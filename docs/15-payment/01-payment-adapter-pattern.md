---
title: Payment Provider Adapter Pattern
description: PaymentProvider interface, Iyzico/Param/BankPos adapter'ları, port + adapter yapısı, tenant config ile factory selection.
sidebar_position: 1
---

## Bu sayfa ne anlatıyor?

Lumix'in **ödeme provider seçiminin neden çoklu** olduğunu, **adapter pattern**'in bu çoklu sağlayıcı problemini nasıl çözdüğünü, **PaymentPort** interface'ini, **IyzicoAdapter / ParamAdapter / BankVposAdapter** somut implementasyonlarını ve **tenant config-driven factory selection**'ı anlatır. Her müşterinin farklı banka POS'u veya farklı payment provider kullanabilmesini sağlayan mimari karar burada.

## 1. Bu nedir? (Sıfırdan)

### 1.1. Günlük hayattan analoji

Bir **fatura ödeme kiosk**u düşün. Karşına çıkan kiosk birkaç tuşa sahip: "Akbank ile öde", "Garanti ile öde", "Iyzico ile öde". Aslında arkada **aynı işi** yapıyor — kart bilgisi al, banka API'sine gönder, sonucu döndür. Ama her bankanın API'si **farklı**. Kiosk yazılımı her birine ayrı kod yazmak yerine, **standart bir arayüz** ve **her bankaya bir adapter** kullanır.

Yazılımda buna **adapter pattern** denir: bir **interface (port)** tanımlanır, her dış sistem için bir **somut adapter** yazılır, core kod adapter'ı **factory** üzerinden alır.

### 1.2. Lumix problemi: farklı tenant farklı provider

Lumix bir SaaS — her müşteri okul/kurum:
- "Ömer Okulları" — Iyzico kullanmak istiyor (kolay onboarding)
- "Bilim İlkokulu" — Akbank sanal POS hesabı var, doğrudan onu kullanmak istiyor
- "ABC Eğitim" — Param ile anlaşmalı
- "Yatılı Lise" — İş Bankası VPOS

Her müşteri için ayrı bir Lumix instance yazmak deli işi. Bu yüzden **runtime'da provider seçen** bir mimari gerekir.

### 1.3. Adapter pattern komponentleri

```text
┌─────────────────────────────────┐
│   Domain (Hexagonal core)        │
│   - PaymentUseCase               │
│   - Payment aggregate            │
└──────────────┬───────────────────┘
               │ uses
               ▼
┌─────────────────────────────────┐
│   Port (interface)               │
│   PaymentPort                    │
└──────────────┬───────────────────┘
               │ implements
        ┌──────┼──────┬──────────┬─────────────┐
        ▼      ▼      ▼          ▼             ▼
   ┌────────┐ ┌────────┐ ┌──────────┐ ┌─────────────────┐
   │ Iyzico │ │ Param  │ │ Akbank   │ │ Garanti VPOS    │
   │ Adapter│ │ Adapter│ │ VPOS Adp │ │ Adapter         │
   └────────┘ └────────┘ └──────────┘ └─────────────────┘
```

Domain core hangi adapter kullanıldığını **bilmez**. Sadece `PaymentPort` ile konuşur.

## 2. Hangi problemi çözüyor?

### 2.1. Provider lock-in

Eğer tüm kod Iyzico SDK'sına bağımlıysa:
- Müşteri "ben Akbank kullanmak istiyorum" derse → tüm payment kodu yeniden yazılır
- Iyzico fiyat artışı yapar veya batar → migration kabusu
- Test için mock yapmak zor (network call'lar her yerde)

### 2.2. Multiple provider in production

Tek SaaS instance içinde birden fazla provider aynı anda aktif olmalı:
- Müşteri A → Iyzico
- Müşteri B → Akbank VPOS
- Müşteri C → Param

Provider seçimi **per-request runtime**.

### 2.3. Test edilebilirlik

Adapter arkasında **MockPaymentAdapter** ile testler hızlı çalışır. Network call yok, gerçek banka tetiklenmez.

### 2.4. Bağımsız evolve

Yeni provider eklemek → yeni adapter class. Domain hiç değişmez.

## 3. Nasıl çalışıyor? (Çalışma prensibi)

### 3.1. Port (interface) tanımı

```java
public interface PaymentPort {

    AuthorizationResult authorize(AuthorizationRequest request);

    CaptureResult capture(CaptureRequest request);

    RefundResult refund(RefundRequest request);

    PaymentStatus query(String providerTransactionId);

    void verifyCallback(CallbackPayload payload);

    PaymentProviderId providerId();
}
```

Bu interface **tüm provider'ların ortak yetenekleri**. Her provider:
- Authorize: kartın yetkilendirilmesi (3D Secure dahil)
- Capture: yetkilendirilmiş ödemeyi tahsil etme
- Refund: iade
- Query: ödeme durumu sorgu
- Verify callback: provider'dan gelen webhook signature doğrulama

### 3.2. Adapter implementation

Iyzico için:

```java
@Component
public class IyzicoAdapter implements PaymentPort {

    private final IyzipayClient client; // Iyzico Java SDK

    @Override
    public AuthorizationResult authorize(AuthorizationRequest request) {
        CreatePaymentRequest req = new CreatePaymentRequest();
        req.setPrice(request.amount().toString());
        req.setPaidPrice(request.amount().toString());
        req.setCurrency(request.currency().getCurrencyCode());
        // ... Iyzico spesifik field mapping
        Payment iyzicoPayment = Payment.create(req, iyzicoOptions());

        if ("success".equals(iyzicoPayment.getStatus())) {
            return AuthorizationResult.success(
                    iyzicoPayment.getPaymentId(),
                    iyzicoPayment.getAuthCode());
        }
        return AuthorizationResult.failed(iyzicoPayment.getErrorMessage());
    }

    @Override
    public PaymentProviderId providerId() {
        return PaymentProviderId.IYZICO;
    }
    // ... diğer methodlar
}
```

Param için ayrı:

```java
@Component
public class ParamAdapter implements PaymentPort {
    // Param SOAP/REST API'sini çağıran kod
    @Override
    public PaymentProviderId providerId() {
        return PaymentProviderId.PARAM;
    }
}
```

Banka VPOS:

```java
@Component
public class AkbankVposAdapter implements PaymentPort {
    @Override
    public PaymentProviderId providerId() {
        return PaymentProviderId.AKBANK_VPOS;
    }
}
```

### 3.3. Factory + tenant config

```java
@Component
public class PaymentAdapterFactory {

    private final Map<PaymentProviderId, PaymentPort> adapters;
    private final TenantPaymentProviderConfigRepository configRepository;

    public PaymentAdapterFactory(List<PaymentPort> allAdapters,
                                  TenantPaymentProviderConfigRepository repo) {
        this.adapters = allAdapters.stream()
                .collect(Collectors.toMap(PaymentPort::providerId, Function.identity()));
        this.configRepository = repo;
    }

    public PaymentPort forTenant(UUID tenantId) {
        TenantPaymentProviderConfig config = configRepository.findActiveByTenantId(tenantId)
                .orElseThrow(() -> new NoPaymentProviderException(tenantId));

        PaymentPort adapter = adapters.get(config.providerId());
        if (adapter == null) {
            throw new IllegalStateException("No adapter for provider: " + config.providerId());
        }
        return adapter;
    }
}
```

Spring `List<PaymentPort>` injection — tüm `@Component` adapter'lar otomatik gelir. Yeni provider eklemek = yeni `@Component` yazmak; factory'yi değiştirmek gerekmez.

### 3.4. Per-request akış

```text
1. Frontend → POST /payments { invoice_id, card_token }
2. Controller → PaymentUseCase.initiate(invoiceId, ...)
3. UseCase → factory.forTenant(currentTenantId) → IyzicoAdapter
4. UseCase → adapter.authorize(request)
5. Adapter → Iyzico HTTP API
6. Result → UseCase → response
```

PaymentUseCase **hangi provider** kullanıldığını bilmez. Sadece `PaymentPort` ile konuşur.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Provider listesi (MVP)

| Provider | Tip | Kullanım senaryosu |
|---|---|---|
| **Iyzico** | Payment gateway | Hızlı onboarding, Iyzico hesabı yeterli |
| **Param** | Payment gateway | Türkiye yerel, esnek satıcı ayarları |
| **Akbank VPOS** | Banka sanal POS | Müşteri Akbank ile direkt sözleşmeli |
| **Garanti VPOS** | Banka sanal POS | Garanti BBVA ile direkt sözleşme |
| **İş Bankası VPOS** | Banka sanal POS | İş Bankası kontratı |
| **Mock** | Test | Local dev + E2E |

Provider sayısı zamanla artar; adapter pattern yeni provider eklemeyi 1-2 günlük iş yapar.

### 4.2. Tenant config tablosu

```sql
CREATE TABLE tenant_payment_provider_config (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    provider_id VARCHAR(64) NOT NULL,    -- 'IYZICO', 'AKBANK_VPOS' vs.
    is_active BOOLEAN NOT NULL DEFAULT true,
    credentials_secret_path TEXT NOT NULL, -- Vault path: 'kv/tenant/abc-123/payment/iyzico'
    config_json JSONB NOT NULL,            -- Provider-specific config (callback URL, merchant id, vs.)
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deactivated_at TIMESTAMPTZ,
    UNIQUE (tenant_id, provider_id)
);

CREATE INDEX idx_tppc_tenant_active ON tenant_payment_provider_config(tenant_id)
    WHERE is_active = true;
```

**Credentials Vault'ta**, DB'de path tutulur. DB compromise olsa bile credential leak olmaz.

### 4.3. Provider switch akışı

Müşteri "ben Akbank'a geçmek istiyorum" derse:

1. Admin panel → "Akbank VPOS config ekle" → credentials Vault'a
2. `INSERT tenant_payment_provider_config (provider_id='AKBANK_VPOS', is_active=true)`
3. Mevcut Iyzico config → `is_active=false`
4. **Devam eden** payment transaction'lar Iyzico ile tamamlanır (provider per-payment kaydedilir)
5. **Yeni** payment'lar Akbank ile başlar

Provider değişimi **kesintisiz**.

### 4.4. Provider-per-payment

```sql
CREATE TABLE payments (
    payment_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    invoice_id UUID NOT NULL,
    provider_id VARCHAR(64) NOT NULL,         -- KAYDET
    provider_transaction_id VARCHAR(255),
    amount NUMERIC(15, 2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'TRY',
    state VARCHAR(32) NOT NULL,                -- 'PENDING', 'AUTHORIZED', ...
    -- ...
);
```

`provider_id` kayıtlı; refund veya query'de aynı provider üzerinden iş yapılır (capture sırasında Iyzico olan bir payment refund'ı Akbank'tan yapılamaz).

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. Düşünülen alternatifler

| Alternatif | Neden elendi |
|---|---|
| **Tek provider lock-in** | Müşteri çeşitliliği kısıtlanır, lock-in iş riski |
| **Stripe + global** | Türkiye'de yaygın değil, banka VPOS senaryosunu karşılamıyor |
| **Provider per-deployment (build-time)** | Çoklu müşteriyi tek instance ile besleyemez |
| **Strategy pattern (no adapter)** | Adapter ≈ Strategy; aynı şey. İsim farkı, mantık aynı |

### 5.2. Trade-off

- **Boilerplate**: Her provider için tam interface implementasyonu. 5 provider = 5 adapter. Yönetilebilir.
- **Common feature subset**: Tüm provider'lar tüm operasyonları desteklemeyebilir (örn. partial refund). Adapter `UnsupportedOperationException` atabilir veya capability flag döner.
- **Field mapping cost**: Her provider'ın API'si farklı; mapping kodu maintenance yükü.
- **Test matrix**: Her adapter için ayrı integration test. Mock veya sandbox kullanılır.

### 5.3. Ne değişirse kararı tekrar gözden geçiririz?

- Tüm müşteriler tek provider'a geçerse simplifikasyon mümkün ama gelecek esnekliği kaybedilir. **Adapter pattern kalır** çünkü test/mock için gerekli.
- Provider sayısı 20+ olursa **plugin registry** (runtime class loading) düşünülebilir.

## 6. Pratik örnek

### 6.1. Domain modeli

```java
public record AuthorizationRequest(
        UUID paymentId,
        UUID tenantId,
        BigDecimal amount,
        Currency currency,
        String cardToken,        // PCI scope dışında tokenize
        String callbackUrl,
        Map<String, String> metadata
) {}

public sealed interface AuthorizationResult {

    record Success(String providerTxId, String authCode) implements AuthorizationResult {}
    record ThreeDSecureRequired(String redirectUrl) implements AuthorizationResult {}
    record Failed(String errorCode, String errorMessage) implements AuthorizationResult {}
}
```

### 6.2. PaymentPort interface

```java
package com.lumix.finance.application.port;

import java.util.UUID;

public interface PaymentPort {
    PaymentProviderId providerId();

    AuthorizationResult authorize(AuthorizationRequest request);

    CaptureResult capture(CaptureRequest request);

    RefundResult refund(RefundRequest request);

    PaymentStatus query(String providerTransactionId);

    CallbackVerification verifyCallback(CallbackPayload payload);
}

public enum PaymentProviderId {
    IYZICO,
    PARAM,
    AKBANK_VPOS,
    GARANTI_VPOS,
    ISBANK_VPOS,
    MOCK
}
```

### 6.3. Iyzico adapter (örnek)

```java
package com.lumix.finance.adapter.payment;

import com.iyzipay.Options;
import com.iyzipay.model.Payment;
import com.iyzipay.request.CreatePaymentRequest;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IyzicoAdapter implements PaymentPort {

    private final IyzicoCredentialResolver credentialResolver;
    private final IyzicoRequestMapper requestMapper;

    @Override
    public PaymentProviderId providerId() {
        return PaymentProviderId.IYZICO;
    }

    @Override
    public AuthorizationResult authorize(AuthorizationRequest request) {
        IyzicoCredentials creds = credentialResolver.resolve(request.tenantId());
        Options options = new Options();
        options.setApiKey(creds.apiKey());
        options.setSecretKey(creds.secretKey());
        options.setBaseUrl(creds.baseUrl());

        CreatePaymentRequest iyzicoReq = requestMapper.toIyzicoRequest(request);
        Payment payment = Payment.create(iyzicoReq, options);

        if ("failure".equals(payment.getStatus())) {
            return new AuthorizationResult.Failed(
                    payment.getErrorCode(), payment.getErrorMessage());
        }
        if (payment.getThreeDSHtmlContent() != null) {
            return new AuthorizationResult.ThreeDSecureRequired(payment.getThreeDSHtmlContent());
        }
        return new AuthorizationResult.Success(
                payment.getPaymentId(), payment.getAuthCode());
    }

    @Override
    public CaptureResult capture(CaptureRequest request) {
        // Iyzico için authorize + capture genelde tek adımda. Pre-auth ayrı flow.
        // ...
    }

    @Override
    public RefundResult refund(RefundRequest request) {
        // ...
    }

    @Override
    public PaymentStatus query(String providerTxId) {
        // ...
    }

    @Override
    public CallbackVerification verifyCallback(CallbackPayload payload) {
        // Iyzico HMAC-SHA1 signature doğrulama
        // ...
    }
}
```

### 6.4. Factory + use case

```java
@Component
@RequiredArgsConstructor
public class PaymentAdapterFactory {

    private final List<PaymentPort> allAdapters;
    private final TenantPaymentProviderConfigRepository configRepo;
    private final Map<PaymentProviderId, PaymentPort> byProvider = new ConcurrentHashMap<>();

    @PostConstruct
    void register() {
        for (PaymentPort adapter : allAdapters) {
            byProvider.put(adapter.providerId(), adapter);
        }
    }

    public PaymentPort forTenant(UUID tenantId) {
        TenantPaymentProviderConfig config = configRepo.findActiveByTenantId(tenantId)
                .orElseThrow(() -> new NoActiveProviderException(tenantId));
        PaymentPort adapter = byProvider.get(config.providerId());
        if (adapter == null) {
            throw new IllegalStateException("No adapter for: " + config.providerId());
        }
        return adapter;
    }

    public PaymentPort forProvider(PaymentProviderId providerId) {
        PaymentPort adapter = byProvider.get(providerId);
        if (adapter == null) {
            throw new IllegalStateException("No adapter for: " + providerId);
        }
        return adapter;
    }
}

@Service
@RequiredArgsConstructor
public class PaymentInitiationUseCase {

    private final PaymentAdapterFactory factory;
    private final PaymentRepository paymentRepository;

    @Transactional
    public PaymentInitiationResult initiate(InitiatePaymentCommand cmd) {
        PaymentPort adapter = factory.forTenant(cmd.tenantId());

        Payment payment = Payment.create(cmd, adapter.providerId());
        paymentRepository.save(payment);

        AuthorizationResult result = adapter.authorize(new AuthorizationRequest(
                payment.id(), cmd.tenantId(), cmd.amount(), cmd.currency(),
                cmd.cardToken(), cmd.callbackUrl(), cmd.metadata()));

        return switch (result) {
            case AuthorizationResult.Success s -> {
                payment.markAuthorized(s.providerTxId(), s.authCode());
                paymentRepository.save(payment);
                yield PaymentInitiationResult.authorized(payment.id());
            }
            case AuthorizationResult.ThreeDSecureRequired t3d -> {
                payment.markThreeDSecurePending();
                paymentRepository.save(payment);
                yield PaymentInitiationResult.threeDSecure(t3d.redirectUrl());
            }
            case AuthorizationResult.Failed f -> {
                payment.markFailed(f.errorCode(), f.errorMessage());
                paymentRepository.save(payment);
                yield PaymentInitiationResult.failed(f.errorMessage());
            }
        };
    }
}
```

## 7. Dikkat edilecek tuzaklar

- **Provider lock-in oluşturma**. Domain'de provider-specific tip (`com.iyzipay.model.*`) kullanma. Sadece adapter'da.
- **Common interface'i çok kısıtlı tutma**. Tüm provider'ların farklı feature'larına yer açacak şekilde tasarla (capability flag).
- **Credential'ı DB'de plain saklama**. Vault path tut; lookup runtime'da yap.
- **Provider değişimini DB-only yapma**. Hangi provider tarafından "tutulan" ödeme varsa onunla refund yap; `payments.provider_id` kayıtlı olmalı.
- **Network timeout yok**. Her provider call için timeout (5-30 sn) ayarla; aksi halde 30sn'lik takılma kullanıcıyı kaçırır.
- **Retry safe değilse retry**. Authorize idempotent değildir → çift charge riski. Idempotency-Key kullan.
- **Test'te gerçek API çağırma**. Sandbox/mock kullan. Provider'lar genelde sandbox env sunar.
- **Adapter business logic koyma**. State machine, business invariant domain'de. Adapter sadece map + call.
- **Audit eksikliği**. Her authorize/capture/refund denemesi `audit_logs`'a düşmeli.
- **Callback verify atlama**. Provider webhook signature doğrulanmadan trust etme. Spoofing riski.
- **Single Vault credential paylaşma**. Tenant başına ayrı path; cross-tenant leak engellemek için.

## 8. Diğer konularla ilişkisi

- [Bank Virtual POS Routing](./02-bank-virtual-pos-routing.md) — Türkiye banka VPOS detayı
- [Payment State Machine](./03-payment-state-machine.md) — Pending → Authorized → Captured akışı
- [Refund Handling](./04-refund-handling.md) — İade kuralları
- [Hexagonal Architecture](../architecture-patterns) — port + adapter prensibi
- [Notification](../notification) — adapter pattern aynı mantık
- [Audit Log](../security-compliance/audit-log-design) — payment audit

## 9. Daha derine inmek için

- Iyzico — [Documentation](https://dev.iyzipay.com/)
- Param — [API Docs](https://dev.param.com.tr/)
- Akbank Sanal POS — [Documentation](https://www.akbank.com/tr/sanal-pos)
- Garanti BBVA Virtual POS — [Documentation](https://www.garantibbvapos.com/)
- Adapter Pattern — [Refactoring Guru](https://refactoring.guru/design-patterns/adapter)
- Araştırma keyword'leri: `payment gateway adapter pattern multi-tenant`, `iyzico java sdk integration`, `turkish virtual pos integration`, `hexagonal architecture payment`

## 10. Sözlük

- **Adapter pattern** — Farklı interface'leri ortak bir interface arkasında soyutlayan tasarım pattern'i.
- **Port** — Hexagonal architecture'da domain'in dış dünya ile konuştuğu interface.
- **PaymentPort** — Lumix'in payment provider interface'i.
- **Provider** — Ödeme sağlayıcı (Iyzico, Param, banka VPOS, vs.).
- **VPOS (Virtual POS)** — Bankaların kart işlem API'leri.
- **Authorize** — Kartın yetkilendirilmesi; para ayrılır ama tahsil edilmez.
- **Capture** — Yetkilendirilmiş ödemenin tahsil edilmesi.
- **Refund** — İade.
- **3D Secure** — Kart sahibinin SMS veya banking app ile doğrulanması.
- **Idempotency-Key** — Duplicate işlem engellemek için client header.
- **Tenant payment provider config** — Tenant'ın hangi provider'ı kullandığını tutan tablo.
- **Factory** — Runtime'da uygun adapter'ı seçen component.
