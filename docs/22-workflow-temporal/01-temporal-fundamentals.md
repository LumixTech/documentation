---
title: Temporal Temelleri
description: Temporal nedir, workflow + activity + signal + query, durable execution, replay, history, Lumix kullanımı.
sidebar_position: 1
---

## Bu sayfa ne anlatıyor?

Lumix'in **uzun süreli, çok-adımlı ve hata toleranslı** iş akışları (Saga, DSAR workflow, customer onboarding, scheduled retention job) **Temporal.io** üzerinde çalışır. Bu sayfa Temporal'i sıfırdan anlatır, **durable execution** felsefesini açıklar, **workflow + activity + signal + query** kavramlarını gösterir, **replay** ve **history** mekanizmalarını açıklar ve Lumix'in Java SDK ile bu yığını nasıl kullandığını detaylandırır. Hedef kitle: Java/Spring Boot bilen, distributed transaction veya saga'ya yeni dokunan geliştirici.

## 1. Bu nedir? (Sıfırdan)

**Temporal** open-source, **durable workflow** engine. Aşağıdaki sorunları tek noktada çözer:
- Bir iş akışı **dakikalar/saatler/günler** sürebilir; sürecin state'ini DB'de tutmak istemezsin.
- Akış yarıda fail olabilir; **kaldığı yerden devam** etmesini istersin.
- Adımlar başka servisleri çağırır; bir adım fail olursa **retry policy + compensation** mantıklı.
- **Saga** pattern (multi-service iş için "her adım için telafi") tipik kullanım.

Temporal'ın iddiası: "**Kod yaz, Temporal kalıcılığı sağlasın**".

Geliştirici **normal koda benzeyen** workflow yazar:

```java
@WorkflowMethod
public OrderResult execute(OrderInput input) {
    var payment = paymentActivity.charge(input.amount);
    if (payment.failed()) {
        return OrderResult.failed("payment");
    }
    var inventory = inventoryActivity.reserve(input.sku, input.qty);
    if (inventory.outOfStock()) {
        paymentActivity.refund(payment.id);   // compensation
        return OrderResult.failed("out of stock");
    }
    return OrderResult.success();
}
```

Bu kod **bir worker pod'unda çalışırken pod ölse bile** — workflow tüm state'i Temporal server'da. Yeni bir worker workflow'u kaldığı yerden devam ettirir.

### Günlük hayattan analoji

Garson siparişi unutsa bile **mutfağa not düşülmüş**, başka garson devam ettirir. Hiç müşteriden "sipariş ne oldu?" sorusu yok; sistem akışı garanti ediyor.

### Durable execution

Workflow her aktivite çağrısında **state'i Temporal server'a yazar** (event sourcing). Worker pod ölünce, başka worker workflow history'sini **replay** eder; bittiği yerden devam.

## 2. Hangi problemi çözüyor?

Distributed transaction acılar:
- 2PC (2-Phase Commit) yavaş, kilit tutar, modern microservice'ler arası uygulanamaz.
- Manuel saga pattern: state DB'de, retry logic kod içinde, compensation karmaşık.
- Spring `@Scheduled` job'lar: tek pod, fail recovery yok.
- "Hangi siparişler 3 gündür beklemede?" sorusunu cevaplama: manuel queue yönetimi.

| Acı | Temporal yok | Temporal var |
|---|---|---|
| Multi-service saga | State table + retry kod + lock | Workflow + activity + retry policy |
| Long-running task | DB poll loop | Workflow sleep (timer) |
| Scheduled cron | `@Scheduled` (tek pod) | Schedule API (cluster-wide) |
| Retry exponential | Kod | Retry policy YAML |
| Compensation | Manuel state machine | `try-catch` + compensation activity |
| Audit "ne adımdaydı?" | Manuel log | Workflow history (event sourced) |
| Resume on crash | "Cron + yeniden başlat" | Otomatik replay |

### Patlamış üretim hikayesi

Bir ödeme akışı 5 adımdı: ödeme charge → inventory reserve → notification → order create → fulfillment. Adım 3'te servis çöktü. Adım 1-2 yapıldı, 3-4-5 yapılmadı. State DB'de "PENDING_NOTIFICATION" olarak kaldı. Cron job 5 dakikada bir resume etti ama her seferinde aynı yerde fail. Ekip 2 saat manuel müdahale. Temporal: workflow timer + retry + compensation, fail otomatik kurtarılır.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Mimari

```
┌──────────────────────────────────────────────┐
│  Temporal Server                             │
│   ├── Frontend (gRPC API)                    │
│   ├── History (workflow state, event log)    │
│   ├── Matching (task queue dispatch)         │
│   └── Worker (system workflows)              │
│                                              │
│  Persistence: PostgreSQL / Cassandra / MySQL │
│  Visibility: Elasticsearch (opsiyonel)       │
└──────────────────┬───────────────────────────┘
                   │ gRPC
       ┌───────────┴───────────┐
       │                       │
       ▼                       ▼
  Worker Pod A             Worker Pod B
  (Spring Boot)           (Spring Boot)
   ├── WorkflowWorker      ├── WorkflowWorker
   └── ActivityWorker      └── ActivityWorker
```

### 3.2. Temel kavramlar

| Kavram | Anlamı |
|---|---|
| **Workflow** | Akışı tanımlayan kod (uzun ömürlü, durable). |
| **Activity** | Workflow'un dışarıya yaptığı tek call (HTTP, DB write). Idempotent olmalı. |
| **Signal** | Workflow'a dışarıdan asenkron mesaj. |
| **Query** | Workflow'tan dışarıya senkron veri okuma (state). |
| **Task Queue** | Worker'ların dinlediği iş kuyruğu. |
| **Worker** | Workflow/activity kod'unu çalıştıran process. |
| **History** | Workflow'un başına gelen tüm event'ler (event-sourced). |
| **Replay** | History'i tekrar oynatarak state'i reconstruct etmek. |
| **Timer** | Workflow içinde "5 dakika bekle" gibi durable sleep. |
| **Retry Policy** | Activity fail durumunda backoff + retry. |

### 3.3. Workflow constraint'leri

Workflow code **deterministik** olmalı: aynı history → aynı kod path. Kurallar:
- **Random kullanma**: `Workflow.newRandom()` deterministik.
- **Date/time**: `Workflow.currentTimeMillis()`.
- **Sleep**: `Thread.sleep` yasak; `Workflow.sleep` zorunlu.
- **External call**: workflow direkt değil, activity üzerinden.
- **Mutex/locks**: yasak.
- **Generics + reflective state**: dikkat.

Activity ise normal Java code; deterministic değil; HTTP/DB/file işlemi yapar.

### 3.4. History ve replay

```
Workflow başlar → Event 1: WorkflowStarted
Activity 1 → Event 2: ActivityScheduled, Event 3: ActivityCompleted(result)
Timer 5dk → Event 4: TimerStarted, Event 5: TimerFired
Activity 2 → ...

Worker crash → yeni worker:
  - History'i baştan oku
  - Kod'u baştan çalıştır
  - Her activity call'da history'den result'u oku (re-execute etmez)
  - History sonuna gelince real-time devam
```

Bu replay sayesinde state DB'de değil; **history kod tarafından replay edilebilir state**.

### 3.5. Retry policy

```java
ActivityOptions opts = ActivityOptions.newBuilder()
    .setStartToCloseTimeout(Duration.ofSeconds(30))
    .setRetryOptions(RetryOptions.newBuilder()
        .setInitialInterval(Duration.ofSeconds(1))
        .setBackoffCoefficient(2.0)
        .setMaximumInterval(Duration.ofMinutes(5))
        .setMaximumAttempts(5)
        .setDoNotRetry("BusinessRuleException")   // bu hata retry edilmez
        .build())
    .build();
```

### 3.6. Signal ve query

```java
@WorkflowInterface
public interface ApprovalWorkflow {
    @WorkflowMethod
    String execute(String requestId);

    @SignalMethod
    void onApproval(String approver);

    @QueryMethod
    String getStatus();
}
```

Workflow `Workflow.await(() -> approved)` ile signal bekler; query her zaman çağrılabilir.

### 3.7. Versioning

Workflow kodu değişince mevcut history hâlâ replay edilebilmeli. Temporal `getVersion` API'si:

```java
int version = Workflow.getVersion("new-feature", Workflow.DEFAULT_VERSION, 1);
if (version == Workflow.DEFAULT_VERSION) {
    legacyActivity.run();
} else {
    newActivity.run();
}
```

Eski workflow'lar legacy path; yeni workflow'lar yeni path.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Temporal cluster topolojisi

Her müşteri cluster'ında **Temporal cluster** çalışır:
- `temporal-frontend`: 2 replica
- `temporal-history`: 2 replica
- `temporal-matching`: 2 replica
- `temporal-worker` (system): 2 replica
- Backend: PostgreSQL (lumix-data namespace), ayrı `temporal` schema

Workers ise her microservice'in **kendi pod'larında**:
- `academic-service` deploy → ActivityWorker + WorkflowWorker thread
- Veya ayrı `temporal-worker-pool` Deployment (bazı servisler için)

### 4.2. Java SDK + Spring Boot

```xml
<dependency>
    <groupId>io.temporal</groupId>
    <artifactId>temporal-sdk</artifactId>
    <version>1.24.0</version>
</dependency>
<dependency>
    <groupId>io.temporal</groupId>
    <artifactId>temporal-spring-boot-starter</artifactId>
    <version>1.24.0</version>
</dependency>
```

`application.yml`:
```yaml
spring:
  temporal:
    connection:
      target: temporal-frontend.lumix-temporal:7233
    namespace: lumix-omer-okullari
    workers:
      - task-queue: academic-task-queue
        name: academic
        capacity:
          max-concurrent-workflow-task-executors: 50
          max-concurrent-activity-executors: 100
```

### 4.3. Namespace per müşteri

Lumix kararı: müşteri başına **Temporal namespace**:
- `lumix-omer-okullari`
- `lumix-x-vakfi`

Aynı Temporal cluster'da namespace izolasyonu (her cluster zaten müşteri başına ayrı; namespace ek izolasyon).

### 4.4. Task queue isimlendirmesi

```
<service>-task-queue       # academic-task-queue, finance-task-queue
<workflow-type>-tq         # dsar-tq, retention-tq
```

Hangi workflow hangi task queue'da yaşıyor → workflow start zamanı task queue belirtilir.

### 4.5. Kullanım alanları

| Kullanım | Detay sayfası |
|---|---|
| Multi-service Saga | [Saga with Temporal](./saga-with-temporal) |
| DSAR (Data Subject Access Request) workflow | [DSAR Workflow](./dsar-workflow-implementation) |
| Scheduled retention (compliance-service) | [Background Jobs](./background-jobs) |
| Customer onboarding seed orchestration | [Customer Onboarding](../20-iac-provisioning/customer-onboarding-pipeline) |
| Payment saga | finance-service kullanımı |
| Notification batch | scheduled |

### 4.6. Observability

- **Temporal Web UI** → workflow listesi, history view.
- **Prometheus metrics** → workflow latency, retry count, queue depth.
- **OpenTelemetry trace** → workflow + activity span'ları Tempo'ya.
- **Loki log** → workflow ID + activity ID structured.

### 4.7. Idempotency garantisi

Activity'ler idempotent yazılır. Workflow her activity'ye unique `activity_id` verir; activity implementation içeride `activity_id` ile state lookup yapar (duplicate çağrıyı tespit eder). Lumix kuralı: business operation tarafında `Idempotency-Key` header / DB constraint.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **Apache Airflow** | Data pipeline odaklı; ad-hoc workflow değil. |
| **Camunda BPMN** | İş süreci diyagramı + Java; daha enterprise. Çok güçlü ama Temporal SDK + Java kod tercih edildi. |
| **Netflix Conductor** | Workflow JSON DSL; daha az flexible. |
| **AWS Step Functions** | Bulut-kilit. |
| **Spring Batch** | Batch odaklı, long-running workflow için yetersiz. |
| **Manuel saga + DB state** | Kod karmaşık, replay yok, scheduled iş yok. |
| **Quartz / Spring `@Scheduled`** | Tek pod, fail recovery zayıf. |

### Kabul ettiğimiz trade-off'lar

- **Öğrenme eğrisi**: workflow constraint'leri (deterministik, sleep, time) öğrenmek gerekir.
- **Operasyonel ek**: ayrı Temporal cluster (PostgreSQL'i de kullanır).
- **History database büyür**: retention policy gerekir.
- **Workflow versioning disiplini**: production'da workflow upgrade'i dikkatli.

### Tekrar değerlendirme tetikleyicileri

- BPMN visual workflow ihtiyacı doğarsa Camunda.
- Çok düşük cluster yükü için Temporal overkill olabilir → minimum mode (Temporalite docker).

## 6. Pratik örnek

### 6.1. Helm install Temporal

```bash
helm repo add temporal https://go.temporal.io/helm-charts
helm install temporal temporal/temporal \
  --namespace lumix-temporal \
  --create-namespace \
  --version 0.45.0 \
  -f values-temporal.yaml
```

`values-temporal.yaml`:
```yaml
server:
  replicaCount: 2
  config:
    persistence:
      default:
        driver: sql
        sql:
          driver: postgres12
          host: postgres-temporal.lumix-data
          database: temporal
          existingSecret: temporal-db
      visibility:
        driver: elasticsearch
        elasticsearch:
          version: v7
          url:
            scheme: http
            host: elasticsearch.lumix-data
            port: 9200

cassandra:
  enabled: false
mysql:
  enabled: false
postgresql:
  enabled: false   # external
elasticsearch:
  enabled: false   # external (Lumix shared ES)

prometheus:
  enabled: false   # ServiceMonitor ile Lumix Prometheus

web:
  enabled: true
  replicaCount: 2
  ingress:
    enabled: false   # Lumix Traefik
```

### 6.2. Workflow interface

```java
package com.lumix.academic.workflow;

import io.temporal.workflow.*;

@WorkflowInterface
public interface ExamGradingWorkflow {

    @WorkflowMethod
    GradingResult execute(GradingRequest req);

    @SignalMethod
    void onAnswerKeyUpdated(AnswerKey newKey);

    @QueryMethod
    String getStatus();
}
```

### 6.3. Workflow implementation

```java
public class ExamGradingWorkflowImpl implements ExamGradingWorkflow {

    private final GradingActivity gradingActivity = Workflow.newActivityStub(
        GradingActivity.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .setRetryOptions(RetryOptions.newBuilder()
                .setMaximumAttempts(3)
                .setBackoffCoefficient(2.0)
                .build())
            .build()
    );

    private String status = "pending";
    private AnswerKey answerKey;
    private boolean keyUpdated = false;

    @Override
    public GradingResult execute(GradingRequest req) {
        status = "loading_answers";
        var answers = gradingActivity.loadAnswers(req.examId());

        status = "waiting_key";
        // wait for signal up to 30 minutes
        boolean got = Workflow.await(Duration.ofMinutes(30), () -> keyUpdated);
        if (!got) {
            status = "failed_no_key";
            return GradingResult.failed("answer key not received");
        }

        status = "grading";
        var graded = gradingActivity.gradeAll(answers, answerKey);

        status = "notifying";
        gradingActivity.publishResults(req.examId(), graded);

        status = "completed";
        return GradingResult.success(graded.size());
    }

    @Override
    public void onAnswerKeyUpdated(AnswerKey newKey) {
        this.answerKey = newKey;
        this.keyUpdated = true;
    }

    @Override
    public String getStatus() {
        return status;
    }
}
```

### 6.4. Activity implementation

```java
@Component
@ActivityImpl(taskQueues = "academic-task-queue")
public class GradingActivityImpl implements GradingActivity {

    @Autowired private AnswerService answerService;
    @Autowired private GradingService gradingService;
    @Autowired private ResultPublisher publisher;

    @Override
    public List<Answer> loadAnswers(UUID examId) {
        return answerService.findByExamId(examId);
    }

    @Override
    public List<GradedAnswer> gradeAll(List<Answer> answers, AnswerKey key) {
        return gradingService.gradeBatch(answers, key);
    }

    @Override
    public void publishResults(UUID examId, List<GradedAnswer> graded) {
        publisher.publish(examId, graded);
    }
}
```

### 6.5. Workflow başlatma (REST controller)

```java
@RestController
public class ExamGradingController {

    @Autowired private WorkflowClient client;

    @PostMapping("/api/v1/exams/{examId}/start-grading")
    public ResponseEntity<?> start(@PathVariable UUID examId) {
        var options = WorkflowOptions.newBuilder()
            .setTaskQueue("academic-task-queue")
            .setWorkflowId("exam-grading-" + examId)
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.REJECT_DUPLICATE)
            .build();

        var workflow = client.newWorkflowStub(ExamGradingWorkflow.class, options);
        WorkflowClient.start(workflow::execute, new GradingRequest(examId));

        return ResponseEntity.accepted().body(Map.of("workflow_id", "exam-grading-" + examId));
    }

    @PostMapping("/api/v1/exams/{examId}/upload-key")
    public ResponseEntity<?> uploadKey(@PathVariable UUID examId, @RequestBody AnswerKey key) {
        var workflow = client.newWorkflowStub(ExamGradingWorkflow.class, "exam-grading-" + examId);
        workflow.onAnswerKeyUpdated(key);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/api/v1/exams/{examId}/status")
    public ResponseEntity<String> status(@PathVariable UUID examId) {
        var workflow = client.newWorkflowStub(ExamGradingWorkflow.class, "exam-grading-" + examId);
        return ResponseEntity.ok(workflow.getStatus());
    }
}
```

### 6.6. Worker bootstrap (Spring config)

```java
@Configuration
public class TemporalWorkerConfig {

    @Bean
    public WorkflowImplementationOptions opts() {
        return WorkflowImplementationOptions.newBuilder()
            .setFailWorkflowExceptionTypes(BusinessRuleException.class)
            .build();
    }

    @Bean
    public WorkerFactory workerFactory(WorkflowClient client) {
        var factory = WorkerFactory.newInstance(client);
        var worker = factory.newWorker("academic-task-queue");
        worker.registerWorkflowImplementationTypes(ExamGradingWorkflowImpl.class);
        worker.registerActivitiesImplementations(new GradingActivityImpl(...));
        factory.start();
        return factory;
    }
}
```

### 6.7. Workflow versioning örnek

```java
public GradingResult execute(GradingRequest req) {
    var answers = gradingActivity.loadAnswers(req.examId());

    int v = Workflow.getVersion("audit-step", Workflow.DEFAULT_VERSION, 1);
    if (v >= 1) {
        gradingActivity.publishAuditEvent(req.examId());
    }

    // ...
}
```

Eski workflow history audit-step görmemiş → `Workflow.DEFAULT_VERSION` path; yeni workflow'lar v=1 path.

## 7. Dikkat edilecek tuzaklar

- **Workflow içinde non-deterministic kod**: `new Date()`, `Math.random()`, `Thread.sleep()` → replay sırasında farklı sonuç → workflow corruption.
- **Activity'nin idempotent olmaması**: retry sonrası duplicate side effect (örn. iki kere ödeme). Activity her zaman business-key tabanlı idempotent.
- **Çok büyük workflow input/output**: gRPC sınırı (default 4 MB). Büyük data S3'e + workflow ID referansı.
- **Workflow versioning disiplini yok**: yeni kod path eski workflow'ları bozar → `NonDeterministicWorkflowException`. `getVersion` zorunlu yeni değişikliklerde.
- **Activity timeout'u yok**: takılan call workflow'u sonsuz bekletir. `startToCloseTimeout` her zaman.
- **Retry policy çok agresif**: sonsuz retry, downstream'i ezer. `maximumAttempts` veya `maximumInterval` set et.
- **Worker pod'ların yetersiz**: queue dolu, workflow bekler. HPA + queue depth metric.
- **History retention**: cluster büyüyünce PostgreSQL şişer. `archival` config + retention period.
- **Workflow ID re-use yanlış**: aynı ID ile iki start → conflict. `WorkflowIdReusePolicy` doğru ayarla.
- **Local activity vs activity**: local activity worker'da inline çalışır (history'e tek event); kısa idempotent için. Yanlış kullanım: external call'ı local yapmak → no retry on worker crash.
- **`Workflow.await` timeout'suz**: sonsuz beklemeden kaçın; `Workflow.await(duration, condition)`.
- **Database isolation level**: Temporal serializable transactions ister; PostgreSQL config doğru.
- **Spring `@Autowired` workflow class içine**: workflow class deterministic; DI yasak. Activity'lerde OK.

## 8. Diğer konularla ilişkisi

- [Saga with Temporal](./saga-with-temporal) — multi-service saga implementation
- [DSAR Workflow Implementation](./dsar-workflow-implementation) — KVKK/GDPR uyumu
- [Background Jobs](./background-jobs) — scheduled workflow
- [Event-Driven Architecture](../event-driven-architecture) — Kafka outbox + Temporal birlikte
- [Customer Onboarding Pipeline](../20-iac-provisioning/customer-onboarding-pipeline) — orchestrator olarak Temporal kullanılabilir
- [Database Architecture](../database-architecture) — Temporal PostgreSQL backend

## 9. Daha derine inmek için

- Resmi doc: [https://docs.temporal.io/](https://docs.temporal.io/)
- Java SDK: [https://docs.temporal.io/dev-guide/java](https://docs.temporal.io/dev-guide/java)
- "Workflow Engines and Patterns" — Temporal blog
- Replication: Maxim Fateev konferans konuşmaları (Temporal kurucu)
- "Designing Data-Intensive Applications" — Martin Kleppmann (durable execution context)
- Search keyword'leri: *"temporal durable execution"*, *"temporal saga compensation"*, *"temporal versioning getversion"*, *"temporal activity idempotency"*

## 10. Sözlük

- **Temporal**: Durable workflow engine (OSS, Apache 2.0).
- **Workflow**: Akışı tanımlayan deterministik kod.
- **Activity**: Workflow'un dış çağrı (HTTP, DB, file).
- **Worker**: Workflow/activity kodunu çalıştıran process.
- **Task Queue**: Worker'ların iş aldığı kuyruk.
- **Signal**: Workflow'a dışarıdan async mesaj.
- **Query**: Workflow'tan senkron state okuma.
- **History**: Workflow event log (event sourced).
- **Replay**: History'i tekrar oynatarak workflow state'i yeniden inşa etmek.
- **Durable execution**: State'i otomatik kalıcı tutarak crash-resilient yürütme.
- **Saga**: Multi-step iş için "her adım için telafi" pattern.
- **Compensation**: Saga'da bir adımın geri alınması.
- **Retry policy**: Activity fail durumunda backoff + retry konfigürasyonu.
- **Timer**: Workflow içinde durable sleep.
- **Versioning (`getVersion`)**: Workflow kodu değişimini history-compatible tutma.
- **Namespace**: Temporal cluster içinde mantıksal izolasyon.
- **Workflow ID re-use policy**: Aynı ID ile yeni workflow başlatma davranışı.
- **`NonDeterministicWorkflowException`**: Replay sırasında kod path uyumsuzluğu hatası.
