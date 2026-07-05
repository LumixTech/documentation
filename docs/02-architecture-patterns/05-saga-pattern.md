---
title: Saga Pattern
description: Distributed transaction problemi, saga prensibi, compensation event, choreography vs orchestration ve Lumix'te Temporal ile saga implementasyonu.
sidebar_position: 5
---

## Bu sayfa ne anlatıyor?

Bu sayfa **distributed transaction** probleminin neden çözümsüz olduğunu, **Saga pattern**'in nasıl bu duvarı aştığını, **compensation** kavramını ve Lumix'te **Temporal.io** ile saga'ların nasıl implement edildiğini gösteriyor. Sonunda okuyan biri "ödeme süreci niye saga, neden Temporal, hata olursa ne geri alınır" sorularını cevaplayabilmeli. Bonus olarak gerçek bir `EnrollmentSaga` örneği baştan sona kodla gösteriliyor.

## 1. Bu nedir? (Sıfırdan)

### Distributed transaction problemi

Monolith dünyada **ACID transaction** vardır: bir transaction içinde ne kadar tablo değiştirirsen değiştir, ya hepsi commit olur ya hepsi rollback. PostgreSQL'in `BEGIN ... COMMIT` blokları bunu garanti eder.

Microservice dünyada **bu sihir yoktur**. Çünkü:
- Her servisin kendi DB'si var
- Servisler arası transaction = "two-phase commit (2PC)" gerektirir
- 2PC operasyonel kabus (network partition'da blocking, deadlock, performans kaybı)
- Kafka, REST, gRPC 2PC desteklemez

Yani: "Öğrenci kaydı için **finance**'ta fatura kes + **academic**'te enrollment yap + **notification**'da hoş geldin maili gönder" tek transaction içinde yapılamaz.

Eğer kabaca yazarsak:

```java
// YANLIŞ — distributed transaction yok!
@Transactional
public void enrollStudent(...) {
    financeClient.createInvoice(...);     // OK
    academicClient.confirmEnrollment(...); // OK
    notificationClient.sendWelcome(...);   // 💥 FAIL
    // İlk iki çağrı zaten gerçekleşti, geri alamazsın!
}
```

Spring'in `@Transactional`'ı sadece **kendi DB**'ne ait. Cross-service rollback yok.

### Saga = uzun süreli iş süreci

**Saga pattern**, 1987'de Garcia-Molina ve Salem'in önerdiği bir tekniktir (orijinali long-lived transaction problemini çözmek içindi). Modern microservice'lerde **distributed transaction'ın yerini alır**.

Saga'nın temel fikri:
- Tüm akışı tek bir transaction yapma
- Akışı **ayrı ayrı, küçük, lokal transaction'lara** böl
- Her adım kendi DB'sinde commit olsun
- Hata olduğunda **compensating action** (telafi edici işlem) ile önceki adımları geri al

**Günlük hayattan analoji:**

Tatil planı düşün:
1. Uçak bileti al
2. Otel rezervasyon yap
3. Araç kirala

Saga olarak yapıyorsan:
- Uçak bileti aldın ✓
- Otel rezervasyon yaptın ✓
- Araç kiralama başarısız oldu ✗
- Compensation: oteli iptal et, uçak biletini iade et

Saga "iptal et" diyebilen bir akıştır. Tek transaction olarak değil, **adım adım yapılır ve gerekirse geri sarılır**.

### Compensation = telafi etmek, geri almak değil

Önemli ayrım:
- **Rollback** — sanki hiç olmamış gibi. Veritabanı işlemleri için geçerli.
- **Compensation** — olmuş şeyin etkisini telafi etmek. Geri alamayız, ama dengeleriz.

Örnek:
- "Veliye SMS gönderildi" — bunu rollback edemezsin (SMS gitti)
- Ama compensation yapabilirsin: "Beyefendi az önceki SMS yanlıştı, dikkate almayın."

Saga compensation **business level**'da düşünülmek zorunda. "Hata oldu ne yapalım?" sorusunun cevabı domain'e bağlı.

## 2. Hangi problemi çözüyor?

Saga olmadan, distributed işlerin başına bunlar gelir:

**Acı 1 — Tutarsız veri.**
"Ödeme tahsil edildi ama enrollment yapılamadı" — para alınmış ama hizmet verilmemiş. Müşteri arar, finans tutmaz, audit kabusu.

**Acı 2 — Manuel müdahale.**
Hata olduğunda DevOps insanlar **elle** veriyi düzeltir. 100 müşteri için 100 manuel müdahale. Saçma + hata kaynağı.

**Acı 3 — Görünürlük yok.**
"Bu kullanıcının enrollment'ı nerede takıldı?" Tek log dosyası bile yok. 5 servisin log'larına bak, tahmin yürüt.

**Acı 4 — Retry kabusu.**
Hata oldu, "tekrar deneyelim". Ama nereye kadar geri sarılıyoruz? Hangi adımı tekrar yapıyoruz? Idempotent mi? Hayır → duplicate işlem.

**Acı 5 — Workflow değişimleri yazılım değişimi gerektirir.**
"Önce KYC, sonra ödeme, sonra enrollment" akışı 6 ay sonra "Önce ödeme, paralelde KYC" olur. Kod baştan yazılır, test edilir, deploy edilir.

**Acı 6 — Long-running iş süreçleri.**
"3 gün ödeme onayı beklenecek, sonra şu adım atılacak." Naive implementation: thread tutar, JVM crash'inde state kaybolur. Saga yoksa long-running iş = imkansızlık.

Saga bu acıları şöyle çözer:

| Acı | Saga çözümü |
|---|---|
| Tutarsız veri | Compensation ile dengelenir (eventual consistency) |
| Manuel müdahale | Otomatik compensation flow |
| Görünürlük yok | Saga state machine — adım adım gözlemlenebilir |
| Retry kabusu | Saga retry'ı idempotent adımlarla yönetir |
| Workflow değişimi = kod değişimi | Temporal'da workflow ayrı, business code da ayrı evrim geçirir |
| Long-running iş | Saga durable storage'da, restart-safe |

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Saga'nın anatomisi

Saga = sıralı/paralel adımlar + her adım için compensation.

```
Adım 1 (Forward)        Compensation 1
Adım 2 (Forward)        Compensation 2
Adım 3 (Forward)        Compensation 3
...
Adım N (Forward)        Compensation N
```

Forward yönde başarılı gidersen finish. Bir adımda hata olursa: ters sırada compensation çalıştır.

```
Forward:   1 → 2 → 3 → 4 (success)
Failure:   1 → 2 → 3 → 4 ✗ → C3 → C2 → C1 (compensation)
```

### 3.2. İki saga koordinasyon stili

**Choreography saga:**
Merkezi koordinatör yok. Her servis kendi adımını yapar, başarı/başarısızlığı event olarak yayınlar. Diğer servisler bu event'lere bakıp kendi adımlarını yapar veya compensation tetikler.

```
finance ──InvoiceCreated──► payment ──PaymentCaptured──► academic ──EnrollmentConfirmed──►

Hata akışı:
academic ──EnrollmentFailed──► payment ──PaymentRefunded──► finance ──InvoiceVoided──►
```

Avantaj: loose coupling, basit kurulum.
Dezavantaj: tüm akışı tek noktadan görmek zor, debug zor, complex saga = dağınık.

**Orchestration saga:**
Merkezi bir **orchestrator** tüm adımları yönetir. Her servise command gönderir, cevabı bekler, hata olunca compensation tetikler.

```
[Orchestrator: EnrollmentSaga]
   │
   ├──► finance.CreateInvoice    ←── InvoiceCreated
   ├──► payment.Capture          ←── PaymentCaptured
   └──► academic.ConfirmEnroll   ←── EnrollmentConfirmed (DONE)
```

Avantaj: tek yerde state machine, görselleştirilebilir, debug kolay, complex saga için elverişli.
Dezavantaj: orchestrator tek nokta sıkıntısı, dikkatli scaling lazım.

**Lumix tercihi:**
- **Basit fan-out** (yoklama → notification, audit, search): choreography
- **Karmaşık çok adımlı iş süreçleri** (saga): **orchestration** via **Temporal.io**

### 3.3. Saga != ACID

Saga **A**tomicity vermez, **C**onsistency'yi eventual yapar. "Sanki tek transaction'mış gibi" yanılgısı tehlikelidir.

| ACID | Saga |
|---|---|
| Atomicity (hepsi ya hiç) | Yarı tamamlanmış state'ler görünebilir |
| Consistency (her zaman doğru) | Eventual consistency |
| Isolation (paralel görmez) | Saga adımı arasında dış görünür |
| Durability (commit kalıcı) | Adımlar kalıcı, ama saga state ayrı durable storage |

Bunun anlamı: saga sırasında **bir kullanıcı yarı state'i görebilir** ("Fatura kesilmiş ama enrollment yok"). Domain bunu tolere etmeli. UI'da `pending` state göstermek yeterli olabilir.

### 3.4. Temporal — durable workflow engine

**Temporal.io** açık kaynak (Uber'in Cadence projesinden fork) workflow orchestration platformudur.

Temporal'ın çözdüğü problem: "Bir workflow uzun sürüyor (saniyeler değil, dakikalar/saatler/günler). Worker crash'inden, network glitch'inden, scaling'den etkilenmesin. Tam olarak nerede kaldıysa devam etsin."

Mekanizma:
1. Workflow kodu **deterministic** Java metodu olarak yazılır
2. Her adım bir **activity** (mock'lanabilir, retry'lanabilir)
3. Temporal sunucusu workflow'un her adımını **event history**'de saklar
4. Worker crash'inde → başka worker history'yi replay eder → tam olarak kaldığı yerden devam eder

```java
@WorkflowInterface
public interface EnrollmentSagaWorkflow {
    @WorkflowMethod
    EnrollmentResult enroll(EnrollmentRequest request);
}

public class EnrollmentSagaWorkflowImpl implements EnrollmentSagaWorkflow {

    private final FinanceActivity finance = Workflow.newActivityStub(FinanceActivity.class);
    private final PaymentActivity payment = Workflow.newActivityStub(PaymentActivity.class);
    private final AcademicActivity academic = Workflow.newActivityStub(AcademicActivity.class);

    @Override
    public EnrollmentResult enroll(EnrollmentRequest req) {
        Saga saga = new Saga(...);
        try {
            InvoiceId invoiceId = finance.createInvoice(req);
            saga.addCompensation(() -> finance.voidInvoice(invoiceId));

            PaymentId paymentId = payment.capture(invoiceId, req.amount());
            saga.addCompensation(() -> payment.refund(paymentId));

            academic.confirmEnrollment(req.studentId(), req.classId());

            return EnrollmentResult.success(invoiceId, paymentId);
        } catch (Exception e) {
            saga.compensate();  // ters sırada
            throw e;
        }
    }
}
```

Temporal'ın sihri: yukarıdaki kod gibi görünür ama her satır arkada bir event history'ye yazılır. Worker'ı kapatıp tekrar açsan, kaldığı yerden devam eder.

### 3.5. Saga state machine

Her saga'nın bir state machine'i vardır:

```
        ┌────────┐
        │ STARTED │
        └────┬────┘
             ▼
   ┌──────────────────┐
   │ INVOICE_CREATED  │
   └────────┬─────────┘
            ▼
   ┌─────────────────────┐
   │ PAYMENT_CAPTURED    │
   └──────┬───────┬──────┘
          │       │
       success  failure
          ▼       ▼
   ┌──────────┐ ┌──────────────────────┐
   │COMPLETED │ │ COMPENSATING_PAYMENT │
   └──────────┘ └──────────┬───────────┘
                           ▼
                ┌──────────────────────┐
                │ COMPENSATING_INVOICE │
                └──────────┬───────────┘
                           ▼
                   ┌────────────┐
                   │ COMPENSATED│
                   └────────────┘
```

Temporal bu state'i otomatik takip eder. Manuel state machine yazmana gerek yok.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Lumix'te saga kullanım alanları

| Saga adı | Adımlar |
|---|---|
| **EnrollmentSaga** | invoice oluştur → ödeme tahsil et → enrollment onayla → hoş geldin maili |
| **PaymentSaga** | authorize → 3DS doğrula → capture → makbuz oluştur |
| **DSARSaga** | onay al → 10 servise sil command'ı gönder → onay topla → kullanıcıya bildir |
| **AnonymizationSaga** | tenant özel kategori verisi → DEK destroy → kayıt anonimleştir → audit |
| **CustomerOnboardingSaga** | Vault seed → Keycloak realm → DB tenant init → Kafka topic create → default rol seed → admin user create |

### 4.2. Workflow engine seçimi

**Karşılaştırma:**

| Seçenek | Karar |
|---|---|
| Custom Java + Spring Batch | Reinventing the wheel, durability sorunlu |
| Quartz Scheduler | Basit cron için OK, complex workflow için yetersiz |
| Camunda BPMN | Güçlü ama heavy, license + BPMN curve |
| Apache Airflow | Data pipeline odaklı, transactional iş için değil |
| **Temporal.io** | **Seçildi** — durable, code-first, retry/compensation native |
| Netflix Conductor | Iyi alternatif ama daha az olgun |

Lumix kararı: **Temporal**. Sebep:
- Java SDK olgun
- Code-first (Java/Go workflow definition)
- Durable execution (kayıp olmaz)
- Built-in retry, scheduling, signal, query
- Open source + self-host

### 4.3. Saga - servis sorumluluk dağılımı

```
[Temporal Worker (workflow process)]
    │
    │ activity stub'larıyla
    ▼
finance-service ─── createInvoice / voidInvoice
payment-service ─── capture / refund
academic-service ── confirmEnrollment / revertEnrollment
notification-svc ── sendWelcomeEmail
```

Her servis **activity**'leri gRPC endpoint olarak expose eder (idempotent). Temporal worker bu endpoint'leri çağırır.

### 4.4. Saga idempotency

Her saga adımı **idempotent** olmak zorunda — Temporal aynı activity'yi retry edebilir.

Idempotency anahtarı genelde saga ID + step ID:

```java
public InvoiceId createInvoice(CreateInvoiceCommand cmd) {
    String idempotencyKey = cmd.sagaId() + ":invoice";
    Optional<Invoice> existing = invoiceRepo.findByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) {
        return existing.get().id();
    }
    Invoice newInvoice = Invoice.create(..., idempotencyKey);
    invoiceRepo.save(newInvoice);
    return newInvoice.id();
}
```

### 4.5. Saga observability

Temporal Web UI ile her saga görünür:
- Hangi adımda
- Ne kadar süredir bekliyor
- Hangi exception fırlattı
- Hangi compensation tetiklendi

Lumix ek olarak:
- Her saga başlangıç/bitiş event'i Kafka'ya yayınlar (audit için)
- Saga metric'leri Prometheus'a (duration, success rate, compensation rate)
- Saga log'ları correlation-id ile diğer servis log'larıyla bağlanır

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. Düşünülen alternatifler

**Alternatif 1 — Two-Phase Commit (2PC)**
XA transaction'larla cross-service ACID.

Niye elendi:
- Kafka, gRPC, HTTP 2PC desteklemez
- Operasyonel kabus (blocking, deadlock)
- Cloud-native dünyada anti-pattern

**Alternatif 2 — Manuel compensation kodu**
Saga library kullanmadan, her use case kendi compensation'ını manuel yönet.

Niye elendi:
- Kod tekrarı patlar
- Long-running workflow için durable storage gerekir, yine implement edilmeli
- Hata yapma fırsatı yüksek

**Alternatif 3 — Choreography only**
Tüm saga'lar Kafka event'leri ile koordine.

Niye kısmen elendi:
- Basit fan-out için choreography iyi, ama complex saga için akış kaybolur
- Compensation event'leri yönetmek dağınık
- Debug imkansız

Lumix: **basit = choreography, complex = orchestration (Temporal)**.

**Alternatif 4 — Camunda BPMN**
İş süreçleri için olgun.

Niye elendi:
- BPMN diagram dependency
- Java SDK Temporal kadar code-first değil
- Lisans / community edition vs enterprise endişe

### 5.2. Kabul edilen trade-off'lar

| Trade-off | Maliyet | Mitigation |
|---|---|---|
| Eventual consistency | Yarı state görünürlüğü | UI'da `pending` state, açık iletişim |
| Compensation karmaşıklığı | Her step'in compensate'ini tasarla | Test driven (compensation test'leri) |
| Temporal sunucu ek bileşen | Bir sunucu daha çalışacak | Self-host, monitoring entegre |
| Worker scalability | Workflow worker'lar yatay scale | K8s HPA + Temporal worker config |
| Workflow versioning | Çalışan workflow'lar var, kod değişti | Temporal versioning API + paralel deploy |
| Test karmaşıklığı | Workflow + activity ayrı test | Temporal test framework, time skipping |

### 5.3. Saga'yı ne zaman kullanmamalı?

- **Tek servis transaction'ı** — `@Transactional` yeter
- **Strict ACID gerektiren işlem** — finansal core ledger gibi → tek servis, atomic
- **Sub-second latency** — saga overhead toleranssız
- **Compensation imkansız** — "para gönderildi, geri alınamaz" durumlarda 2-aşamalı tasarım gerekli (önce reserve, sonra commit)

## 6. Pratik örnek

### 6.1. EnrollmentSaga — tam örnek

**Workflow interface (Temporal annotation'lı):**

```java
// compliance-service veya orchestrator-service workflow paketi
@WorkflowInterface
public interface EnrollmentSagaWorkflow {

    @WorkflowMethod
    EnrollmentResult enroll(EnrollmentRequest request);

    @SignalMethod
    void cancelByAdmin(String reason);
}
```

**Activity interface'leri:**

```java
@ActivityInterface
public interface FinanceActivity {
    InvoiceId createInvoice(CreateInvoiceCommand cmd);
    void voidInvoice(InvoiceId invoiceId);
}

@ActivityInterface
public interface PaymentActivity {
    PaymentId capture(PaymentCommand cmd);
    void refund(PaymentId paymentId);
}

@ActivityInterface
public interface AcademicActivity {
    EnrollmentId confirmEnrollment(EnrollmentCommand cmd);
    void revertEnrollment(EnrollmentId enrollmentId);
}

@ActivityInterface
public interface NotificationActivity {
    void sendWelcomeEmail(UserId userId, String classId);
}
```

**Workflow implementation:**

```java
public class EnrollmentSagaWorkflowImpl implements EnrollmentSagaWorkflow {

    private static final ActivityOptions DEFAULT_OPTIONS = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(30))
        .setRetryOptions(RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(1))
            .setMaximumInterval(Duration.ofSeconds(30))
            .setBackoffCoefficient(2.0)
            .setMaximumAttempts(5)
            .setDoNotRetry(BusinessException.class.getName())
            .build())
        .build();

    private final FinanceActivity finance = Workflow.newActivityStub(FinanceActivity.class, DEFAULT_OPTIONS);
    private final PaymentActivity payment = Workflow.newActivityStub(PaymentActivity.class, DEFAULT_OPTIONS);
    private final AcademicActivity academic = Workflow.newActivityStub(AcademicActivity.class, DEFAULT_OPTIONS);
    private final NotificationActivity notification = Workflow.newActivityStub(NotificationActivity.class, DEFAULT_OPTIONS);

    private boolean adminCancelled = false;

    @Override
    public EnrollmentResult enroll(EnrollmentRequest req) {
        Saga.Options sagaOpts = new Saga.Options.Builder().setParallelCompensation(false).build();
        Saga saga = new Saga(sagaOpts);

        try {
            // 1. Fatura oluştur
            InvoiceId invoiceId = finance.createInvoice(new CreateInvoiceCommand(
                req.tenantId(),
                req.studentId(),
                req.tuitionAmount()
            ));
            saga.addCompensation(() -> finance.voidInvoice(invoiceId));

            if (adminCancelled) {
                throw new SagaCancelledException("admin tarafından iptal edildi");
            }

            // 2. Ödeme tahsili
            PaymentId paymentId = payment.capture(new PaymentCommand(
                invoiceId,
                req.tuitionAmount(),
                req.paymentMethodId(),
                req.sagaId()
            ));
            saga.addCompensation(() -> payment.refund(paymentId));

            // 3. Enrollment onayla
            EnrollmentId enrollmentId = academic.confirmEnrollment(new EnrollmentCommand(
                req.studentId(),
                req.classId(),
                req.sagaId()
            ));
            saga.addCompensation(() -> academic.revertEnrollment(enrollmentId));

            // 4. Hoş geldin maili (compensation gerekmez — informational)
            notification.sendWelcomeEmail(req.studentId(), req.classId().toString());

            return EnrollmentResult.success(invoiceId, paymentId, enrollmentId);

        } catch (ActivityFailure | SagaCancelledException ex) {
            saga.compensate();
            throw Workflow.wrap(ex);
        }
    }

    @Override
    public void cancelByAdmin(String reason) {
        this.adminCancelled = true;
    }
}
```

**Activity implementation (Spring servisinde):**

```java
@Component
@RequiredArgsConstructor
public class FinanceActivityImpl implements FinanceActivity {

    @GrpcClient("finance-service")
    private FinanceServiceGrpc.FinanceServiceBlockingStub financeStub;

    @Override
    public InvoiceId createInvoice(CreateInvoiceCommand cmd) {
        try {
            CreateInvoiceRequest req = CreateInvoiceRequest.newBuilder()
                .setTenantId(cmd.tenantId().toString())
                .setStudentId(cmd.studentId().toString())
                .setAmount(cmd.amount().amount().toString())
                .setCurrency(cmd.amount().currency().getCurrencyCode())
                .setIdempotencyKey(cmd.idempotencyKey())
                .build();

            CreateInvoiceResponse resp = financeStub
                .withDeadlineAfter(10, TimeUnit.SECONDS)
                .createInvoice(req);

            return new InvoiceId(UUID.fromString(resp.getInvoiceId()));
        } catch (StatusRuntimeException ex) {
            // Business exception ise retry yapma
            if (ex.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) {
                throw new BusinessException(ex.getStatus().getDescription());
            }
            throw new TransientException(ex);
        }
    }

    @Override
    public void voidInvoice(InvoiceId invoiceId) {
        // Idempotent — fatura zaten void'se sessizce geç
        financeStub.voidInvoice(VoidInvoiceRequest.newBuilder()
            .setInvoiceId(invoiceId.value().toString())
            .build());
    }
}
```

### 6.2. Saga tetikleme — REST endpoint'ten

```java
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/enrollments")
public class EnrollmentController {

    private final WorkflowClient workflowClient;

    @PostMapping
    public ResponseEntity<EnrollmentSagaResponse> enroll(@RequestBody @Valid EnrollmentRequest req) {
        String sagaId = "enrollment-" + req.studentId() + "-" + req.classId();

        EnrollmentSagaWorkflow workflow = workflowClient.newWorkflowStub(
            EnrollmentSagaWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue("enrollment-saga-queue")
                .setWorkflowId(sagaId)  // idempotency garantili
                .setWorkflowExecutionTimeout(Duration.ofMinutes(15))
                .build()
        );

        WorkflowClient.start(workflow::enroll, req);
        return ResponseEntity.accepted()
            .body(new EnrollmentSagaResponse(sagaId, "STARTED"));
    }

    @GetMapping("/{sagaId}/status")
    public EnrollmentSagaStatus status(@PathVariable String sagaId) {
        WorkflowExecution execution = WorkflowExecution.newBuilder()
            .setWorkflowId(sagaId).build();
        DescribeWorkflowExecutionResponse describe = workflowClient.getWorkflowServiceStubs()
            .blockingStub()
            .describeWorkflowExecution(DescribeWorkflowExecutionRequest.newBuilder()
                .setExecution(execution).build());
        return EnrollmentSagaStatus.from(describe);
    }
}
```

### 6.3. Worker registration

```java
@Configuration
public class TemporalWorkerConfig {

    @Bean
    public WorkflowServiceStubs workflowServiceStubs(@Value("${temporal.target}") String target) {
        return WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder().setTarget(target).build()
        );
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs stubs) {
        return WorkflowClient.newInstance(stubs);
    }

    @Bean
    public WorkerFactory workerFactory(WorkflowClient client) {
        return WorkerFactory.newInstance(client);
    }

    @Bean(initMethod = "start")
    public Worker enrollmentSagaWorker(
        WorkerFactory factory,
        FinanceActivityImpl financeAct,
        PaymentActivityImpl paymentAct,
        AcademicActivityImpl academicAct,
        NotificationActivityImpl notifAct
    ) {
        Worker worker = factory.newWorker("enrollment-saga-queue");
        worker.registerWorkflowImplementationTypes(EnrollmentSagaWorkflowImpl.class);
        worker.registerActivitiesImplementations(financeAct, paymentAct, academicAct, notifAct);
        return worker;
    }
}
```

### 6.4. Workflow unit test (Temporal test framework)

```java
class EnrollmentSagaWorkflowTest {

    private final TestWorkflowEnvironment testEnv = TestWorkflowEnvironment.newInstance();
    private final Worker worker = testEnv.newWorker("enrollment-saga-queue");

    private FinanceActivity financeMock = Mockito.mock(FinanceActivity.class);
    private PaymentActivity paymentMock = Mockito.mock(PaymentActivity.class);
    private AcademicActivity academicMock = Mockito.mock(AcademicActivity.class);
    private NotificationActivity notifMock = Mockito.mock(NotificationActivity.class);

    @BeforeEach
    void setup() {
        worker.registerWorkflowImplementationTypes(EnrollmentSagaWorkflowImpl.class);
        worker.registerActivitiesImplementations(financeMock, paymentMock, academicMock, notifMock);
        testEnv.start();
    }

    @AfterEach
    void teardown() {
        testEnv.close();
    }

    @Test
    void happyPath() {
        when(financeMock.createInvoice(any())).thenReturn(new InvoiceId(UUID.randomUUID()));
        when(paymentMock.capture(any())).thenReturn(new PaymentId(UUID.randomUUID()));
        when(academicMock.confirmEnrollment(any())).thenReturn(new EnrollmentId(UUID.randomUUID()));

        EnrollmentSagaWorkflow workflow = testEnv.getWorkflowClient().newWorkflowStub(
            EnrollmentSagaWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue("enrollment-saga-queue").build()
        );
        EnrollmentResult result = workflow.enroll(sampleRequest());

        assertThat(result.status()).isEqualTo(EnrollmentResult.Status.SUCCESS);
        verify(notifMock).sendWelcomeEmail(any(), any());
    }

    @Test
    void compensatesOnPaymentFailure() {
        InvoiceId invoiceId = new InvoiceId(UUID.randomUUID());
        when(financeMock.createInvoice(any())).thenReturn(invoiceId);
        when(paymentMock.capture(any())).thenThrow(new BusinessException("Insufficient funds"));

        EnrollmentSagaWorkflow workflow = testEnv.getWorkflowClient().newWorkflowStub(
            EnrollmentSagaWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue("enrollment-saga-queue").build()
        );

        assertThatThrownBy(() -> workflow.enroll(sampleRequest()))
            .isInstanceOf(WorkflowFailedException.class);

        verify(financeMock).voidInvoice(invoiceId); // compensation
        verify(academicMock, never()).confirmEnrollment(any());
        verify(notifMock, never()).sendWelcomeEmail(any(), any());
    }
}
```

## 7. Dikkat edilecek tuzaklar

**Tuzak 1 — Compensation imkansız adım.**
"Para hesaba transfer edildi, geri alınamaz" — geri alamayacağın şeyi saga'ya katma.
**Önleme:** İki aşamalı tasarım: önce reserve/authorize (geri alınabilir), sonra capture (final).

**Tuzak 2 — Idempotent olmayan activity.**
Temporal retry yapar, activity duplicate çalışır → duplicate invoice, duplicate payment.
**Önleme:** Her activity idempotency key kullanır. DB constraint + check.

**Tuzak 3 — Workflow içinde non-deterministic kod.**
`new Date()`, `UUID.randomUUID()`, `Thread.sleep()`, `Math.random()` workflow içinde kullanılmaz — replay'de farklı sonuç verir.
**Önleme:** `Workflow.currentTimeMillis()`, `Workflow.randomUUID()`, `Workflow.sleep()` kullan.

**Tuzak 4 — External call workflow içinde.**
Workflow doğrudan REST API çağırır, deterministic olmaktan çıkar.
**Önleme:** External call'lar activity içinde — workflow sadece activity'leri çağırır.

**Tuzak 5 — Çok büyük workflow.**
20 adımlı tek bir saga. Bakımı imkansız.
**Önleme:** Workflow'u sub-workflow'lara böl (child workflows).

**Tuzak 6 — Versioning'i atlamak.**
Workflow kodu değişti, production'da bekleyen workflow var → replay başarısız.
**Önleme:** `Workflow.getVersion("change-id", min, max)` API'si ile versiyonlu değişiklik.

**Tuzak 7 — Hata olduğunda susmak.**
Saga compensate edildi, kullanıcıya bilgi gitmedi.
**Önleme:** Compensation'dan sonra `notification.sendFailureNotice(...)` veya event yayınla.

**Tuzak 8 — Timeout planlamamak.**
Activity sonsuza kadar bekleyebilir.
**Önleme:** `ActivityOptions.setStartToCloseTimeout(...)` her activity için zorunlu.

**Tuzak 9 — Saga state'i ek bir yerde tutmak.**
Temporal zaten state tutar, ayrıca DB'de saga_state tablosu — duplicate state, sync sorunu.
**Önleme:** Saga state = Temporal'da. UI için query method kullan.

**Tuzak 10 — Saga vs Sync karıştırmak.**
Basit 2-adımlı işlem için saga overkill. Tek transaction'da yapılabilecek şey.
**Önleme:** Saga **birden fazla servis** + **failure compensation gerektiren** + **uzun süreli** akışlar için. Aksi: basit sync yeter.

**Tuzak 11 — Compensation order ihlali.**
Forward sırasıyla compensation aynı sıra ile yapılır — yanlış. Ters sırada olmalı.
**Önleme:** Saga library zaten ters sırada compensation yapar. Custom yazıyorsan dikkat.

## 8. Diğer konularla ilişkisi

- [Microservices Architecture](./microservices-architecture) — distributed transaction problemi
- [Event-Driven Architecture](./event-driven-architecture) — saga choreography style EDA üzerinden
- [Outbox Pattern](./outbox-pattern) — saga'nın altyapısı (atomic write + publish)
- [Domain-Driven Design](./domain-driven-design) — compensation domain dilinde tanımlanır
- [gRPC Service Communication](../03-backend/03-grpc-service-communication) — activity'ler servisleri gRPC ile çağırır

## 9. Daha derine inmek için

**Resmi kaynaklar:**
- temporal.io/docs — Temporal dokümantasyonu
- "Patterns of Distributed Systems" — Unmesh Joshi
- "Microservices Patterns" — Chris Richardson (Saga chapter)
- Garcia-Molina & Salem, "Sagas" (1987) — orijinal makale

**Online:**
- microservices.io/patterns/data/saga.html
- learn.temporal.io — Temporal eğitim
- youtube.com/temporalio — Temporal konferans videoları

**Search keywords (İngilizce):**
- "saga pattern microservices"
- "compensation transaction"
- "choreography vs orchestration saga"
- "temporal workflow java"
- "distributed transaction alternative"
- "long-running business process"

## 10. Sözlük

- **2PC (Two-Phase Commit)** — Distributed ACID transaction protokolü. Lumix kullanmıyor.
- **Activity** — Temporal'da işin yapıldığı atomic unit. Servis çağrısı, DB update vs.
- **Choreography Saga** — Merkezi koordinatör olmadan, event'lerle koordine olan saga.
- **Compensation Action / Compensation Event** — Başarısız adımı telafi etmek için yapılan iş.
- **Deterministic Code** — Aynı input ile aynı output veren kod. Workflow'da zorunlu.
- **Event History** — Temporal'ın workflow state'ini takip ettiği append-only log.
- **Forward Recovery** — Hata anında geri sarmak yerine ileri devam etmek (saga'da nadiren).
- **Idempotency Key** — Bir işlemin tekrar tetiklenmesi durumunda duplicate olmamasını sağlayan anahtar.
- **Long-Running Workflow** — Saniyelerden günlere uzanabilen iş süreci.
- **Orchestration Saga** — Merkezi orchestrator'ın adımları yönettiği saga.
- **Saga** — Distributed transaction alternatifi pattern. Lokal transaction + compensation.
- **Temporal** — Lumix'in workflow engine'i.
- **Workflow** — Temporal'da iş sürecinin kod tanımı.
- **Worker** — Workflow + activity kodunu execute eden process.
