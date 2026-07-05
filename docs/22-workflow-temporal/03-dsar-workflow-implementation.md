---
title: DSAR Workflow Implementasyonu
description: DSAR workflow — intake → verification → eligibility check → approval → anonymization → audit. Temporal'da multi-step. Her servisin anonymization handler'ı. Idempotent.
sidebar_position: 3
---

## Bu sayfa ne anlatıyor?

KVKK ve GDPR, veri sahibinin **erişim, taşınabilirlik, düzeltme, silme/anonimleştirme** (DSAR — Data Subject Access Request) haklarını sunma yükümlülüğü doğurur. Lumix'te DSAR çok-adımlı, çok-servisli bir akıştır; **Temporal saga** olarak modellenir. Bu sayfa DSAR'ı sıfırdan açıklar, yasal arka planı kısaca tanıtır, Lumix'in **intake → verification → eligibility → approval → execution (anonymization/export) → audit** akışını gösterir, her **microservice'in anonymization handler**'ını tanımlar, **idempotency** + **deadline (30 gün)** + **manual approval** detaylandırır. Hedef kitle: Temporal ve Saga temellerini bilen ([Temporal Fundamentals](./01-temporal-fundamentals.md), [Saga with Temporal](./02-saga-with-temporal.md)), KVKK/GDPR'a duyarlı backend mühendisi.

## 1. Bu nedir? (Sıfırdan)

**DSAR (Data Subject Access Request)**: bir kişinin kendi verisi hakkında yaptığı resmi talep. Üç ana kategori:
- **Access**: "Hakkımda hangi veriler var?" (export, JSON/PDF).
- **Erasure / Right to be Forgotten**: "Sil veya anonimleştir."
- **Portability**: "Verimi yapılandırılmış formatta al."
- **Rectification**: "Veriyi düzelt."

Yasal cevap süresi: **KVKK 30 gün, GDPR 30 gün** (1 ay; complex case +60).

Lumix'te bu talepler:
1. **End-user UI** veya **destek kanalı** ile gelir (`compliance-service`).
2. **Identity verification** yapılır.
3. **Eligibility** kontrol (yasal istisna var mı? örn. mali kayıt 5 yıl tutulmalı).
4. **Approval** (manuel) — özellikle silme için.
5. **Execution**: her servisin kendi anonymization handler'ı tetiklenir.
6. **Audit** + kullanıcıya sonuç bildirimi.

Tüm bu adımlar **Temporal workflow** olarak uygulanır; **idempotent** ve **yarıda kalmaya dayanıklı**.

### Günlük hayattan analoji

Postaneye gidip "benim hakkımdaki tüm dosyaları gönder" diyorsun. Postane: kimliği kontrol et, dosyaların yerini sorgula (her şubede ayrı), yasal istisnalar mı var (örn. devlet talebi), onay aldıktan sonra dosyayı hazırla, sana gönder, defterine kaydet. Çoklu adım, manuel onay, dış sistem etkileşimi → Temporal saga.

## 2. Hangi problemi çözüyor?

| Acı | Manuel DSAR | DSAR workflow |
|---|---|---|
| 30 gün süreyi kaçırma | E-posta zincirinde unutulur | Workflow timer + escalation |
| Hangi servis veriyi tuttuğunu unutmak | Manuel envanter | Her servisin anonymization handler API'si zorunlu |
| Idempotency | "Tekrar tıkladım sildi mi?" | `request_id` + Temporal workflow ID |
| Audit | Manuel kayıt | Workflow history + audit-service event'leri |
| Kısmi başarı (3 servis sildi, 1 fail) | Manuel düzelt | Saga compensation + retry |
| Yasal istisna check | Hatırlatma | Eligibility activity |
| Compliance raporu | Manuel toplama | DB query + Grafana dashboard |

### Patlamış üretim hikayesi

KVKK denetiminde "geçen yıl gelen 47 DSAR talebi nasıl işlendi?" sorusu. Manuel sistemde Excel listesi yarısı eksik. Workflow olsaydı: Temporal Web UI'da her workflow'un detayı + history. Lumix bu disipline baştan koyar.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. DSAR workflow makro akış

```
┌─────────────────────────────────────────────────────────┐
│ DSAR Workflow                                           │
│                                                         │
│  1. intake (compliance-service)                         │
│     → request_id, type, subject_email, scope            │
│                                                         │
│  2. identity verification                               │
│     → email link / OTP / Knowledge-Based Auth           │
│     → 7 gün bekle (otherwise abort)                     │
│                                                         │
│  3. eligibility check                                   │
│     → legal hold? retention obligation? audit trail?    │
│     → bazı veriler hariç (mali 5 yıl)                   │
│                                                         │
│  4. (silme/değiştirme ise) approval                     │
│     → DPO / Data Protection Officer manuel onay         │
│     → 14 gün bekle                                      │
│                                                         │
│  5. execution                                           │
│     a. data inventory (her servise sor)                 │
│     b. eğer access → export (file-service'e yaz)        │
│     c. eğer erasure → her servise anonymize sinyali     │
│     d. encryption key destroy (Vault)                   │
│                                                         │
│  6. audit (audit-service event)                         │
│                                                         │
│  7. notify subject (e-posta + URL)                      │
│                                                         │
│  8. compliance record (kalıcı log)                      │
└─────────────────────────────────────────────────────────┘
```

### 3.2. Workflow ID

`dsar-{request_id}` → idempotent + UI ile cross-reference.

### 3.3. Her microservice'in DSAR API'si

Microservice contract zorunluğu:
- `POST /api/internal/dsar/export` — tenant subject_id için JSON export
- `POST /api/internal/dsar/anonymize` — tenant subject_id için anonimleştirme
- `GET /api/internal/dsar/preview` — anonimleştirilecek alanların listesi

Activity'ler bu endpoint'leri çağırır.

### 3.4. Anonymization stratejisi

Aşamalı:
1. **Identifier fields** (e-posta, telefon, TC, ad-soyad): hash veya tombstone.
2. **Linked records** (yorum, mesaj): yazar bilgisi "Anonymous User".
3. **Auditable records** (log, audit): değişmez; ama PII kısmı pseudonymized.
4. **Encrypted DEK destroy**: kullanıcı-spesifik encryption key Vault'tan silinir → şifreli veri okunamaz hale gelir.

Detay: [Compliance](../security-compliance) ve [Privacy](../security-compliance).

### 3.5. Eligibility istisnaları

KVKK Madde 28 ve GDPR Article 17.3 istisnaları:
- Mali kayıt (TR Vergi Usul Kanunu: 5 yıl saklama)
- Sözleşmesel zorunluluk (devam eden ilişki)
- Audit log (immutable)
- Hukuki uyuşmazlık

Eligibility activity her servise sorar: "subject_id için silinemez kayıt var mı?" → list döner; rapor subject'a sunulur.

### 3.6. Subject identity verification

Email + magic link:
1. compliance-service e-posta gönderir, link valid 7 gün.
2. Link tıklanınca workflow `signal(verified=true)`.
3. Workflow `Workflow.await(Duration.ofDays(7), () -> verified)`.
4. Süre dolarsa abort.

### 3.7. DPO approval

Erasure talepleri için DPO (Veri Koruma Yetkilisi) onayı:
- Internal Admin Panel'de "Pending DSAR Approvals" listesi.
- DPO inceler, onaylar/reddeder.
- Workflow signal alır.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Compliance-service rolü

compliance-service tüm DSAR akışının "front-door"'u:
- DSAR API endpoint'leri (`/api/v1/dsar/requests`)
- Identity verification mail orchestration
- DPO approval UI backend
- Workflow client başlatma
- Compliance audit kaydı

### 4.2. Workflow interface

```java
@WorkflowInterface
public interface DsarWorkflow {

    @WorkflowMethod
    DsarResult execute(DsarRequest request);

    @SignalMethod
    void onIdentityVerified();

    @SignalMethod
    void onDpoApproved(String dpoUserId, String notes);

    @SignalMethod
    void onDpoRejected(String dpoUserId, String reason);

    @QueryMethod
    DsarStatus getStatus();

    @QueryMethod
    List<EligibilityIssue> getEligibilityReport();
}
```

### 4.3. Workflow implementation iskeleti

```java
public class DsarWorkflowImpl implements DsarWorkflow {

    private final ComplianceActivities compliance = ...;
    private final IdentityActivities identity = ...;
    private final AcademicActivities academic = ...;
    private final FinanceActivities finance = ...;
    private final FileActivities file = ...;
    private final NotificationActivities notification = ...;
    private final AuditActivities audit = ...;
    private final VaultActivities vault = ...;

    private DsarStatus status = DsarStatus.RECEIVED;
    private boolean identityVerified = false;
    private boolean dpoApproved = false;
    private boolean dpoRejected = false;
    private String dpoNotes;
    private List<EligibilityIssue> eligibilityReport = new ArrayList<>();

    @Override
    public DsarResult execute(DsarRequest req) {
        var sagaKey = Workflow.getInfo().getWorkflowId();

        // ── 1. Audit start ──
        audit.recordEvent(AuditEvent.dsarReceived(req));

        // ── 2. Identity verification ──
        status = DsarStatus.PENDING_VERIFICATION;
        compliance.sendVerificationEmail(req.subjectEmail(), req.requestId(), sagaKey);
        boolean verified = Workflow.await(Duration.ofDays(7), () -> identityVerified);
        if (!verified) {
            status = DsarStatus.EXPIRED_NO_VERIFICATION;
            audit.recordEvent(AuditEvent.dsarExpired(req));
            return DsarResult.expired();
        }

        // ── 3. Eligibility check ──
        status = DsarStatus.CHECKING_ELIGIBILITY;
        eligibilityReport = compliance.runEligibilityChecks(req.subjectId(), req.tenantId());

        if (req.type() == DsarType.ERASURE) {
            // ── 4. DPO approval ──
            status = DsarStatus.PENDING_DPO_APPROVAL;
            compliance.notifyDpoForApproval(req, eligibilityReport);
            boolean decided = Workflow.await(Duration.ofDays(14), () -> dpoApproved || dpoRejected);
            if (!decided) {
                status = DsarStatus.EXPIRED_NO_APPROVAL;
                return DsarResult.expired();
            }
            if (dpoRejected) {
                status = DsarStatus.REJECTED_BY_DPO;
                compliance.notifySubjectRejection(req.subjectEmail(), dpoNotes);
                audit.recordEvent(AuditEvent.dsarRejected(req, dpoNotes));
                return DsarResult.rejected(dpoNotes);
            }
        }

        // ── 5. Execution ──
        status = DsarStatus.EXECUTING;
        var saga = new Saga(new Saga.Options.Builder().build());

        try {
            if (req.type() == DsarType.ACCESS) {
                var export = executeAccess(req, sagaKey);
                return DsarResult.access(export.downloadUrl());
            } else if (req.type() == DsarType.ERASURE) {
                executeErasure(req, sagaKey, saga, eligibilityReport);
            } else if (req.type() == DsarType.PORTABILITY) {
                var export = executePortability(req, sagaKey);
                return DsarResult.portability(export.downloadUrl());
            }

            status = DsarStatus.COMPLETED;
            audit.recordEvent(AuditEvent.dsarCompleted(req));
            compliance.notifySubjectCompletion(req.subjectEmail(), req.requestId());

            return DsarResult.completed();

        } catch (ActivityFailure af) {
            saga.compensate();
            status = DsarStatus.FAILED;
            audit.recordEvent(AuditEvent.dsarFailed(req, af.getMessage()));
            return DsarResult.failed(af.getMessage());
        }
    }

    private DsarExport executeAccess(DsarRequest req, String sagaKey) {
        var inventory = compliance.collectDataInventory(req.subjectId(), req.tenantId());
        var bundle = new ArrayList<ServiceExport>();
        bundle.add(identity.exportSubjectData(req.subjectId(), sagaKey + "/identity-export"));
        bundle.add(academic.exportSubjectData(req.subjectId(), sagaKey + "/academic-export"));
        bundle.add(finance.exportSubjectData(req.subjectId(), sagaKey + "/finance-export"));
        bundle.add(file.exportSubjectFiles(req.subjectId(), sagaKey + "/file-export"));
        // diğer servisler
        var zipUrl = file.bundleAndUpload(bundle, sagaKey + "/bundle");
        return new DsarExport(zipUrl);
    }

    private void executeErasure(DsarRequest req, String sagaKey, Saga saga, List<EligibilityIssue> issues) {
        // Eligibility istisnası olmayan tüm servislerde anonymize
        // (Eligibility raporunda excluded:false olanlar)

        var subjectId = req.subjectId();

        identity.anonymize(subjectId, sagaKey + "/identity-anon");
        // anonymize compensation yok — geri alınamaz

        academic.anonymize(subjectId, sagaKey + "/academic-anon");
        finance.anonymizeIfAllowed(subjectId, issues, sagaKey + "/finance-anon");
        file.deleteSubjectFilesIfAllowed(subjectId, issues, sagaKey + "/file-del");

        // Notification subscriptions, push tokens
        notification.removeSubscriptions(subjectId, sagaKey + "/notif-remove");

        // Encryption key destroy — kullanıcıya bağlı DEK'leri Vault Transit'te destroy
        vault.destroySubjectKeys(subjectId, sagaKey + "/vault-destroy");
    }

    private DsarExport executePortability(DsarRequest req, String sagaKey) {
        var data = executeAccess(req, sagaKey);
        return data;   // sadece formatting farkı (machine-readable JSON)
    }

    // Signal/query implementations
    @Override public void onIdentityVerified() { identityVerified = true; }
    @Override public void onDpoApproved(String dpoUserId, String notes) {
        dpoApproved = true;
        dpoNotes = notes;
    }
    @Override public void onDpoRejected(String dpoUserId, String reason) {
        dpoRejected = true;
        dpoNotes = reason;
    }
    @Override public DsarStatus getStatus() { return status; }
    @Override public List<EligibilityIssue> getEligibilityReport() { return eligibilityReport; }
}
```

### 4.4. Servis-spesifik anonymization handler örnek (academic-service)

```java
@RestController
@RequestMapping("/api/internal/dsar")
public class AcademicDsarController {

    @PostMapping("/anonymize")
    @Idempotent     // custom annotation: idempotency key header zorunlu
    public AnonymizeResult anonymize(@RequestBody AnonymizeRequest req) {
        // 1. Direct PII fields anonymize
        studentRepo.findBySubjectId(req.subjectId()).forEach(s -> {
            s.setFirstName(anonymizedName());
            s.setLastName(null);
            s.setEmail(hashedEmail(s.getEmail()));
            s.setPhone(null);
            s.setNationalId(null);
            s.setBirthDate(s.getBirthDate().withDayOfMonth(1).withMonth(1));   // sadece yıl
            s.markAnonymized();
        });

        // 2. Linked records — yazar bilgisi anonimleştir
        attendanceRepo.findByStudentId(req.subjectId()).forEach(a -> {
            // Don't delete; replace identifier
            a.setRemark(scrubText(a.getRemark()));
        });

        // 3. Audit record (silinmez, ama subject_id pseudonymize)
        auditRepo.pseudonymizeSubject(req.subjectId());

        return AnonymizeResult.success(req.subjectId());
    }

    @PostMapping("/export")
    public ServiceExport exportData(@RequestBody ExportRequest req) {
        return new ServiceExport(
            "academic",
            Map.of(
                "students", studentRepo.findBySubjectId(req.subjectId()),
                "enrollments", enrollmentRepo.findByStudentId(req.subjectId()),
                "grades", gradeRepo.findByStudentId(req.subjectId())
            )
        );
    }

    @GetMapping("/preview")
    public AnonymizationPreview preview(@RequestParam UUID subjectId) {
        return new AnonymizationPreview(
            List.of("first_name", "last_name", "email", "phone", "national_id"),
            List.of("attendance.remark (PII scrub)")
        );
    }
}
```

### 4.5. Compliance-service'in audit event'leri

Tüm DSAR adımları audit-service'e push:
- `dsar.received`
- `dsar.verification.sent`
- `dsar.verification.completed`
- `dsar.eligibility.checked`
- `dsar.dpo.approval.requested`
- `dsar.dpo.approved` / `dsar.dpo.rejected`
- `dsar.execution.started`
- `dsar.execution.completed`
- `dsar.subject.notified`

audit-service Kafka topic'inden append-only audit log'a yazar.

### 4.6. UI status flow

End-user portal:
1. "DSAR Talebi Oluştur" formu → kaydedildi.
2. "E-posta'na verification linki gönderildi" → tıkla.
3. Linke tıklanınca: "Eligibility kontrol ediliyor..." → birkaç dakika.
4. Erasure: "DPO onayı bekleniyor (max 14 gün)" → durum güncellemesi.
5. Execution: "İşlem yapılıyor..."
6. "Tamamlandı: indirme linki" veya "Silindi" mesajı.

Internal Admin Panel:
- DPO için "Pending Approvals" listesi.
- Eligibility report görüntüleme.
- Approve/Reject + comment.

### 4.7. Deadline ve escalation

DSAR yasal süresi 30 gün. Workflow:
- Day 1-7: verification bekle.
- Day 8-9: eligibility check.
- Day 10-23: DPO approval (max 14 gün ama escalation 7. günde).
- Day 24-29: execution.
- Day 30: deadline; tamamlanmadıysa **alert + manuel müdahale**.

Workflow `Workflow.sleep(Duration.ofDays(25))` ile timer; başka thread (signal-based scheduler) escalation maili gönderir.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **Manuel ticket sistemi** | İz takip, idempotency yok, audit zayıf. |
| **Spring Batch** | Batch odaklı, manuel signal/timer zor. |
| **DB-driven state machine** | Replay yok, retry zayıf, debug zor. |
| **AWS Step Functions** | Bulut-kilit. |
| **Camunda BPMN** | Visual güzel, ama Lumix Temporal-first. |
| **Tek microservice (compliance-service) içine gömme** | Cross-service compensation zor; saga sınırı bulanık. |

### Kabul ettiğimiz trade-off'lar

- **Anonymization compensation yok**: geri alınamaz; risk olarak DPO onay zorunlu.
- **Workflow uzun süreli (30 gün)**: history büyür; archival policy 1 yıl sonrası.
- **Her servisin DSAR handler'ı yazmak zorunda**: zaman alır; ama compliance contract.

### Tekrar değerlendirme tetikleyicileri

- Çok yüksek hacim DSAR (binler) → batch + paralel optimization.
- Self-service erasure tam otomatik isteniyor mu (DPO onayı off) → karar riski; default DPO on.

## 6. Pratik örnek

### 6.1. DSAR talebi oluşturma endpoint

```java
@PostMapping("/api/v1/dsar/requests")
public ResponseEntity<?> createRequest(@RequestBody @Valid DsarRequestForm form) {
    var requestId = UUID.randomUUID();
    var subjectId = identityService.lookupByEmail(form.email())
        .orElseThrow(() -> new SubjectNotFoundException());

    var input = new DsarRequest(
        requestId,
        subjectId,
        form.email(),
        form.tenantId(),
        DsarType.valueOf(form.type()),
        Instant.now()
    );

    var options = WorkflowOptions.newBuilder()
        .setTaskQueue("compliance-task-queue")
        .setWorkflowId("dsar-" + requestId)
        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.REJECT_DUPLICATE)
        .setWorkflowExecutionTimeout(Duration.ofDays(45))
        .build();

    var stub = workflowClient.newWorkflowStub(DsarWorkflow.class, options);
    WorkflowClient.start(stub::execute, input);

    return ResponseEntity.accepted()
        .body(Map.of(
            "request_id", requestId,
            "status_url", "/api/v1/dsar/requests/" + requestId,
            "deadline", Instant.now().plus(Duration.ofDays(30))
        ));
}
```

### 6.2. Verification signal endpoint

```java
@GetMapping("/api/v1/dsar/verify/{token}")
public ResponseEntity<?> verify(@PathVariable String token) {
    var verificationToken = verificationService.consume(token);
    if (verificationToken == null) return ResponseEntity.status(410).build();

    var stub = workflowClient.newWorkflowStub(DsarWorkflow.class, "dsar-" + verificationToken.requestId());
    stub.onIdentityVerified();

    return ResponseEntity.ok(Map.of("status", "verified"));
}
```

### 6.3. DPO approval endpoint

```java
@PostMapping("/api/v1/dsar/admin/{requestId}/decision")
@PreAuthorize("hasRole('DPO')")
public ResponseEntity<?> dpoDecision(@PathVariable UUID requestId, @RequestBody DpoDecisionDto dto) {
    var stub = workflowClient.newWorkflowStub(DsarWorkflow.class, "dsar-" + requestId);
    if (dto.approved()) {
        stub.onDpoApproved(SecurityContext.userId(), dto.notes());
    } else {
        stub.onDpoRejected(SecurityContext.userId(), dto.reason());
    }
    return ResponseEntity.accepted().build();
}
```

### 6.4. Eligibility activity

```java
@Component
@ActivityImpl(taskQueues = "compliance-task-queue")
public class ComplianceActivitiesImpl implements ComplianceActivities {

    @Autowired private LegalHoldRepository legalHoldRepo;
    @Autowired private FinanceClient financeClient;

    @Override
    public List<EligibilityIssue> runEligibilityChecks(UUID subjectId, UUID tenantId) {
        var issues = new ArrayList<EligibilityIssue>();

        // Mali kayıt 5 yıl saklama (TR)
        var financialActivity = financeClient.hasRecentActivity(subjectId, Duration.ofDays(5L * 365L));
        if (financialActivity.exists()) {
            issues.add(new EligibilityIssue(
                "finance",
                "5_year_retention",
                "Vergi Usul Kanunu 5 yıl saklama yükümlülüğü",
                financialActivity.endsOn()
            ));
        }

        // Hukuki uyuşmazlık
        legalHoldRepo.findActiveBySubjectId(subjectId).forEach(lh ->
            issues.add(new EligibilityIssue("legal", "legal_hold", lh.reason(), lh.untilDate())));

        // Audit log (immutable)
        issues.add(new EligibilityIssue("audit", "immutable",
            "Audit log silinmez; subject_id pseudonymize edilir", null));

        return issues;
    }
}
```

### 6.5. Status sorgu UI'dan

```java
@GetMapping("/api/v1/dsar/requests/{requestId}")
public DsarStatusDto getStatus(@PathVariable UUID requestId) {
    var stub = workflowClient.newWorkflowStub(DsarWorkflow.class, "dsar-" + requestId);
    return new DsarStatusDto(
        stub.getStatus(),
        stub.getEligibilityReport()
    );
}
```

### 6.6. Compliance dashboard query (Grafana)

```sql
-- workflow_completed_total counter Prometheus → Grafana
sum(rate(dsar_workflow_completed_total{outcome="success"}[7d])) by (type)
sum(rate(dsar_workflow_completed_total{outcome="expired_no_verification"}[7d]))
histogram_quantile(0.95, rate(dsar_workflow_duration_days_bucket[30d]))
```

### 6.7. Test senaryosu (Temporal TestEnv)

```java
@Test
public void erasure_with_dpo_approval_anonymizes_all_services() {
    var env = TestWorkflowEnvironment.newInstance();
    var worker = env.newWorker("compliance-task-queue");
    worker.registerWorkflowImplementationTypes(DsarWorkflowImpl.class);

    var mockCompliance = mock(ComplianceActivities.class);
    when(mockCompliance.runEligibilityChecks(any(), any())).thenReturn(List.of());
    // ... diğer mock'lar

    worker.registerActivitiesImplementations(mockCompliance, /* ... */);
    env.start();

    var stub = env.getWorkflowClient().newWorkflowStub(
        DsarWorkflow.class,
        WorkflowOptions.newBuilder().setTaskQueue("compliance-task-queue").setWorkflowId("dsar-test").build());

    var future = WorkflowClient.execute(stub::execute, sampleErasureRequest());

    // Signal verification
    env.sleep(Duration.ofMinutes(5));
    stub.onIdentityVerified();

    env.sleep(Duration.ofMinutes(5));
    stub.onDpoApproved("dpo-user", "approved with note");

    var result = future.get();
    assertThat(result.outcome()).isEqualTo(DsarOutcome.COMPLETED);
    verify(mockAcademic).anonymize(any(), any());
    verify(mockVault).destroySubjectKeys(any(), any());
}
```

## 7. Dikkat edilecek tuzaklar

- **Anonymization idempotent değil**: ikinci çağrı already-anonymized → no-op (success). Crashed retry tolere edilir.
- **Eligibility istisnasını ignore etmek**: finansal kayıt anonimleşince denetimde sorun. Eligibility activity'yi atlamak yasak.
- **30 gün deadline'ı kaçırmak**: workflow timer + escalation; cron job ek alert.
- **Audit log'unda PII tutmak**: subject_id pseudonymize edilmeli; ad/email değil.
- **Identity verification'ı atlamak**: yanlış kişi başkasının verisini silebilir. Workflow shortcut yasak.
- **Compensation yok ama saga.compensate çağırmak**: erasure'da geri alınamaz; manuel queue.
- **Tek workflow ID re-use ile aynı subject için ikinci DSAR**: ALLOW yerine REJECT_DUPLICATE; ikinci başvuru farklı request_id ile.
- **Vault key destroy'u önce yapmak**: encrypted veri henüz export edilmediyse export başarısız → restore-after-destroy imkansız. Sıra: önce export/anonymize, sonra key destroy.
- **Notification subscription temizliği unutmak**: push token kaldığı için silmiş kullanıcıya hâlâ bildirim → ihlal.
- **Cross-tenant subject_id collision**: subject_id global olmayabilir. Tenant scope dahil.
- **Long-running workflow timeout**: 30 gün + safety margin = workflow execution timeout 45 gün.
- **Workflow history retention < 30 gün**: workflow tamamlanmadan history silinir → corrupt. Retention en az 90 gün.

## 8. Diğer konularla ilişkisi

- [Temporal Fundamentals](./01-temporal-fundamentals.md) — workflow temelleri
- [Saga with Temporal](./02-saga-with-temporal.md) — saga pattern (DSAR bir saga)
- [Background Jobs](./04-background-jobs.md) — retention enforcement (compliance otomasyonu)
- [Audit Log](../security-compliance) — DSAR event audit
- [Privacy / KVKK](../security-compliance) — yasal arka plan
- [Vault](../security-compliance) — DEK destroy mekanizması
- [Compliance Service](../security-compliance) — DSAR front-door servisi

## 9. Daha derine inmek için

- KVKK metni: [https://www.kvkk.gov.tr/](https://www.kvkk.gov.tr/)
- GDPR Article 15-21 (Right to Access, Erasure, etc.)
- "GDPR for Engineers" — Bart Jansen
- "Designing Data-Intensive Applications" — Martin Kleppmann (privacy bölümü)
- Search keyword'leri: *"gdpr erasure workflow"*, *"kvkk veri sahibi talep akışı"*, *"data anonymization vs pseudonymization"*, *"crypto-shredding key destroy"*

## 10. Sözlük

- **DSAR (Data Subject Access Request)**: Veri sahibinin haklarına ilişkin talebi.
- **Access**: "Verim ne?" exports.
- **Erasure**: "Sil/anonimleştir."
- **Portability**: "Yapılandırılmış formatta al."
- **Rectification**: "Düzelt."
- **Veri sahibi (Data Subject)**: Verilerine sahip kişi.
- **DPO (Data Protection Officer)**: Veri koruma yetkilisi.
- **Anonymization**: Geri dönüşsüz kişi-veri ilişkisini kesme.
- **Pseudonymization**: Geri dönüştürülebilir ama doğrudan tanımlamayan dönüşüm.
- **Crypto-shredding**: Veriyi şifreli bırakıp anahtarı imha ederek "okunamaz" hale getirme.
- **Eligibility check**: Yasal istisnalara karşı kontrol.
- **Legal hold**: Hukuki uyuşmazlık nedeniyle veriyi silmeme zorunluluğu.
- **Workflow timer**: Temporal'da durable sleep.
- **Idempotency key**: Aynı operasyonun tekrarında aynı sonucu garanti eden anahtar.
- **Audit log**: Kim, ne zaman, neyi yaptı — append-only kayıt.
- **Compliance dashboard**: DSAR metric ve durumu izleyen Grafana panel.
