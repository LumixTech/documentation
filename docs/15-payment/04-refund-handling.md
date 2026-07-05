---
title: Refund Handling — Tam ve Kısmi İade
description: Full refund vs partial refund, provider'a göre fark, audit log entegrasyonu, fraud check, refund yetkisi, saga compensation.
sidebar_position: 4
---

## Bu sayfa ne anlatıyor?

Lumix'te bir ödemenin **nasıl iade edildiğini**, **tam refund vs kısmi refund** farkını, **provider'a göre davranış** farklılıklarını, **audit log** entegrasyonunu, **fraud check** mekanizmasını, **refund yetkilendirmesini** (sadece admin) ve **Saga compensation** olarak refund'ın nasıl kullanıldığını anlatır. Önceki state machine sayfası `Captured → Refunded` geçişini tanımladı; bu sayfa **iş kuralları + güvenlik + provider davranışı**nı detaylandırır.

## 1. Bu nedir? (Sıfırdan)

### 1.1. Günlük hayattan analoji

Bir restoran'da yemek yedin, kredi kartı ile ödedin. Yemek beklediğin gibi gelmedi → restoran yöneticisi parayı **iade ediyor**. İki yol var:

- **Tam iade**: Ödediğin tüm tutarı geri verir
- **Kısmi iade**: Sadece şikayet ettiğin yemek kadarını verir, içecekleri tutar

Online ödemede aynısı:
- **Full refund**: tüm `amount` geri ödenir
- **Partial refund**: `amount`'un bir kısmı geri ödenir; birden fazla partial refund yapılabilir, toplam `amount`'u aşamaz

### 1.2. Refund ne değil?

- **Void** ≠ Refund. Void: henüz capture edilmemiş authorization iptal. Para hiç tahsil edilmedi.
- **Chargeback** ≠ Refund. Chargeback: müşteri kartı banka aracılığıyla zorla geri istedi (dispute). İşletmenin onayı yok.

Refund **mağazanın gönüllü** iade işlemidir.

### 1.3. Settlement timing

Türkiye'de banka VPOS settlement T+1 veya T+2. Bu önemli:
- Capture aynı gün → ertesi gün settlement → para mağaza IBAN'ına
- Refund tetiklendiyse settlement'tan önce → "void" benzeri davranır (provider'a göre değişir)
- Settlement'tan sonra refund → reverse transaction → yeni settlement çıkışı

Provider API'leri bu detayı saklar ama edge case'leri bilmek lazım.

## 2. Hangi problemi çözüyor?

### 2.1. Müşteri memnuniyeti + yasal zorunluluk

- Tüketicinin Korunması Hakkında Kanun: belirli koşullarda iade hakkı
- Müşteri talepleri → iade işlemi
- Service issue durumunda goodwill refund

### 2.2. Saga compensation

Multi-step workflow'da ödeme alındı ama sonraki adım başarısız (örn. öğrenci kaydı oluşmadı) → ödeme **otomatik refund edilmeli**. Yoksa para tahsil edildi ama hizmet verilmedi.

### 2.3. Fraud / hatalı işlem

- Yanlış tutarda charge → hızlı refund
- Çift charge → iki tane charge için bir refund
- Test environment'tan production'a sızan test transaction → temizlik refund

### 2.4. Yetki kontrolü

Refund **iade edilebilen bir paraya el atmak** demektir. Sadece authorized user'lar yapabilmeli. Lumix'te:
- Tenant admin: tam refund yetkisi
- Finance user: kısıtlı refund (limit altında)
- Normal user: refund yetkisi yok

## 3. Nasıl çalışıyor? (Çalışma prensibi)

### 3.1. Refund flow

```text
1. Admin → POST /payments/{id}/refund { amount, reason }
2. Authorization check (RBAC + amount limit)
3. Fraud check (rapid refund pattern?)
4. Load Payment, validate state
5. Validate amount (≤ unrefunded balance)
6. PaymentPort.refund(request) → provider call
7. Provider response →
   - success → Payment.recordRefund(amount)
   - failed → log + bubble up
8. INSERT refund record
9. Outbox event: PaymentRefunded
10. Audit log
11. Response
```

### 3.2. Provider davranış farkları

| Provider | Refund window | Partial refund | Multiple partial |
|---|---|---|---|
| Iyzico | 60 gün | Destekli | Destekli |
| Param | Bankaya göre | Destekli | Destekli |
| Akbank VPOS | T+0 void / sonrası refund | Destekli | Destekli |
| Garanti VPOS | T+0 void / sonrası refund | Destekli | Destekli |
| İş Bankası VPOS | Limit'i banka belirler | Genelde destekli | Destekli |

Lumix adapter `refund()` her birinin nuance'ını sarar; domain bilmez.

### 3.3. Refund state model

`Payment` aggregate `refundedAmount` field'ı tutar:

```text
amount = 100.00 TRY, refundedAmount = 0     → CAPTURED
refund(30) → refundedAmount = 30           → PARTIALLY_REFUNDED
refund(40) → refundedAmount = 70           → PARTIALLY_REFUNDED
refund(30) → refundedAmount = 100          → REFUNDED
refund(1)  → ERROR (exceeds amount)
```

### 3.4. Refund table

```sql
CREATE TABLE refunds (
    refund_id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payments(payment_id),
    tenant_id UUID NOT NULL,
    amount NUMERIC(15, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    initiated_by UUID NOT NULL,            -- user_id
    state VARCHAR(32) NOT NULL,             -- 'PENDING', 'COMPLETED', 'FAILED'
    provider_refund_id VARCHAR(255),
    error_code VARCHAR(64),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_refunds_payment ON refunds(payment_id);
CREATE INDEX idx_refunds_tenant ON refunds(tenant_id);
```

Bir payment'ın birden fazla refund'ı olabilir (multiple partial).

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Refund yetkilendirme matrix

| Role | Refund limit | Onay süreci |
|---|---|---|
| Tenant Admin | Sınırsız | Direkt |
| Finance Manager | 10.000 TRY altı | Direkt |
| Finance Staff | 500 TRY altı | Direkt |
| Teacher / Other | 0 | Refund yetkisi yok |
| Lumix Internal Admin | Sınırsız | İkinci onay (4-eyes principle) |

Permission: `payments:refund:full`, `payments:refund:partial:limit:10000`, vs.

### 4.2. Fraud check rules

Suspicious refund pattern:
- Aynı payment için 5+ refund attempt → block + alert
- Aynı user'dan 10dk içinde 3+ refund initiate → rate limit
- Tenant geneli refund rate > %20 → operations alert
- Refund amount > payment amount → reject (state machine zaten)

Fraud detection async; refund hemen reddedilmez ama "review queue"ya düşer.

### 4.3. Saga compensation as refund

```text
EnrollmentPaymentSaga:
  Step 1: createInvoice    → invoice_id
  Step 2: initiatePayment  → payment_id
  Step 3: capturePayment   → payment captured
  Step 4: confirmEnrollment → fails

Compensation reverse order:
  Step 4 fail → cancel step 4 (no-op since failed)
  Step 3 compensate → refundPayment(payment_id, full amount, "enrollment failed")
  Step 2 compensate → no-op (refund already covers)
  Step 1 compensate → cancelInvoice
```

Workflow'da refund'ın **otomatik tetiklenmesi** kritik. Manuel admin müdahalesi gerekmez.

### 4.4. Refund initiate methods

| Trigger | Kaynak | Notlar |
|---|---|---|
| Manual admin refund | UI → API | Permission + amount limit check |
| Saga compensation | Temporal workflow | Internal trigger, system actor |
| Chargeback notification | Provider webhook | Auto-refund + dispute flag |
| Customer request workflow | Approval workflow | Multi-step approval |

### 4.5. Audit log content

Her refund için:
```json
{
  "action": "payment.refund",
  "actor_user_id": "...",
  "tenant_id": "...",
  "payment_id": "...",
  "refund_id": "...",
  "amount": "30.00",
  "currency": "TRY",
  "reason": "Customer requested cancellation",
  "approval_chain": ["admin_user_x"],
  "timestamp": "2026-05-27T..."
}
```

Audit-service'in append-only DB'sinde tutulur.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. Partial refund destekleme

Bazı projeler partial refund'ı **basitlik için kapatır** ("tam refund veya hiç"). Lumix destekler çünkü:
- Eğitim hizmeti birden çok modül içerir; sadece bir modül iptali kısmi refund gerektirir
- Customer expectations partial refund seviyesinde

### 5.2. Refund vs Void karar logic

```java
if (payment.state == AUTHORIZED && capturedAt == null) {
    adapter.void(payment);  // henüz para tahsil edilmedi
} else {
    adapter.refund(payment, amount);  // settlement sonrası refund
}
```

Bu karar Lumix domain'inde, provider'a göre değişmez. Adapter `void()` veya `refund()` ayrı method'larıyla davranışı netleştirir.

### 5.3. Fraud check senkron vs async

| Yaklaşım | Avantaj | Dezavantaj |
|---|---|---|
| Senkron (refund öncesi block) | Anında dur | Latency artar, false positive frustrating |
| Async (refund sonrası flag) | Hızlı UX | Fraud gerçekleşir sonra yakalanır |

**Lumix tercihi**: senkron rule-based (hard limit) + async ML (varsa). Hard limit'ler: amount > captured, rate limit aşımı.

### 5.4. Trade-off

- **Refund window**: Provider'a göre 60-180 gün. Bu süre sonrası refund imkansız → manual bank transfer.
- **Multiple partial refund**: Provider başına farklı API. Adapter sınırı saklayabilir.
- **Settlement timing**: Refund initiate edildi → settlement gerçekleşmemiş olabilir. Banka iadeyi farklı timing'lerde yapar; UI'da "iade işlemi başlatıldı, 1-5 iş günü içinde hesabınıza yansıyacaktır" mesajı.

## 6. Pratik örnek

### 6.1. Refund endpoint

```java
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class RefundController {

    private final RefundUseCase useCase;

    @PostMapping("/{paymentId}/refund")
    @PreAuthorize("hasAuthority('payments:refund:initiate')")
    public ResponseEntity<RefundResponse> refund(
            @PathVariable UUID paymentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RefundRequestDto dto,
            @AuthenticationPrincipal UserPrincipal user) {

        RefundResult result = useCase.refund(idempotencyKey, new RefundCommand(
                paymentId, user.userId(), user.tenantId(),
                dto.amount(), dto.currency(), dto.reason()));
        return ResponseEntity.ok(RefundResponse.from(result));
    }
}
```

### 6.2. RefundUseCase

```java
@Service
@RequiredArgsConstructor
public class RefundUseCase {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentAdapterFactory factory;
    private final RefundAuthorizationService authzService;
    private final FraudCheckService fraudCheck;
    private final IdempotencyKeyService idempotency;
    private final OutboxRepository outbox;
    private final AuditLogger audit;

    @Transactional
    public RefundResult refund(String idempotencyKey, RefundCommand cmd) {
        return idempotency.executeIdempotent(idempotencyKey, cmd, () -> {
            Payment payment = paymentRepository.findById(cmd.paymentId())
                    .orElseThrow(() -> new PaymentNotFoundException(cmd.paymentId()));

            if (!payment.tenantId().equals(cmd.tenantId())) {
                throw new ForbiddenException("Tenant mismatch");
            }

            authzService.authorize(cmd.userId(), cmd.amount());

            FraudCheckResult fraud = fraudCheck.checkRefund(cmd, payment);
            if (fraud.isBlocked()) {
                audit.log("payment.refund.blocked", cmd.userId(),
                        cmd.paymentId().toString(), fraud.reason());
                throw new FraudCheckException(fraud.reason());
            }

            Refund refund = Refund.initiate(
                    UuidV7Generator.generate(),
                    payment.id(), cmd.tenantId(),
                    cmd.amount(), cmd.currency(),
                    cmd.reason(), cmd.userId());

            PaymentPort adapter = factory.forProvider(payment.providerId());
            RefundResult providerResult = adapter.refund(new RefundRequest(
                    payment.providerTransactionId(),
                    cmd.amount(), cmd.currency(),
                    cmd.reason(), refund.id().toString()));

            if (providerResult.isSuccess()) {
                refund.markCompleted(providerResult.providerRefundId());
                payment.recordRefund(cmd.amount());
            } else {
                refund.markFailed(providerResult.errorCode(), providerResult.errorMessage());
                throw new RefundFailedException(providerResult.errorMessage());
            }

            refundRepository.save(refund);
            paymentRepository.save(payment);
            outbox.saveAll(payment.flushEvents());
            outbox.saveAll(refund.flushEvents());

            audit.log("payment.refund", cmd.userId(),
                    cmd.paymentId().toString(),
                    Map.of("amount", cmd.amount(), "refund_id", refund.id()));

            return RefundResult.success(refund);
        });
    }
}
```

### 6.3. RefundAuthorizationService

```java
@Component
@RequiredArgsConstructor
public class RefundAuthorizationService {

    private final PermissionResolver permissionResolver;

    public void authorize(UUID userId, BigDecimal amount) {
        Set<String> permissions = permissionResolver.resolve(userId);

        if (permissions.contains("payments:refund:full")) {
            return;
        }

        Optional<BigDecimal> limit = findLimit(permissions, "payments:refund:partial:limit:");
        if (limit.isEmpty()) {
            throw new ForbiddenException("No refund permission");
        }
        if (amount.compareTo(limit.get()) > 0) {
            throw new ForbiddenException(
                    "Refund amount exceeds your limit: " + limit.get());
        }
    }

    private Optional<BigDecimal> findLimit(Set<String> perms, String prefix) {
        return perms.stream()
                .filter(p -> p.startsWith(prefix))
                .map(p -> new BigDecimal(p.substring(prefix.length())))
                .max(BigDecimal::compareTo);
    }
}
```

### 6.4. Fraud check service

```java
@Component
@RequiredArgsConstructor
public class FraudCheckService {

    private final RefundRepository refundRepository;
    private final RedisTemplate<String, String> redis;

    public FraudCheckResult checkRefund(RefundCommand cmd, Payment payment) {
        // Rule 1: amount validation (zaten state machine'de var, double check)
        BigDecimal available = payment.amount().subtract(payment.refundedAmount());
        if (cmd.amount().compareTo(available) > 0) {
            return FraudCheckResult.blocked("Amount exceeds refundable balance");
        }

        // Rule 2: rate limit (10 min içinde max 3 refund initiate)
        String key = "fraud:refund:user:" + cmd.userId();
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofMinutes(10));
        }
        if (count != null && count > 3) {
            return FraudCheckResult.blocked("Rate limit exceeded");
        }

        // Rule 3: aynı payment için 5+ refund attempt
        long historicalAttempts = refundRepository.countByPaymentId(cmd.paymentId());
        if (historicalAttempts >= 5) {
            return FraudCheckResult.blocked("Too many refund attempts on this payment");
        }

        return FraudCheckResult.allowed();
    }
}
```

### 6.5. Saga compensation refund (Temporal activity)

```java
@Service
@RequiredArgsConstructor
public class FinanceActivitiesImpl implements FinanceActivities {

    private final RefundUseCase refundUseCase;
    private final PaymentRepository paymentRepository;
    private final SystemActorRegistry systemActors;

    @Override
    public void refundPayment(UUID paymentId, String reason) {
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        BigDecimal balance = payment.amount().subtract(payment.refundedAmount());
        if (balance.compareTo(BigDecimal.ZERO) <= 0) {
            return; // already fully refunded
        }

        UUID systemUserId = systemActors.findOrCreate("saga-compensation");
        String idempotencyKey = "saga-refund-" + paymentId;

        refundUseCase.refund(idempotencyKey, new RefundCommand(
                paymentId, systemUserId, payment.tenantId(),
                balance, payment.currency(),
                "[SAGA] " + reason));
    }
}
```

## 7. Dikkat edilecek tuzaklar

- **Refund yetkisini default açma**. Hiçbir user default refund yapamamalı. Explicit permission.
- **Amount validation atlama**. Refund > captured = balance bug → check her zaman aggregate'te.
- **Audit log eksikliği**. Her refund attempt audit log'a; başarısız da dahil.
- **Idempotency-Key eksik**. Network retry çift refund yapabilir → çifte iade.
- **Test environment'ta canlı kart**. Sandbox kullan; canlı kart test = legal risk.
- **Saga compensation refund'da idempotency unutma**. Same workflow retry edilirse çift refund olmaz.
- **Refund window aşımı sonrası bug**. Provider 60 gün sonrası reject ederse adapter graceful error dönmeli.
- **Multi-currency mismatch**. Refund currency != original currency = banka reject. Original'ı kullan.
- **Pending refund'ı CAPTURE saymak**. Refund state machine'i ayrı; PENDING → COMPLETED/FAILED.
- **Chargeback ile refund karıştırma**. Chargeback ayrı workflow; system'in inisiyatifinde değil, banka'nın.
- **Customer notification atlamak**. Refund tamamlandığında müşteriye email/SMS gönder.
- **State machine guard atlama**. `Captured → Refunded` izinli, ama `Failed → Refunded` izinsiz; enforce et.

## 8. Diğer konularla ilişkisi

- [Payment Adapter Pattern](./01-payment-adapter-pattern.md)
- [Bank Virtual POS](./02-bank-virtual-pos-routing.md)
- [Payment State Machine](./03-payment-state-machine.md) — refund transitions
- [Workflow Temporal](../workflow-temporal) — saga compensation
- [Audit Log](../security-compliance/audit-log-design)
- [RBAC + Permission](../authentication-authorization)
- [Notification](../notification) — refund email/SMS

## 9. Daha derine inmek için

- Stripe — [Refunds API](https://docs.stripe.com/refunds)
- Iyzico — [Refund API](https://dev.iyzipay.com/tr/api/iadeler)
- PCI DSS — [Refund security guidance](https://www.pcisecuritystandards.org/)
- Microsoft — [Compensating Transaction Pattern](https://learn.microsoft.com/en-us/azure/architecture/patterns/compensating-transaction)
- Araştırma keyword'leri: `partial refund implementation pattern`, `payment refund fraud detection`, `saga compensation refund automatic`, `chargeback vs refund difference`

## 10. Sözlük

- **Refund** — Tahsil edilmiş ödemenin müşteriye geri ödenmesi.
- **Partial refund** — Tahsil edilen tutarın bir kısmının iadesi.
- **Full refund** — Tahsil edilen tutarın tamamının iadesi.
- **Void** — Capture'dan önce authorization'ın iptal edilmesi; para hiç tahsil edilmedi.
- **Chargeback** — Müşterinin bankası aracılığıyla zorla geri istemesi; mağaza onayı yok.
- **Settlement** — Provider'dan mağaza hesabına paranın aktarılması (T+1/T+2).
- **Refund window** — Provider'ın refund kabul ettiği süre.
- **Saga compensation** — Workflow'da bir adım başarısız olunca önceki adımları geri almak.
- **4-eyes principle** — Yüksek riskli işlemde iki onaylayıcının olması gerekliliği.
- **Fraud check** — Şüpheli işlem tespit eden kontroller.
- **Idempotency-Key** — Aynı işlemi çift yapmamak için client header.
- **Audit log** — "Kim ne zaman ne yaptı" append-only log.
