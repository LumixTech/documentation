---
title: Payment State Machine ve Saga Orchestration
description: Lifecycle Pending → Authorized → Captured → Refunded/Failed, idempotency, Spring State Machine vs manual implementation, Temporal saga koordinasyonu.
sidebar_position: 3
---

## Bu sayfa ne anlatıyor?

Lumix'te bir ödemenin **lifecycle** akışını (Pending → Authorized → Captured → Refunded/Failed), **state machine pattern**'in nasıl uygulandığını, **Idempotency-Key** header kullanımını, **Spring State Machine vs manuel implementation** kararını ve **Temporal saga** ile multi-step payment workflow koordinasyonunu anlatır. Adapter pattern (1. sayfa) "kime soruyoruz"u, bu sayfa "ne sırayla ve nasıl güvenli ilerliyoruz"u verir.

## 1. Bu nedir? (Sıfırdan)

### 1.1. Günlük hayattan analoji

Bir **otel rezervasyonu** düşün. Karta sadece "rezervasyon yaptım" demekle olmaz. Akış:

```text
1. Rezervasyon yapıldı (Pending)
2. Otel kartı authorize etti (ön provizyon — para BLOKE ama tahsil EDİLMEDİ)
3. Müşteri check-in yaptı → otel kartı capture ediyor (gerçek tahsilat)
4. Müşteri check-out → işlem tamamlandı (Captured)
5. İade gerekirse → Refunded
```

Aynı kavram online ödemede:
- **Pending** — ödeme başlatıldı
- **Authorized** — kart yetkilendirildi, para bloke
- **Captured** — para gerçekten tahsil edildi
- **Failed** — herhangi bir aşamada başarısız
- **Refunded** — tahsil edilmiş ödeme iade edildi

### 1.2. State machine nedir?

Bir entity'nin **alabileceği state'lerin** ve **state'ler arası izinli geçişlerin** matematiksel modeli. Geçersiz geçiş yapmaya çalışırsan exception atılır.

```text
Pending ──authorize──► Authorized ──capture──► Captured
   │                       │                       │
   │                       └──fail──► Failed       └──refund──► Refunded
   └──fail──► Failed
```

`Captured → Pending` gibi geçiş **yasak**.

### 1.3. Idempotency

Aynı işlemi tekrar gönderen client (network retry, double-click) için sistem **iki kez charge etmemeli**. Çözüm: **Idempotency-Key** header. İlk istek key ile cache'lenir; aynı key tekrar gelirse cache'den cevap döner.

## 2. Hangi problemi çözüyor?

### 2.1. State explosion ve invalid transition

Naif kod:

```java
// ANTI-PATTERN
if (payment.status.equals("PENDING")) { ... }
if (payment.status.equals("AUTHORIZED")) { ... }
// 7 farklı yerde duplicate state check
```

State sayısı arttıkça bug doğar:
- `Captured → Capture` (zaten captured)
- `Failed → Refund` (failed'i refund edemezsin)
- `Refunded → Refund` (double refund!)

### 2.2. Distributed multi-step transaction

Ödeme tek başına yetmez:
1. Invoice oluştur
2. Payment initiate
3. Stock reserve (eğer ürün satışı varsa)
4. Confirm enrollment (Lumix'te öğrenci kayıt onayı)
5. Notify customer

Adım 4 başarısız olursa adım 2'yi geri al (compensate). Bu **saga pattern** ile yönetilir.

### 2.3. Idempotency yokluğu = çift charge

```text
Client → POST /payments (timeout sonrası retry)
       → POST /payments (retry)
Backend her ikisini de işler → İki kez charge
```

Idempotency-Key olmasaydı bunu engellemek zor.

## 3. Nasıl çalışıyor? (Çalışma prensibi)

### 3.1. State diagram (tam)

```text
                  ┌─────────┐
                  │ PENDING │
                  └────┬────┘
            ┌──────────┼──────────────┐
            ▼          ▼              ▼
      ┌─────────┐  ┌──────────────┐ ┌────────┐
      │ AUTH'D  │  │ THREE_D_     │ │ FAILED │
      │         │  │ PENDING      │ └────────┘
      └────┬────┘  └──────┬───────┘
           │              │
           │       ┌──────┴──────┐
           │       │             │
           │       ▼             ▼
           │   AUTHORIZED     FAILED
           │
   ┌───────┴───────┐
   ▼               ▼
CAPTURED       VOIDED (yetkilendirme iptal)
   │
┌──┴────────────────────┐
│                        │
▼                        ▼
PARTIALLY_REFUNDED   REFUNDED
   │
   └──► (more refunds) → REFUNDED
```

State'ler:

| State | Anlam |
|---|---|
| `PENDING` | Initialize edildi, henüz provider'a gitmedi |
| `THREE_D_PENDING` | 3DS challenge bekliyor (callback bekleniyor) |
| `AUTHORIZED` | Kart yetkilendirildi (para bloke) |
| `CAPTURED` | Para tahsil edildi |
| `VOIDED` | Authorized iptal edildi (capture'dan önce) |
| `PARTIALLY_REFUNDED` | Kısmi iade yapıldı |
| `REFUNDED` | Tam iade yapıldı |
| `FAILED` | Herhangi adımda failed |

### 3.2. Transition kuralları

```java
public enum PaymentState {
    PENDING, THREE_D_PENDING, AUTHORIZED,
    CAPTURED, VOIDED, PARTIALLY_REFUNDED, REFUNDED, FAILED;

    private static final Map<PaymentState, Set<PaymentState>> TRANSITIONS = Map.of(
            PENDING, Set.of(THREE_D_PENDING, AUTHORIZED, FAILED),
            THREE_D_PENDING, Set.of(AUTHORIZED, FAILED),
            AUTHORIZED, Set.of(CAPTURED, VOIDED, FAILED),
            CAPTURED, Set.of(PARTIALLY_REFUNDED, REFUNDED),
            PARTIALLY_REFUNDED, Set.of(PARTIALLY_REFUNDED, REFUNDED),
            VOIDED, Set.of(),
            REFUNDED, Set.of(),
            FAILED, Set.of()
    );

    public boolean canTransitionTo(PaymentState target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
```

### 3.3. Spring State Machine vs manuel

| Yaklaşım | Avantaj | Dezavantaj |
|---|---|---|
| **Spring State Machine** | Built-in transition validation, event-based, persistence | Learning curve, debug zor, version uyumsuzluğu |
| **Manuel (state map + enum)** | Basit, debug açık, Java'da tamamen yönetilir | Spring State Machine'in built-in event/listener'ı yok |

**Lumix kararı**: Manuel implementation. State sayısı az (8), business kuralları net. Spring State Machine'in maliyeti getirilerini aşıyor.

### 3.4. Idempotency-Key flow

```text
1. Client → POST /payments
            Idempotency-Key: 01HXY-CLIENT-GEN
            { ... }
2. Backend:
   a. SELECT * FROM idempotency_keys WHERE key = '01HXY-...'
   b. Var? → cached response döndür (200, aynı response)
   c. Yok?
      INSERT idempotency_keys (key, request_hash, response=null)
      → işlem yap
      → UPDATE idempotency_keys SET response = ...
   d. Cevap döndür
3. Aynı client retry: aynı Idempotency-Key
   → cached response → aynı paymentId, aynı state
```

TTL: 24-48 saat (kullanıcı saatler sonra retry yapmaz).

### 3.5. Saga ile multi-step

Temporal workflow:

```text
EnrollmentPaymentSaga:
  1. create_invoice → invoice_id
  2. initiate_payment(invoice_id) → payment_id
  3. wait_for_authorization (timeout 10 min)
  4. capture_payment(payment_id)
  5. confirm_enrollment(student_id)
  6. send_receipt_email

Her adım failure'da compensation:
  6 fail → void capture (refund değil, henüz settlement değil)
  5 fail → refund payment + cancel invoice
  4 fail → void authorization + cancel invoice
  3 timeout → cancel invoice
  2 fail → cancel invoice
```

Temporal **durable** — her adım state'i kalıcı, retry yapılır.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Payment aggregate

```java
public class Payment {
    private UUID id;
    private UUID tenantId;
    private UUID invoiceId;
    private PaymentProviderId providerId;
    private String providerTransactionId;
    private BigDecimal amount;
    private Currency currency;
    private PaymentState state;
    private BigDecimal refundedAmount;
    private List<DomainEvent> uncommittedEvents = new ArrayList<>();

    public static Payment initiate(UUID id, UUID tenantId, UUID invoiceId,
                                    PaymentProviderId provider,
                                    BigDecimal amount, Currency currency) {
        Payment p = new Payment();
        p.id = id;
        p.tenantId = tenantId;
        p.invoiceId = invoiceId;
        p.providerId = provider;
        p.amount = amount;
        p.currency = currency;
        p.state = PaymentState.PENDING;
        p.refundedAmount = BigDecimal.ZERO;
        p.uncommittedEvents.add(new PaymentInitiated(id, tenantId, invoiceId, amount));
        return p;
    }

    public void markAuthorized(String providerTxId, String authCode) {
        transitionTo(PaymentState.AUTHORIZED);
        this.providerTransactionId = providerTxId;
        uncommittedEvents.add(new PaymentAuthorized(id, providerTxId, authCode));
    }

    public void markCaptured() {
        transitionTo(PaymentState.CAPTURED);
        uncommittedEvents.add(new PaymentCaptured(id));
    }

    public void markFailed(String code, String message) {
        transitionTo(PaymentState.FAILED);
        uncommittedEvents.add(new PaymentFailed(id, code, message));
    }

    public void recordRefund(BigDecimal refundAmount) {
        if (state != PaymentState.CAPTURED && state != PaymentState.PARTIALLY_REFUNDED) {
            throw new InvalidStateTransitionException(
                    "Cannot refund from state: " + state);
        }
        BigDecimal newTotal = refundedAmount.add(refundAmount);
        if (newTotal.compareTo(amount) > 0) {
            throw new RefundExceedsAmountException();
        }
        this.refundedAmount = newTotal;
        if (newTotal.compareTo(amount) == 0) {
            this.state = PaymentState.REFUNDED;
        } else {
            this.state = PaymentState.PARTIALLY_REFUNDED;
        }
        uncommittedEvents.add(new PaymentRefunded(id, refundAmount, newTotal, state));
    }

    private void transitionTo(PaymentState target) {
        if (!state.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(
                    "Cannot transition from " + state + " to " + target);
        }
        this.state = target;
    }
}
```

### 4.2. Idempotency persistence

```sql
CREATE TABLE idempotency_keys (
    key VARCHAR(128) PRIMARY KEY,
    tenant_id UUID NOT NULL,
    user_id UUID NOT NULL,
    endpoint VARCHAR(255) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    response_body JSONB,
    response_status INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_idem_expires ON idempotency_keys(expires_at);
```

Periyodik cleanup job `expires_at < NOW()` silsin.

### 4.3. Idempotency interceptor

```java
@Component
public class IdempotencyInterceptor implements HandlerInterceptor {

    private final IdempotencyKeyRepository repository;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        String key = req.getHeader("Idempotency-Key");
        if (key == null) {
            return true; // Idempotency optional for non-mutating endpoints
        }

        Optional<IdempotencyKeyEntry> existing = repository.findByKey(key);
        if (existing.isPresent()) {
            IdempotencyKeyEntry entry = existing.get();
            String currentHash = hashRequest(req);
            if (!entry.requestHash().equals(currentHash)) {
                throw new IdempotencyMismatchException(key);
            }
            // Return cached response
            resp.setStatus(entry.responseStatus());
            resp.getWriter().write(entry.responseBody());
            return false;
        }
        // Mark as in-flight
        repository.markInFlight(key, hashRequest(req));
        return true;
    }
}
```

### 4.4. Saga workflow (Temporal)

```java
@WorkflowInterface
public interface EnrollmentPaymentWorkflow {
    @WorkflowMethod
    EnrollmentResult execute(EnrollmentPaymentInput input);
}

public class EnrollmentPaymentWorkflowImpl implements EnrollmentPaymentWorkflow {

    private final FinanceActivities finance = Workflow.newActivityStub(...);
    private final EnrollmentActivities enrollment = Workflow.newActivityStub(...);
    private final NotificationActivities notification = Workflow.newActivityStub(...);

    @Override
    public EnrollmentResult execute(EnrollmentPaymentInput input) {
        UUID invoiceId = null;
        UUID paymentId = null;
        try {
            invoiceId = finance.createInvoice(input);
            paymentId = finance.initiatePayment(invoiceId, input.tenantId());

            PaymentStatus status = Workflow.await(Duration.ofMinutes(10),
                    () -> finance.queryPaymentStatus(paymentId).isTerminal());

            if (!status.isAuthorized()) {
                finance.cancelInvoice(invoiceId);
                return EnrollmentResult.failed("Payment not authorized");
            }

            finance.capturePayment(paymentId);
            enrollment.confirmEnrollment(input.studentId(), input.classId());
            notification.sendReceipt(input.payerEmail(), paymentId);
            return EnrollmentResult.success();
        } catch (Exception e) {
            // Compensate
            if (paymentId != null) finance.refundPayment(paymentId);
            if (invoiceId != null) finance.cancelInvoice(invoiceId);
            return EnrollmentResult.failed(e.getMessage());
        }
    }
}
```

Temporal güvencesi: workflow ne kadar uzun sürerse sürsün, hangi adımda çökerse çöksün, **state durable** olduğu için resume edilir.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. State machine vs status string

| Yaklaşım | Avantaj | Dezavantaj |
|---|---|---|
| String status field | Basit | Validation eksik, transition control yok |
| Enum + transition map | Type-safe, validation built-in | Az ekstra kod |
| Spring State Machine | Full feature | Karmaşık, version riski |

**Lumix tercihi**: Enum + transition map (manuel). Domain'de açık ve okunabilir.

### 5.2. Idempotency yöntemi

| Yöntem | Avantaj | Dezavantaj |
|---|---|---|
| Idempotency-Key header | Standard, fine-grained | Client gerekirse implement etmeli |
| Request hash karşılaştırma | Otomatik | Hash collision riski, slow |
| UNIQUE constraint (DB) | DB seviyesinde garantili | Sadece insert için, update için yok |

Lumix payment endpoint'lerinde **Idempotency-Key zorunlu**. Stripe ile aynı pattern.

### 5.3. Saga: Choreography vs Orchestration

| Yaklaşım | Avantaj | Dezavantaj |
|---|---|---|
| Choreography (event-driven) | Decoupled, no central point | State takibi zor, debugging acı |
| Orchestration (Temporal) | Central state, retry built-in | Single point of dependency |

**Lumix tercihi**: Payment saga için **Temporal orchestration**. Multi-step + retry + compensate karmaşıklığı central orchestrator'ı haklı çıkarır.

### 5.4. Trade-off

- **Manuel state machine**: Spring State Machine'in event handler'ı yok. Domain event'lerle compense ediyoruz.
- **Idempotency overhead**: Her payment request DB lookup + insert. Performance etkisi var ama güvenlik kritik.
- **Temporal dependency**: Saga için Temporal cluster çalışmalı. HA + monitoring şart.

## 6. Pratik örnek

### 6.1. Controller (idempotent endpoint)

```java
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentInitiationUseCase useCase;

    @PostMapping
    @PreAuthorize("hasAuthority('payments:create')")
    public ResponseEntity<PaymentInitiationResponse> initiate(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody InitiatePaymentDto dto,
            @AuthenticationPrincipal UserPrincipal user) {

        PaymentInitiationResult result = useCase.initiate(
                idempotencyKey,
                new InitiatePaymentCommand(
                        user.tenantId(), user.userId(),
                        dto.invoiceId(), dto.amount(), dto.currency(),
                        dto.cardToken(), dto.callbackUrl()));
        return ResponseEntity.ok(PaymentInitiationResponse.from(result));
    }
}
```

### 6.2. State transition unit test

```java
class PaymentStateMachineTest {

    @Test
    void shouldTransitionFromPendingToAuthorized() {
        var p = Payment.initiate(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), PaymentProviderId.IYZICO,
                new BigDecimal("100.00"), Currency.getInstance("TRY"));

        p.markAuthorized("provider-tx-123", "AUTH123");

        assertThat(p.getState()).isEqualTo(PaymentState.AUTHORIZED);
    }

    @Test
    void shouldRejectCapturedToPendingTransition() {
        var p = createCapturedPayment();

        assertThatThrownBy(() -> p.markAuthorized("x", "y"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void shouldAllowPartialRefund() {
        var p = createCapturedPayment(); // amount=100
        p.recordRefund(new BigDecimal("30.00"));
        assertThat(p.getState()).isEqualTo(PaymentState.PARTIALLY_REFUNDED);

        p.recordRefund(new BigDecimal("70.00"));
        assertThat(p.getState()).isEqualTo(PaymentState.REFUNDED);
    }

    @Test
    void shouldRejectOverRefund() {
        var p = createCapturedPayment(); // amount=100
        assertThatThrownBy(() -> p.recordRefund(new BigDecimal("150.00")))
                .isInstanceOf(RefundExceedsAmountException.class);
    }
}
```

### 6.3. Outbox event publishing

```java
@Service
@RequiredArgsConstructor
public class PaymentInitiationUseCase {

    private final PaymentRepository repository;
    private final OutboxRepository outbox;
    private final PaymentAdapterFactory factory;
    private final IdempotencyKeyService idempotency;
    private final ObjectMapper objectMapper;

    @Transactional
    public PaymentInitiationResult initiate(String idempotencyKey, InitiatePaymentCommand cmd) {
        return idempotency.executeIdempotent(idempotencyKey, cmd, () -> {
            Payment payment = Payment.initiate(
                    UuidV7Generator.generate(), cmd.tenantId(),
                    cmd.invoiceId(), factory.providerIdForTenant(cmd.tenantId()),
                    cmd.amount(), cmd.currency());

            PaymentPort adapter = factory.forTenant(cmd.tenantId());
            AuthorizationResult auth = adapter.authorize(toRequest(payment, cmd));

            applyResult(payment, auth);
            repository.save(payment);
            outbox.saveAll(payment.flushEvents());

            return PaymentInitiationResult.from(payment, auth);
        });
    }
}
```

## 7. Dikkat edilecek tuzaklar

- **Status string ile kontrol**. Type-safe enum kullan, transition map ile validate.
- **Idempotency-Key zorunlu olmayan endpoint**. Payment endpoint'lerinde zorunlu yap.
- **TTL eksik idempotency**. Sonsuza dek cache olmamalı; expire policy şart.
- **Idempotency response cache'lemeden işlem yapma**. İlk request başarısız olduktan sonra ikinci request farklı response döndürürse client confused.
- **Refund amount > captured**. Validate et; aksi halde negatif balance.
- **Multiple capture**. Authorized payment'ı iki kez capture ANTI-PATTERN; state machine engellesin.
- **Saga compensation yok**. Step 5 failure'da step 2 refund edilmezse para kayıp.
- **Temporal workflow versioning**. Mevcut running workflow'lar yeni kod ile crash etmesin; versioning API kullan.
- **State transition event yok**. Her transition `PaymentXxx` domain event üretmeli; downstream consumer'lar bunu bekler.
- **3DS timeout yönetimi**. Müşteri 3DS sayfasını açıp 10 dakika hiçbir şey yapmazsa state PENDING'de kalır. Cleanup job veya Temporal workflow timeout.
- **Distributed transaction (XA) deneme**. PostgreSQL + provider arasında atomic txn imkansız. Saga zorunlu.
- **Audit log eksikliği**. Her state transition `audit_logs`'a girer; finance audit kritik.

## 8. Diğer konularla ilişkisi

- [Payment Adapter Pattern](./01-payment-adapter-pattern.md) — kime soruyoruz
- [Bank Virtual POS](./02-bank-virtual-pos-routing.md) — VPOS detayı
- [Refund Handling](./04-refund-handling.md) — refund kuralları
- [Workflow Temporal](../workflow-temporal) — saga orchestration
- [Outbox Pattern](../02-architecture-patterns/06-outbox-pattern.md)
- [Audit Log](../security-compliance/audit-log-design)
- [Domain Servisleri](../01-tenancy-and-domain-model/02-domain-services-overview.md) — finance-service detay

## 9. Daha derine inmek için

- Stripe — [Idempotency keys](https://docs.stripe.com/api/idempotent_requests)
- Microsoft — [Saga distributed transactions pattern](https://learn.microsoft.com/en-us/azure/architecture/reference-architectures/saga/saga)
- Temporal — [Saga pattern docs](https://docs.temporal.io/encyclopedia/temporal-sdks)
- Spring State Machine — [Reference](https://docs.spring.io/spring-statemachine/docs/current/reference/) (Lumix kullanmaz ama referans)
- Araştırma keyword'leri: `payment state machine pattern`, `idempotency key implementation`, `saga compensation event`, `distributed transaction microservice`

## 10. Sözlük

- **State machine** — Entity'nin izinli state'leri ve transition'larını modelleyen yapı.
- **Pending** — Ödeme başlatıldı, provider'a gitmedi state'i.
- **Authorized** — Kart yetkilendirildi, para bloke.
- **Captured** — Para tahsil edildi.
- **Voided** — Authorization iptal edildi (capture'dan önce).
- **Refunded** — Tahsil edilmiş ödeme iade edildi.
- **Partially Refunded** — Tahsil edilmiş ödemenin bir kısmı iade edildi.
- **Idempotency-Key** — Aynı request'i tekrar gönderildiğinde çift işlem engellemek için client header.
- **Saga** — Multi-step distributed transaction pattern; her adım kendi DB'sinde commit, fail'de compensation.
- **Compensation** — Saga'da bir adım başarısız olunca önceki adımları geri alan action.
- **Temporal** — Lumix'in workflow orchestration engine'i.
- **3D Secure** — Visa/Mastercard kart sahibi doğrulama protokolü.
- **Outbox** — Atomic write + event publish için ara tablo.
