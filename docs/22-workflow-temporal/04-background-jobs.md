---
title: Background Jobs ve Scheduled Workflows
description: Scheduled workflow (cron), retention enforcement (compliance-service), cleanup job'ları, Spring `@Scheduled` yerine Temporal.
sidebar_position: 4
---

## Bu sayfa ne anlatıyor?

Lumix'in periyodik işleri (retention enforcement, expired token cleanup, daily summary maili, weekly report, audit log archive, license expiry warning) **Spring `@Scheduled` veya Quartz yerine Temporal Scheduled Workflows** ile yönetilir. Bu sayfa Temporal Schedule API'sını sıfırdan anlatır, **cron syntax**, **paused / triggered run / catch-up policy**, **scheduled vs cronWorkflow** ayrımını gösterir, Lumix'in **retention enforcement** (compliance-service'in en kritik scheduled işi) örneğini detaylandırır, hangi durumda Temporal yerine alternatif (cron Job) seçileceğini söyler. Hedef kitle: Temporal temellerini bilen ([Temporal Fundamentals](./temporal-fundamentals)), backend scheduled iş yazan geliştirici.

## 1. Bu nedir? (Sıfırdan)

Bir uygulama her saat/gün/hafta tetiklenmesi gereken işleri vardır:
- Son 24 saatlik audit log özeti
- Süresi geçmiş JWT'leri Redis'ten temizle
- Retention policy: 5 yıl önce kayıtları silmeli
- Lisans expire warning (30 gün)
- Index optimize, vacuum
- Müşteri kullanım raporu

Klasik Java yaklaşımı: `@Scheduled(cron = "0 0 2 * * *")` (Spring). Sorunlar:
- Tek pod'da çalışır (cluster-wide tek seferlik garanti yok)
- Pod ölürse atlanır
- State yok; "son çalışma ne zamandı?" cevabı belirsiz
- Retry zayıf
- Audit yok

**Temporal Scheduled Workflow**: cron-style + durable + cluster-wide + audit + retry zincirinden ibaret bir mekanizma.

İki yaklaşım:
- **Schedule (yeni API, Temporal 1.18+)**: Schedule CRD benzeri; periyodik **workflow execution** üretir.
- **Cron Workflow (legacy)**: `WorkflowOptions.setCronSchedule(...)` ile workflow kendini "her seferinde yeniden başlatır" pattern'i.

Lumix kararı: **Schedule API** (modern).

### Günlük hayattan analoji

Eski çalar saat: tek seferlik, batarya bitince susar (Spring @Scheduled). Smart home routine (cron): merkezde Google Home, tetiklenmesi gereken eylem listesi, her sabah 7'de kahve makinesi açılır; ev kapanıp açılsa da routine kayıp olmaz. Temporal Schedule.

## 2. Hangi problemi çözüyor?

| Acı | `@Scheduled` | Temporal Schedule |
|---|---|---|
| Cluster-wide tek seferlik garanti | ShedLock + DB | Built-in |
| Crash recovery | Atlanır | Yeniden tetiklenir |
| "Son çalışma ne zamandı?" | Manuel log | Schedule UI |
| Manuel tetik (ad-hoc) | Servisi restart, hacky | "Trigger" tek tıklamayla |
| Paused durumu | Config + restart | "Pause" tek tıklamayla |
| Retry on fail | Manuel | Workflow retry policy |
| Catch-up (geçmiş kaçırılan run'lar) | Atlanır | `BACKFILL` policy |
| Audit | Yok | Workflow history |
| Cron syntax + UI | Yok | UI'da editlenebilir |

### Patlamış üretim hikayesi

`@Scheduled` retention job 6 ayda bir prod'da ShedLock ile çalışıyordu. Bir gün ShedLock DB row bozuldu (corruption), iki pod aynı anda silme job'u çalıştırdı → race condition, bazı kayıt iki kez silindi. Temporal Schedule olsaydı: native single-execution garanti.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Schedule yapısı

```java
ScheduleSpec spec = ScheduleSpec.newBuilder()
    .setCronExpressions(List.of("0 2 * * *"))            // daily at 02:00
    .setTimeZoneName("Europe/Istanbul")
    .build();

ScheduleAction action = ScheduleActionStartWorkflow.newBuilder()
    .setWorkflowType(RetentionEnforcementWorkflow.class)
    .setOptions(WorkflowOptions.newBuilder()
        .setWorkflowId("retention-enforcement-{{.ScheduledTime.Format \"20060102\"}}")
        .setTaskQueue("compliance-task-queue")
        .build())
    .setArguments(/* workflow input */)
    .build();

Schedule schedule = Schedule.newBuilder()
    .setSpec(spec)
    .setAction(action)
    .setPolicy(SchedulePolicy.newBuilder()
        .setOverlap(ScheduleOverlapPolicy.SKIP)           // önceki bitmediyse atla
        .setCatchupWindow(Duration.ofHours(6))            // geç kalmış run 6h içinde tetiklenir
        .build())
    .build();

ScheduleClient client = ScheduleClient.newInstance(temporalClient.getServiceStubs(), ...);
client.createSchedule(
    "compliance-retention-enforcement",
    schedule,
    ScheduleOptions.newBuilder().build()
);
```

### 3.2. Cron syntax

Standart 5 alan: `dakika saat ayın-günü ay haftanın-günü`.
```
0 2 * * *          → her gün 02:00
*/15 * * * *       → her 15 dakika
0 9 * * MON-FRI    → hafta içi 09:00
0 0 1 * *          → her ayın 1'inde
```

Veya `@daily`, `@hourly`, `@every 30m` aliases.

### 3.3. Overlap policy

Önceki run hâlâ çalışıyorken yeni run zamanı gelirse:
- `SKIP`: yeni run'u atla.
- `BUFFER_ONE`: bir tane bekler kuyruğa.
- `BUFFER_ALL`: tüm kaçırılanları kuyruğa al.
- `CANCEL_OTHER`: önceki iptal, yeni başlat.
- `TERMINATE_OTHER`: önceki zorla bitir, yeni başlat.
- `ALLOW_ALL`: paralel.

Lumix default: `SKIP` (idempotency güvenliği).

### 3.4. Manuel trigger

```java
client.getHandle("compliance-retention-enforcement").trigger();
```

UI'dan da tek tıkla. Acil müdahale veya test için.

### 3.5. Pause/Unpause

```java
client.getHandle("compliance-retention-enforcement").pause("maintenance window");
client.getHandle("compliance-retention-enforcement").unpause("incident resolved");
```

Pause sırasında scheduled run'lar olmaz; **trigger** ile manuel çalıştırılabilir.

### 3.6. Backfill (geriye dönük tetik)

Schedule oluşturulduğunda **geçmişte kaçırılan run'lar** istenebilir:
```java
client.getHandle("retention-enforcement").backfill(List.of(
    new ScheduleBackfill(
        Instant.parse("2026-04-01T02:00:00Z"),
        Instant.parse("2026-05-01T02:00:00Z"),
        ScheduleOverlapPolicy.SKIP
    )
));
```

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Hangi job'lar Temporal Scheduled?

| Job | Sahip Servis | Cron | Açıklama |
|---|---|---|---|
| Retention Enforcement | compliance-service | `0 3 * * *` | Retention policy bazlı veri silme/anonimleştirme |
| License Expiry Check | identity-service (license-svc) | `0 9 * * *` | Expire eden lisanslar için warning |
| Token Cleanup | identity-service | `0 */6 * * *` | Expired token Redis temizliği (yedek; Redis EXPIRE zaten) |
| Audit Log Archive | audit-service | `0 4 * * SUN` | Haftalık eski log RustFS soğuk arşive |
| ES Index Optimization | search-service | `0 5 * * SUN` | Index merge, force_merge |
| Daily Summary Email | notification-service | `0 18 * * *` | Tenant admin'lere günlük özet |
| Outbox Cleanup | each microservice | `0 1 * * *` | Processed outbox row'ları sil |
| Idempotency Key Cleanup | each microservice | `0 2 * * *` | 30 gün eski idempotency kayıtları |
| Storage Lifecycle | file-service | `0 6 * * *` | RustFS lifecycle policy enforcement (yedek) |
| Statistics Aggregation | performance-service | `0 0 * * *` | Daily KPI aggregation |

### 4.2. Schedule kayıt — bootstrap

Her servis startup'ta kendi schedule'larını **idempotent kayıt eder** (compliance-service örnek):

```java
@Component
@RequiredArgsConstructor
public class ScheduleBootstrap implements ApplicationListener<ContextRefreshedEvent> {

    private final ScheduleClient scheduleClient;

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        registerRetentionEnforcement();
        registerLicenseExpiryCheck();
    }

    private void registerRetentionEnforcement() {
        var handle = scheduleClient.getHandle("compliance-retention-enforcement");
        try {
            handle.describe();   // var mı kontrol
        } catch (NotFound ex) {
            scheduleClient.createSchedule(
                "compliance-retention-enforcement",
                Schedule.newBuilder()
                    .setSpec(ScheduleSpec.newBuilder()
                        .setCronExpressions(List.of("0 3 * * *"))
                        .setTimeZoneName("UTC")
                        .build())
                    .setAction(ScheduleActionStartWorkflow.newBuilder()
                        .setWorkflowType(RetentionEnforcementWorkflow.class)
                        .setOptions(WorkflowOptions.newBuilder()
                            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.ALLOW_DUPLICATE)
                            .setTaskQueue("compliance-task-queue")
                            .setWorkflowExecutionTimeout(Duration.ofHours(4))
                            .build())
                        .setArgs(new RetentionInput(Instant.now()))
                        .build())
                    .setPolicy(SchedulePolicy.newBuilder()
                        .setOverlap(ScheduleOverlapPolicy.SKIP)
                        .setCatchupWindow(Duration.ofHours(6))
                        .build())
                    .build(),
                ScheduleOptions.newBuilder().build()
            );
        }
    }
}
```

### 4.3. Retention Enforcement workflow

Compliance-service'in en kritik scheduled iş'i:

```java
@WorkflowInterface
public interface RetentionEnforcementWorkflow {
    @WorkflowMethod
    RetentionResult execute(RetentionInput input);
}

public class RetentionEnforcementWorkflowImpl implements RetentionEnforcementWorkflow {

    private final RetentionActivities activities = Workflow.newActivityStub(
        RetentionActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(30))
            .setRetryOptions(RetryOptions.newBuilder()
                .setMaximumAttempts(3)
                .setBackoffCoefficient(2.0)
                .build())
            .build()
    );

    @Override
    public RetentionResult execute(RetentionInput input) {
        // 1. Tüm retention policy'leri yükle
        var policies = activities.loadRetentionPolicies();

        var totalDeleted = 0L;
        var totalAnonymized = 0L;
        var errors = new ArrayList<String>();

        // 2. Her policy için
        for (var policy : policies) {
            try {
                // Candidate sayım
                var candidates = activities.findExpiredRecords(policy);
                if (candidates.isEmpty()) continue;

                // Tenant başına batch
                for (var batch : Lists.partition(candidates, 1000)) {
                    var result = switch (policy.action()) {
                        case DELETE -> activities.deleteBatch(policy, batch);
                        case ANONYMIZE -> activities.anonymizeBatch(policy, batch);
                        case ARCHIVE -> activities.archiveBatch(policy, batch);
                    };

                    totalDeleted += result.deletedCount();
                    totalAnonymized += result.anonymizedCount();

                    // Audit
                    activities.recordRetentionAudit(policy, result);
                }
            } catch (ActivityFailure af) {
                errors.add(policy.id() + ": " + af.getCause().getMessage());
            }
        }

        // 3. Özet rapor
        return new RetentionResult(totalDeleted, totalAnonymized, errors);
    }
}
```

### 4.4. Audit log

Her scheduled run audit-service'e event publish:
- `schedule.run.started{name=...}`
- `schedule.run.completed{name=..., outcome=success|partial|failure, metrics={...}}`

### 4.5. Observability

- Temporal Web UI: schedule list, her schedule'ın son N run'u.
- Prometheus: `temporal_schedule_action_attempts_total`, `temporal_schedule_workflow_execution_failed_total`.
- Grafana panel: Schedule SLO dashboard.
- Slack alert: 24 saatte run yoksa veya 3 consecutive fail.

### 4.6. Test stratejisi

Schedule değil, **workflow** test edilir. Test framework içinde direkt `WorkflowMethod` çağrısı:
```java
@Test
public void retention_workflow_deletes_expired() {
    var stub = testEnv.getWorkflowClient().newWorkflowStub(
        RetentionEnforcementWorkflow.class,
        WorkflowOptions.newBuilder().setTaskQueue("compliance-task-queue").build());
    var result = stub.execute(new RetentionInput(Instant.now()));
    assertThat(result.totalDeleted()).isPositive();
}
```

### 4.7. Hangi durumda Temporal kullanmıyoruz?

- **Çok yüksek frekanslı, basit, idempotent** işler: K8s `CronJob` daha hafif. Örnek: `Promtail` log forward, `etcd snapshot`.
- **Trivial cleanup** (örn. Redis EXPIRE zaten halletmiyor mu?): native özellik.
- **Tek shot bootstrap (Velero schedule oluştur)**: K8s job yeterli.

Lumix kuralı: **business logic içeren** scheduled iş → Temporal; **infra/maintenance** → K8s CronJob.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **Spring `@Scheduled`** | Tek pod, no cluster-wide guarantee, replay yok. |
| **Quartz Scheduler** | DB-driven; cluster-wide ama operasyonel yük; state-management ek. |
| **ShedLock + `@Scheduled`** | Cluster-wide eklenir; ama "son çalışma ne durumdaydı?" cevabı yok. |
| **K8s CronJob** | Infra için OK; ancak business logic için audit/retry/replay zayıf. |
| **Quartz Cluster** | Karmaşık config, eski araç. |
| **Cron Workflow (legacy Temporal)** | Yeni Schedule API daha temiz. |

### Kabul ettiğimiz trade-off'lar

- **Temporal cluster zorunlu**: zaten kullanıyoruz; ekstra maliyet yok.
- **Schedule UI öğrenmek**: bir kez öğrenilir.
- **Bootstrap idempotency**: schedule'ı update edebilmek için describe + update logic gerekiyor.

### Tekrar değerlendirme tetikleyicileri

- Temporal cluster yükü critical scheduled işler artarsa ölçeklenme.
- Çok hafif scheduled iş çokluğu Temporal'i abartılı hale getirirse → K8s CronJob'a kaydırma.

## 6. Pratik örnek

### 6.1. Schedule update örneği

```java
private void updateSchedule(String name, String newCron) {
    var handle = scheduleClient.getHandle(name);
    handle.update(updateInput -> {
        var current = updateInput.getDescription().getSchedule();
        return new ScheduleUpdate(
            Schedule.newBuilder(current)
                .setSpec(ScheduleSpec.newBuilder()
                    .setCronExpressions(List.of(newCron))
                    .setTimeZoneName(current.getSpec().getTimeZoneName())
                    .build())
                .build()
        );
    });
}
```

### 6.2. License Expiry Workflow

```java
@WorkflowInterface
public interface LicenseExpiryCheckWorkflow {
    @WorkflowMethod
    void execute();
}

public class LicenseExpiryCheckWorkflowImpl implements LicenseExpiryCheckWorkflow {

    private final LicenseActivities license = Workflow.newActivityStub(
        LicenseActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .build());

    @Override
    public void execute() {
        var expiringIn30 = license.findExpiringLicenses(Duration.ofDays(30));
        for (var lic : expiringIn30) {
            license.notifyCustomer(lic, "30 gün içinde lisansınızın süresi dolacak");
            license.notifyLumixSales(lic);
        }

        var expired = license.findExpiredLicenses();
        for (var lic : expired) {
            license.activateGracePeriod(lic);
            license.notifyCustomer(lic, "Lisansınız süresi geçti. Grace period başladı.");
        }
    }
}
```

### 6.3. Audit Log Archive Workflow

```java
public class AuditArchiveWorkflowImpl implements AuditArchiveWorkflow {

    @Override
    public void execute() {
        var cutoff = LocalDate.now().minusDays(90);   // 90 gün öncesi ılık storage'dan soğuk arşive
        var batchSize = 10_000;

        long total = 0;
        while (true) {
            var batch = auditActivities.fetchOlderThan(cutoff, batchSize);
            if (batch.isEmpty()) break;
            auditActivities.uploadBatchToColdStorage(batch);
            auditActivities.deleteFromHotStorage(batch);
            total += batch.size();
        }
        auditActivities.recordArchiveRun(total, Instant.now());
    }
}
```

### 6.4. Schedule UI (Temporal Web)

```
https://temporal.lumix.io/namespaces/lumix-omer-okullari/schedules

┌─────────────────────────────────────────────────────────┐
│ compliance-retention-enforcement   [Active]             │
│ Cron: 0 3 * * *  TZ: UTC                                │
│ Last run: 2026-05-27 03:00:14 UTC (Success)             │
│ Next run: 2026-05-28 03:00:00 UTC                       │
│ Total runs: 152, Failures: 2                            │
│ [Trigger] [Pause] [Edit] [Backfill]                     │
└─────────────────────────────────────────────────────────┘
```

### 6.5. CI ile schedule güncelleme

Schedule'lar **kod ile birlikte versiyonlu** olmalı. CI bir job'ta:
```bash
# scripts/sync-schedules.sh (servis startup'ta da çalışabilir)
java -jar compliance-schedule-sync.jar
```

Bu jar ScheduleClient kullanarak idempotent kayıt yapar.

### 6.6. Prometheus alert

```yaml
- alert: ScheduledWorkflowFailing
  expr: increase(temporal_schedule_workflow_execution_failed_total[24h]) > 3
  for: 30m
  labels: { severity: warning }
  annotations:
    summary: "Schedule {{ $labels.schedule_id }} failing"

- alert: ScheduledWorkflowMissed
  expr: time() - temporal_schedule_last_action_timestamp > 86400
  for: 1h
  labels: { severity: critical }
  annotations:
    summary: "Schedule {{ $labels.schedule_id }} hasn't run in 24h"
```

### 6.7. Operasyonel runbook (örnek: retention job fail)

```
1. Temporal Web UI → schedule "compliance-retention-enforcement"
2. Last run → workflow detail → history
3. Error mesajı incele (genelde activity timeout veya DB connection)
4. Manuel trigger (sorun çözüldükten sonra):
   $ temporal schedule trigger --schedule-id compliance-retention-enforcement
5. Run başarılı mı kontrol → audit-service event check
```

## 7. Dikkat edilecek tuzaklar

- **Workflow ID re-use ALLOW_DUPLICATE yerine REJECT_DUPLICATE**: scheduled run aynı ID kullanırsa ikinci başlatılamaz. Schedule API otomatik unique ID üretir; ama custom ID verirseniz dikkat.
- **Overlap policy ALLOW_ALL ile uzun süren job**: paralel çalışma, race condition. Lumix default SKIP.
- **`@Scheduled` ve Temporal'ı aynı işte birlikte kullanmak**: iki seferlik tetik. Yalnız bir tane.
- **Schedule oluşturma `ApplicationListener` yerine `@PostConstruct`**: bean sırası sorunu; client hazır değil. `ContextRefreshedEvent` veya `ApplicationReadyEvent`.
- **Catch-up window yok**: cluster downtime sonrası kaçırılan run'lar gitmiş gibi. `CatchupWindow` ayarla.
- **Schedule timezone yanlış**: müşteri Türkiye saatinde bekliyor ama UTC'de çalışıyor. TZ explicit.
- **Sleep/timer workflow içinde scheduled aktivite ile karışmak**: scheduled workflow zaten cron + workflow timer içinde sleep zorlamaz. Ama gerekiyorsa `Workflow.sleep(...)` durable.
- **Outbox cleanup'ı her servisin kendisi yapması yerine merkezi olarak**: serviceler bağımsız; her servis kendi kendi schedule'ını yönetir.
- **Schedule UI'da manuel pause + unpause unutmak**: maintenance sonrası unpause atlanır, job hiç çalışmaz. Audit + alert.
- **Audit event publish yapmayan schedule**: incident sonrası "ne zaman çalıştı?" cevapsız. Workflow zorunlu audit emit.
- **Çok büyük batch (1M kayıt)**: activity timeout dolar. Pagination + batch içinde batch.
- **Idempotency olmadan delete batch**: retry sonrası double delete (zaten yok). Idempotent: "if exists then delete".

## 8. Diğer konularla ilişkisi

- [Temporal Fundamentals](./temporal-fundamentals) — workflow temelleri
- [Saga with Temporal](./saga-with-temporal) — multi-service iş
- [DSAR Workflow Implementation](./dsar-workflow-implementation) — özelleşmiş workflow
- [Compliance Service](../security-compliance) — retention policy ve enforcement
- [Audit Log](../security-compliance) — scheduled run kayıtları
- [Observability](../observability-qa) — schedule monitoring
- [Storage Lifecycle](../12-storage-and-files) — RustFS lifecycle + Lumix scheduled archive

## 9. Daha derine inmek için

- Temporal Schedule API doc: [https://docs.temporal.io/workflows#schedule](https://docs.temporal.io/workflows#schedule)
- "Site Reliability Engineering" — Google, batch jobs
- "Designing Distributed Systems" — Brendan Burns
- Kron Wood'un "Scheduled tasks at scale" Twitter rant'leri
- Search keyword'leri: *"temporal schedule overlap policy"*, *"temporal cron workflow vs schedule"*, *"shedlock alternatives"*, *"distributed scheduled jobs cluster wide"*

## 10. Sözlük

- **Scheduled Workflow**: Cron-style periyodik tetiklenen workflow.
- **Schedule (Temporal API)**: Workflow tetik tanımı + policy + state.
- **Cron expression**: `0 2 * * *` gibi 5 alanlı zaman ifadesi.
- **Overlap policy**: Önceki run hâlâ çalışırken yeni run davranışı (SKIP, BUFFER, CANCEL_OTHER, ALLOW_ALL).
- **Catch-up window**: Kaçırılan run'ların geriye dönük tetik penceresi.
- **Backfill**: Geçmişte kaçırılan run'ları manuel tetikleme.
- **Trigger (manual)**: Schedule'ı plan dışı çalıştırma.
- **Pause / Unpause**: Schedule'ı geçici askıya alma.
- **Retention policy**: Verinin saklanma süresi kuralları.
- **Crypto-shredding**: Encryption key destroy ile veriyi okunamaz hale getirme.
- **ShedLock**: Spring `@Scheduled` için cluster-wide tek-instance kilidi (Temporal alternatifsiz dünyada kullanılırdı).
- **K8s CronJob**: Kubernetes native cron job (infra için).
- **Audit emit**: Job'un çalıştığını audit-service'e bildirme.
- **SLO (Service Level Objective)**: Hedeflenen hizmet seviyesi (örn. schedule başarı oranı).
