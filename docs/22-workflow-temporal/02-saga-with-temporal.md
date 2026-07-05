---
title: Saga Pattern Temporal ile
description: Saga pattern Temporal ile — her activity bir step, compensation activity, OrderSaga örneği (academic + finance + notification servisler arası), retry policy.
sidebar_position: 2
---

## Bu sayfa ne anlatıyor?

Lumix microservice mimarisinde **"4 servisi de etkileyen tek atomic işlem"** (örnek: bir öğrencinin kayıt + sınıf atama + ödeme + bildirim) klasik DB transaction'la mümkün değil. **Saga pattern** + **Temporal** ile her adım için **compensation** (telafi) tanımlanır; bir adım fail olursa önceki adımlar geri alınır. Bu sayfa Saga'yı sıfırdan anlatır, **orchestration vs choreography** ayrımını gösterir, Temporal'in saga'yı niçin doğal taşıdığını açıklar ve gerçek bir Lumix saga örneği (OrderSaga: academic + finance + notification) ile detaylandırır. Hedef kitle: Temporal temellerini bilen ([Temporal Fundamentals](./temporal-fundamentals)), microservice arası iş akışı tasarlayan geliştirici.

## 1. Bu nedir? (Sıfırdan)

**Saga pattern**: Birden fazla servis veya kaynak üzerinden geçen iş akışında, klasik **ACID transaction** yerine **bir dizi adım + her adımın compensation'ı** kullanmak.

Örnek bir kayıt iş akışı:
1. `academic-service`: öğrenciyi sınıfa ekle.
2. `finance-service`: ödeme tahsil et.
3. `file-service`: kayıt belgesi PDF oluştur.
4. `notification-service`: veliye e-posta gönder.

Eğer adım 2 fail olursa adım 1 manuel geri alınmalı; adım 3 fail olursa adım 1 + 2 geri alınmalı vs.

Saga iki yaklaşımla uygulanır:

### Orchestration (Lumix tercihi)

Tek bir **orchestrator** (Temporal workflow) tüm adımları sırayla çağırır. Hata olursa compensation'ları sırayla tetikler.

```
[Orchestrator] → [Service A] → [Service B] → [Service C]
                 fail
   <───────── compensate A ←─── compensate B ←───
```

### Choreography

Servisler event'lerle birbirini tetikler; orchestrator yok.

```
[Service A publish] → [Service B reacts] → [Service C reacts]
```

Lumix kararı: **Orchestration**. Sebepler: net adım listesi, debug kolay, compensation logic merkezi. Choreography sadece basit, lineer akışlar için.

### Günlük hayattan analoji

Restoranda sipariş: garson (orchestrator) → mutfak (yemek hazırla) → barmen (içecek hazırla) → kasa (öde) → sunum. Yemek hazır ama içecek tükenmiş → garson yemeği iptal eder (compensation), sipariş baştan.

## 2. Hangi problemi çözüyor?

| Acı | Saga yok | Saga var |
|---|---|---|
| Multi-service tek akış | Manuel state machine + retry | Workflow + activity |
| Atomic değil ama tutarlı | Tutarsız state birikir | Compensation ile rollback |
| Hata sonrası yarım state | "Tamir" prosedürü manuel | Otomatik compensation |
| Audit | Manuel log | Workflow history her adımı kaydeder |
| Çoklu kullanıcı, paralel akış | Concurrency bug'ları | Workflow ID per business entity |

### Patlamış üretim hikayesi

Öğrenci kayıt akışı 4 servise yayılmıştı. Bir gün finance-service down idi. Academic-service kayıt yaptı, finance fail oldu. Manuel müdahale: 50 öğrenci için kayıt geri al + tekrar dene. Saga olsaydı: compensation otomatik, manuel yok.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Saga workflow yapısı

```java
@WorkflowMethod
public OrderResult execute(OrderInput input) {
    var completed = new ArrayList<CompensationAction>();
    try {
        var stepA = serviceA.execute(input);
        completed.add(() -> serviceA.compensate(stepA));

        var stepB = serviceB.execute(input, stepA);
        completed.add(() -> serviceB.compensate(stepB));

        var stepC = serviceC.execute(input, stepA, stepB);
        completed.add(() -> serviceC.compensate(stepC));

        return OrderResult.success();
    } catch (Exception e) {
        // Compensation reverse order
        Collections.reverse(completed);
        for (var action : completed) {
            try { action.execute(); }
            catch (Exception ce) { /* log + manual queue */ }
        }
        return OrderResult.failed(e.getMessage());
    }
}
```

Temporal Java SDK `Saga` helper class'ı vardır:

```java
Saga saga = new Saga(new Saga.Options.Builder().build());
try {
    var a = activityA.execute(input);
    saga.addCompensation(activityA::compensate, a);

    var b = activityB.execute(input, a);
    saga.addCompensation(activityB::compensate, b);

    var c = activityC.execute(input, a, b);
    saga.addCompensation(activityC::compensate, c);

    return OrderResult.success();
} catch (Exception e) {
    saga.compensate();
    return OrderResult.failed(e.getMessage());
}
```

### 3.2. Compensation tasarımı

Compensation **idempotent + null-safe** olmalı:
- "Eğer adım gerçekleşti ise geri al; gerçekleşmediyse no-op."
- Activity retry sırasında compensation iki kere çağrılabilir; aynı sonucu vermeli.

Örnek:
```
execute: charge user → returns paymentId
compensate(paymentId): refund(paymentId)   # paymentId yoksa no-op
```

### 3.3. Compensation ordering

Yukarıdaki Saga helper: **reverse order**. A, B, C yapıldıysa fail durumunda C → B → A compensation.

### 3.4. Pivot transaction

Bazı saga'larda **pivot** noktası vardır: bu noktadan sonra geri dönüş yok (örn. dış sisteme final commit). Pivot öncesi compensation OK; pivot sonrası fail olursa **forward recovery** (retry + manuel müdahale).

### 3.5. Retry, deadline ve Saga karışımı

Activity retry: tekil adım fail için. Saga'da bütün workflow retry değil — adımlardan biri retry sonrası kalıcı fail olursa saga compensation tetiklenir.

### 3.6. Status query

Saga workflow `@QueryMethod String getStatus()` ile mevcut adımı dışarı verir:
```
"executing_step_a"
"executing_step_b"
"compensating_step_b"
"completed"
"failed_compensated"
```

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Kullanım alanları

Lumix'te saga ihtiyacı olan akışlar:

| Akış | Servisler |
|---|---|
| **Öğrenci kayıt** | academic + finance + file + notification |
| **Ödeme** | finance + audit + notification (refund saga) |
| **DSAR (anonymization)** | her servisin anonymization handler'ı + audit + compliance |
| **Tenant create** | identity + organization + (per-service tenant init) |
| **Bulk export** | file + permission check + audit |

### 4.2. Workflow ID standardı

`<saga-type>-<business-entity-id>` formatı, idempotency için:
```
student-registration-{studentId}
order-{orderId}
dsar-{requestId}
tenant-create-{tenantId}
```

`WorkflowIdReusePolicy: REJECT_DUPLICATE` → aynı işlemi iki kez başlatma engellenir.

### 4.3. Task queue

Saga workflow'lar genellikle başlatan servisin task queue'sunda yaşar:
- `student-registration-saga` → `academic-task-queue`
- `payment-saga` → `finance-task-queue`

Activity'ler kendi servislerinin task queue'sundadır:
- `chargePayment` → `finance-task-queue`
- `generateCertificatePdf` → `file-task-queue`
- `sendNotificationEmail` → `notification-task-queue`

Worker pod'lar farklı task queue'larda dinler.

### 4.4. Activity'lerin idempotency garantisi

Her activity input'ta `Idempotency-Key` taşır (workflow run ID + activity step ID hash):
```java
var stepKey = Workflow.getInfo().getWorkflowId() + "/charge";
var payment = financeActivity.chargePayment(input, stepKey);
```

Activity implementation:
```java
public Payment chargePayment(ChargeInput input, String idempotencyKey) {
    var existing = paymentRepo.findByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) return existing.get();   // duplicate retry → return original
    // ... charge logic
}
```

### 4.5. Compensation idempotency

```java
public void refundPayment(String paymentId) {
    var p = paymentRepo.findById(paymentId).orElse(null);
    if (p == null) return;                          // null safe
    if (p.status() == REFUNDED) return;              // already done
    if (p.status() != AUTHORIZED && p.status() != CAPTURED) return;   // can't refund
    // refund logic
}
```

### 4.6. Hata kategorileri

- **Retryable error** (network glitch, 5xx, timeout): activity retry; saga continue.
- **Business rule error** (out of stock, insufficient funds): activity `@DoNotRetry`; saga compensate.
- **Workflow logic error** (bug): NonDeterministic; investigate + version bump.

### 4.7. Audit integration

Saga workflow her adım için audit event publish eder:
```
saga.started
saga.step.completed{step=A, latency_ms=..., correlation_id=...}
saga.step.failed{step=B, reason=...}
saga.compensation.started
saga.compensation.step.completed{step=A}
saga.completed{outcome=success|compensated|partial}
```

audit-service Kafka topic'inden tüketir.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **2PC (Two-Phase Commit)** | Modern microservice'lerde imkansız; tüm DB'leri kilit alır. |
| **Choreography saga** (event-driven) | Net flow yok; debug zor; cycle riski. |
| **Eventuate Tram / Axon Saga** | Olgun framework'ler ama Temporal generic + Lumix Temporal'i zaten kullanıyor. |
| **Manuel saga (DB state)** | Replay yok, retry zayıf, audit dağınık. |
| **Camunda BPMN** | Visual; ama Java code-first tercih edildi. |

### Kabul ettiğimiz trade-off'lar

- **Eventual consistency**: saga sırasında sistem geçici tutarsız (örn. öğrenci kayıtlı ama ödeme yok). UI bunu bekler ("processing").
- **Compensation tasarımı zor**: bazı şeyler geri alınamaz (e-posta gönderildi). Bu durumlarda "tersine notification" (rollback e-posta).
- **Workflow versioning sürekli ihtiyaç**: yeni adım eklemek = `getVersion`.

### Tekrar değerlendirme tetikleyicileri

- Çok basit lineer akışlar artarsa → event choreography (lightweight).
- BPMN visual gereksinim doğarsa → Camunda.

## 6. Pratik örnek

### 6.1. Student Registration Saga

```java
@WorkflowInterface
public interface StudentRegistrationSaga {
    @WorkflowMethod
    RegistrationResult execute(RegistrationInput input);
    @QueryMethod
    SagaStatus getStatus();
}

@Component
public class StudentRegistrationSagaImpl implements StudentRegistrationSaga {

    private final ActivityOptions defaultOpts = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(30))
        .setRetryOptions(RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(1))
            .setMaximumInterval(Duration.ofMinutes(2))
            .setBackoffCoefficient(2.0)
            .setMaximumAttempts(5)
            .setDoNotRetry(
                BusinessRuleException.class.getName(),
                ValidationException.class.getName())
            .build())
        .build();

    private final AcademicActivities academic = Workflow.newActivityStub(
        AcademicActivities.class,
        defaultOpts.toBuilder().setTaskQueue("academic-task-queue").build());
    private final FinanceActivities finance = Workflow.newActivityStub(
        FinanceActivities.class,
        defaultOpts.toBuilder().setTaskQueue("finance-task-queue").build());
    private final FileActivities file = Workflow.newActivityStub(
        FileActivities.class,
        defaultOpts.toBuilder().setTaskQueue("file-task-queue").build());
    private final NotificationActivities notification = Workflow.newActivityStub(
        NotificationActivities.class,
        defaultOpts.toBuilder().setTaskQueue("notification-task-queue").build());

    private SagaStatus status = SagaStatus.PENDING;

    @Override
    public RegistrationResult execute(RegistrationInput input) {
        var saga = new Saga(new Saga.Options.Builder().setParallelCompensation(false).build());
        var ctx = Workflow.getInfo();
        var sagaKey = ctx.getWorkflowId();

        try {
            status = SagaStatus.ENROLLING_STUDENT;
            var enrollment = academic.enrollStudent(input.studentInfo(), input.classId(), sagaKey + "/enroll");
            saga.addCompensation(academic::cancelEnrollment, enrollment.enrollmentId());

            status = SagaStatus.CHARGING_PAYMENT;
            var payment = finance.charge(input.studentInfo().id(), input.tuitionAmount(), sagaKey + "/charge");
            saga.addCompensation(finance::refundPayment, payment.paymentId());

            status = SagaStatus.GENERATING_CERTIFICATE;
            var pdfUrl = file.generateRegistrationPdf(enrollment.enrollmentId(), sagaKey + "/pdf");
            saga.addCompensation(file::deleteRegistrationPdf, pdfUrl);

            status = SagaStatus.NOTIFYING_PARENT;
            notification.sendRegistrationEmail(input.studentInfo().parentEmail(), enrollment.enrollmentId(), pdfUrl);
            // notification — best effort; compensation = "registration cancelled" email
            saga.addCompensation(notification::sendCancellationEmail,
                input.studentInfo().parentEmail(), enrollment.enrollmentId());

            status = SagaStatus.COMPLETED;
            return RegistrationResult.success(enrollment.enrollmentId(), payment.paymentId(), pdfUrl);

        } catch (ActivityFailure af) {
            status = SagaStatus.COMPENSATING;
            saga.compensate();
            status = SagaStatus.FAILED_COMPENSATED;
            return RegistrationResult.failed("step failed: " + af.getCause().getMessage());
        }
    }

    @Override
    public SagaStatus getStatus() {
        return status;
    }
}
```

### 6.2. Finance activity (idempotent charge + refund)

```java
@Component
@ActivityImpl(taskQueues = "finance-task-queue")
public class FinanceActivitiesImpl implements FinanceActivities {

    @Autowired private PaymentService paymentService;

    @Override
    public Payment charge(UUID userId, BigDecimal amount, String idempotencyKey) {
        var existing = paymentService.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();   // duplicate replay-safe
        }
        return paymentService.chargeUser(userId, amount, idempotencyKey);
    }

    @Override
    public void refundPayment(UUID paymentId) {
        paymentService.refundIfRefundable(paymentId);   // null+state safe
    }
}
```

### 6.3. Saga başlatma REST controller

```java
@RestController
@RequestMapping("/api/v1/students")
public class StudentRegistrationController {

    @Autowired private WorkflowClient workflowClient;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody @Valid RegistrationRequest req) {
        var workflowId = "student-registration-" + req.studentInfo().id();
        var options = WorkflowOptions.newBuilder()
            .setTaskQueue("academic-task-queue")
            .setWorkflowId(workflowId)
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.REJECT_DUPLICATE)
            .setWorkflowExecutionTimeout(Duration.ofMinutes(10))
            .build();

        var stub = workflowClient.newWorkflowStub(StudentRegistrationSaga.class, options);
        WorkflowClient.start(stub::execute, req.toInput());

        return ResponseEntity.accepted()
            .header("Location", "/api/v1/students/registrations/" + workflowId)
            .body(Map.of("workflow_id", workflowId, "status", "started"));
    }

    @GetMapping("/registrations/{wfId}/status")
    public ResponseEntity<SagaStatus> status(@PathVariable String wfId) {
        var stub = workflowClient.newWorkflowStub(StudentRegistrationSaga.class, wfId);
        return ResponseEntity.ok(stub.getStatus());
    }
}
```

### 6.4. Test (Temporal test framework)

```java
@Test
public void test_payment_fail_triggers_compensation() {
    var testEnv = TestWorkflowEnvironment.newInstance();
    var worker = testEnv.newWorker("academic-task-queue");
    worker.registerWorkflowImplementationTypes(StudentRegistrationSagaImpl.class);

    // Mock activities
    var mockAcademic = mock(AcademicActivities.class);
    var mockFinance = mock(FinanceActivities.class);
    when(mockAcademic.enrollStudent(any(), any(), any()))
        .thenReturn(new Enrollment(UUID.randomUUID()));
    when(mockFinance.charge(any(), any(), any()))
        .thenThrow(ApplicationFailure.newFailure("insufficient funds", "BusinessRuleException"));

    worker.registerActivitiesImplementations(mockAcademic, mockFinance, /* others */);
    testEnv.start();

    var stub = testEnv.getWorkflowClient()
        .newWorkflowStub(StudentRegistrationSaga.class,
            WorkflowOptions.newBuilder().setTaskQueue("academic-task-queue").build());

    var result = stub.execute(sampleInput());
    assertThat(result.success()).isFalse();
    verify(mockAcademic).cancelEnrollment(any());   // compensation tetiklendi
}
```

### 6.5. Workflow ID strategy ile concurrency

İki kullanıcı aynı student için register başlatırsa:
- Workflow ID: `student-registration-{studentId}`
- `REJECT_DUPLICATE` → ikinci request 409 Conflict.

API'da:
```java
catch (WorkflowExecutionAlreadyStarted e) {
    return ResponseEntity.status(409).body(Map.of("error", "Registration already in progress"));
}
```

### 6.6. Bulunan saga durumlarına göre UI behavior

Frontend polls `/registrations/{wfId}/status`:
- `PENDING / ENROLLING / CHARGING / ...`: spinner + "İşleniyor..."
- `COMPLETED`: success page + sertifika link.
- `FAILED_COMPENSATED`: "İşlem tamamlanamadı. Hata: ödeme reddedildi. Sistem tutarlı; tekrar deneyebilirsiniz."
- `COMPENSATION_FAILED`: "Lütfen destek ile iletişime geçin" + audit trail.

### 6.7. Compensation fail durumu

Compensation activity da fail edebilir (örn. refund API down). Lumix kuralı:
- Compensation activity retry policy: aggressive (10 attempt, max 1h interval).
- Hâlâ fail ederse: workflow `COMPENSATION_FAILED` status; alert + manuel queue (`incident-tracker`).
- DB'de "stuck saga" kaydı; ekip akşam batch'inde inceler.

## 7. Dikkat edilecek tuzaklar

- **Compensation idempotent değil**: refund iki kez = iki refund. Her compensation null + state safe yazılmalı.
- **Compensation'ın pivot sonrası tetiklenmesi**: e-posta gönderildi, "geri alınamaz". Compensation farklı strategy: "cancellation email" + audit.
- **Workflow ID re-use yanlış**: `ALLOW_DUPLICATE` ile aynı student için iki saga başlatma → çakışma. `REJECT_DUPLICATE`.
- **Activity timeout çok kısa**: yavaş downstream saga'yı fail eder. Realistic timeout.
- **Activity'lerin `@DoNotRetry` listesinde business hata yok**: retry sonrası kalıcı fail bekleniyor; tetik compensation. Business exception explicit.
- **Saga workflow içine business logic gömmek**: workflow sadece koordinasyon; business logic activity'lerde.
- **Compensation order yanlış**: paralel compensation bazen anlam taşır (independent steps); ama default sequential reverse.
- **Workflow timeout yok**: hung saga sonsuza kadar kalır. `WorkflowExecutionTimeout` zorunlu.
- **Test'siz saga**: complex flow; her dal mock'la test. Temporal `TestWorkflowEnvironment`.
- **Versioning olmadan saga değişimi**: production'da çalışan saga'lar nondeterministic patlar. `getVersion`.
- **Activity option'ları cross-task-queue kullanmak**: ActivityStub task queue tek; her servisin activity'si ayrı stub.

## 8. Diğer konularla ilişkisi

- [Temporal Fundamentals](./temporal-fundamentals) — temel kavramlar
- [DSAR Workflow Implementation](./dsar-workflow-implementation) — özelleşmiş bir saga
- [Background Jobs](./background-jobs) — scheduled saga (örn. periyodik reconciliation)
- [Event-Driven Architecture](../event-driven-architecture) — Kafka + Saga karşılaştırma
- [Payment](../15-payment) — ödeme saga'sı
- [Audit Log](../security-compliance) — saga event'leri audit'e
- [Idempotency](../03-backend) — activity idempotency garantisi

## 9. Daha derine inmek için

- "Microservices Patterns" — Chris Richardson (Saga bölümü klasik)
- "Designing Data-Intensive Applications" — Martin Kleppmann
- Temporal Saga Pattern documentation
- "Distributed Systems for Fun and Profit" — Mikito Takada
- Search keyword'leri: *"saga orchestration vs choreography"*, *"temporal saga compensation"*, *"distributed transaction microservices"*, *"idempotent compensation pattern"*

## 10. Sözlük

- **Saga**: Multi-step iş akışı; her adım için compensation tanımlı.
- **Orchestration**: Bir merkezi koordinator (workflow) adımları çağırır.
- **Choreography**: Servisler event'lerle birbirini tetikler; merkez yok.
- **Compensation**: Bir adımın iş etkisini geri alan operasyon.
- **Pivot transaction**: Sonrasında geri dönüş olmayan kritik nokta.
- **Idempotency key**: Aynı operasyonu tekrar etse de aynı sonuç döndürecek anahtar.
- **`Saga` helper (Temporal Java SDK)**: Compensation listesi tutan yardımcı sınıf.
- **`addCompensation`**: Saga'ya yeni telafi adımı ekleme.
- **`saga.compensate()`**: Reverse order tüm compensation'ları çalıştırma.
- **`@DoNotRetry`**: Retry policy'ye eklenen — bu exception sınıfı retry edilmez.
- **`ActivityFailure`**: Activity exception'ı workflow'a fırlatılan formu.
- **Forward recovery**: Pivot sonrası fail için "manuel düzelt + ileri" stratejisi.
- **`WorkflowIdReusePolicy`**: Aynı workflow ID için yeni başlatma davranışı.
- **Eventual consistency**: Sistemin geçici tutarsızlığı sonunda tutarlı hale gelir.
