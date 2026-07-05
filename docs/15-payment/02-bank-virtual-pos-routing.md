---
title: Banka Sanal POS (VPOS) ve Routing
description: Türkiye banka sanal POS modeli, 3D Secure, Visa/Mastercard, BKM, Akbank/Garanti/İş Bankası VPOS, provider config table ve routing logic.
sidebar_position: 2
---

## Bu sayfa ne anlatıyor?

Türkiye'de **banka sanal POS** sisteminin nasıl çalıştığını, **3D Secure** zorunluluğunu, **BKM** rolünü, **major bankaların VPOS API'lerini**, Lumix'te **tenant payment provider config** ile **routing logic**'in nasıl çalıştığını ve VPOS adapter'ın spesifik tasarım kararlarını anlatır. Önceki sayfa adapter pattern'in kavramsal yapısını verdi; bu sayfa **Türkiye banka VPOS'larına özgü spesifik detaylar**.

## 1. Bu nedir? (Sıfırdan)

### 1.1. Türkiye ödeme ekosistemi — günlük hayattan analoji

Bir mağazada kart ile öderken üç oyuncu var:
- **Müşteri** (kart sahibi)
- **Mağaza (üye işyeri)**
- **Banka (üye işyerinin bankası, "acquiring bank")**

Online'a geçince fiziksel POS cihazı yerine **virtual POS (VPOS)** devreye girer. Mağazanın e-ticaret sitesi banka VPOS API'sini çağırır:

```text
1. Müşteri kart bilgilerini siteye girer
2. Site → banka VPOS API → kart provizyonu
3. Banka kartı sorgular (Visa/Mastercard network üzerinden issuing bank'a)
4. 3D Secure → müşteriye SMS veya banking app push
5. Müşteri onaylar → işlem tamamlanır
6. Banka mağazaya parayı yatırır (T+1 veya T+2 gün)
```

VPOS, mağaza ile banka arasındaki yazılım arayüzüdür.

### 1.2. BKM ve standardizasyon

**BKM (Bankalararası Kart Merkezi)** Türkiye'deki kart sistemleri ortak altyapısıdır. Roller:
- Kartlar arası takas (clearing)
- Standartlaştırma (3D Secure protokolü, BKM Express)
- Fraud detection ortak servis

Her banka kendi VPOS API'sini sunar — BKM ortak bir API sunmaz. Bu yüzden **her banka için ayrı integration** gerekir.

### 1.3. 3D Secure (mandatory)

**3D Secure** — Visa "3-Domain Secure", Mastercard "Identity Check". Üç domain:
1. Issuer domain (kart sahibinin bankası)
2. Acquirer domain (üye işyerinin bankası)
3. Interoperability domain (Visa/Mastercard network)

Türkiye'de **3D Secure zorunlu** (BKM ve BDDK regülasyonu) — non-3DS işlem yapılabilir ama özel sözleşme + risk üye işyerinde.

3DS akışı:
```text
1. Mağaza VPOS'a authorize çağrısı
2. VPOS issuer bank'a yönlendirir
3. Issuer bank: SMS OTP / banking app push
4. Müşteri kod girer veya onaylar
5. Callback → mağaza success/fail bildirimi
```

Lumix tüm tenant'lar için **3D Secure zorunlu**.

## 2. Hangi problemi çözüyor?

### 2.1. Müşteri banka tercihi

- Bazı okullar uzun yıllardır Akbank ile çalışır, ekstra Iyzico sözleşmesi yapmak istemez
- Bazı müşteriler banka komisyon oranlarını pazarlık etmiş, doğrudan VPOS kullanmaları gerekiyor
- Bazı kurumlar her banka için ayrı IBAN'a yatırma istiyor (kâr merkezi bazında)

### 2.2. VPOS provider çeşitliliği

Türkiye'de major bankalar:
- Akbank
- Garanti BBVA
- İş Bankası
- Yapı Kredi
- Ziraat Bankası
- Halkbank
- VakıfBank
- TEB, Finansbank, ING vs.

Her birinin API'si farklı (SOAP, REST, XML payload, JSON payload, signature algoritması).

### 2.3. Routing decision

Hangi VPOS'un çağrılacağına karar veren mekanizma şart. Lumix bunu `tenant_payment_provider_config` tablosu + factory ile yapar.

## 3. Nasıl çalışıyor? (Çalışma prensibi)

### 3.1. VPOS request akışı (3D Secure)

```text
Client (browser)         file-service              VPOS (örn. Akbank)        Issuer Bank
       │                       │                          │                       │
       │ POST /payments        │                          │                       │
       ├──────────────────────►│                          │                       │
       │                       │   POST /3dsinit          │                       │
       │                       ├─────────────────────────►│                       │
       │                       │                          │                       │
       │                       │   { html_form }          │                       │
       │                       │◄─────────────────────────┤                       │
       │ { redirect_url +      │                          │                       │
       │   html_form }         │                          │                       │
       │◄──────────────────────┤                          │                       │
       │                       │                          │                       │
       │ Browser submits form to issuer 3DS page          │                       │
       ├───────────────────────────────────────────────────────────────────────────►
       │   SMS OTP entered, OK                                                    │
       │◄──────────────────────────────────────────────────────────────────────────┤
       │                       │                          │                       │
       │ Browser redirects to callback (mağaza domain)    │                       │
       ├──────────────────────►│                          │                       │
       │   { paResponse, ... } │                          │                       │
       │                       │  Verify callback signature                       │
       │                       │  POST /authorize (with 3DS auth proof)           │
       │                       ├─────────────────────────►│                       │
       │                       │                          │ Visa/MC network call  │
       │                       │                          ├──────────────────────►│
       │                       │   auth result            │                       │
       │                       │◄─────────────────────────┤                       │
       │                       │                          │                       │
       │ { success/fail }      │                          │                       │
       │◄──────────────────────┤                          │                       │
```

### 3.2. Routing logic — factory pattern

```text
Request → PaymentUseCase
  → factory.forTenant(currentTenantId)
    → SELECT * FROM tenant_payment_provider_config
       WHERE tenant_id = ? AND is_active = true
    → provider_id = 'AKBANK_VPOS'
    → return AkbankVposAdapter
  → adapter.authorize(request)
```

Tenant başına **tek active provider** kuralı (`is_active=true` row tek). Switch atomik:
- Yeni config insert + is_active=true
- Eski config update set is_active=false
- Tek transaction, lock ile

### 3.3. Provider config JSON

Her VPOS farklı parametreler ister. `config_json` JSONB:

```json
// Akbank VPOS örnek
{
  "merchant_id": "100100000",
  "terminal_id": "00100001",
  "vpos_url": "https://www.sanalakpos.com/fim/api",
  "callback_url": "https://omer-okullari.lumix.io/api/v1/payments/akbank/callback",
  "currency_code": "949",
  "lang": "tr",
  "txn_type": "Auth",
  "store_type": "3d_pay"
}

// Garanti VPOS örnek
{
  "merchant_id": "7000679",
  "terminal_id": "30691317",
  "terminal_provusername": "PROVAUT",
  "terminal_provpassword": "VAULT_REFERENCE",
  "vpos_url": "https://sanalposprov.garanti.com.tr/VPServlet",
  "callback_url": "https://omer-okullari.lumix.io/api/v1/payments/garanti/callback",
  "store_type": "3D"
}
```

Sensitive credential (password, secret key) **Vault**'ta; config_json sadece path referansı tutar.

### 3.4. Callback verification

Her banka kendi imzalama algoritmasını kullanır:
- Akbank — `mac` hash (SHA-1 / SHA-256, sıralı concatenation)
- Garanti — `mac` HMAC-SHA1
- İş Bankası — `mpiTransactionInfo` özel format

Adapter `verifyCallback()` her banka için doğru algoritmayı çalıştırır. **Doğrulanmayan callback REDDEDİLİR** — spoofing önemli risk.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Desteklenen VPOS'lar (MVP)

| Banka | Adapter | Status |
|---|---|---|
| Akbank | `AkbankVposAdapter` | Planlanan |
| Garanti BBVA | `GarantiVposAdapter` | Planlanan |
| İş Bankası | `IsbankVposAdapter` | Planlanan |
| Yapı Kredi | — | İhtiyaç olunca |
| Ziraat | — | İhtiyaç olunca |

Plus Iyzico / Param payment gateway adapter'ları (gateway, banka değil ama interface aynı).

### 4.2. Callback endpoint convention

Her provider için ayrı callback endpoint:

```text
POST /api/v1/payments/akbank/callback
POST /api/v1/payments/garanti/callback
POST /api/v1/payments/isbank/callback
POST /api/v1/payments/iyzico/callback
```

Lumix prensibi: callback URL tenant config'ten gelir; provider adapter sadece doğru endpoint'i match eder.

### 4.3. Routing decision tree

```text
incoming payment request
  │
  ├─ tenant_id var mı?
  │   └─ no → 400 BadRequest
  │
  ├─ tenant_payment_provider_config aktif var mı?
  │   └─ no → 503 ServiceUnavailable, alert ops
  │
  ├─ provider adapter registered mı?
  │   └─ no → 500 InternalError, alert
  │
  ├─ Vault credential erişilebilir mi?
  │   └─ no → 503, retry
  │
  └─ adapter.authorize(request)
      ├─ Success → state=AUTHORIZED
      ├─ 3DS required → state=THREE_D_PENDING
      └─ Failed → state=FAILED + audit
```

### 4.4. Multi-provider fallback (opsiyonel feature)

İleride: bir VPOS down olursa secondary'ye fallback. MVP'de yok, business decision.

```sql
ALTER TABLE tenant_payment_provider_config
  ADD COLUMN priority INTEGER DEFAULT 0;  -- 0 = primary, 1 = fallback
```

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. Banka VPOS vs payment gateway

| Konu | Banka VPOS | Payment Gateway (Iyzico/Param) |
|---|---|---|
| Komisyon | Banka ile pazarlık, genelde düşük | Sabit oran, daha yüksek |
| Entegrasyon kompleksitesi | Yüksek (her banka farklı API) | Tek API |
| Onboarding | Banka ile sözleşme, KYC | Online kayıt, kısa |
| Settlement | Direkt müşteri IBAN'ına | Gateway'de tutulur, sonra ödeme |
| Multi-bank acceptance | Bir bankaya bağlı | Tüm kartlar aynı API |

Lumix her ikisini de destekler — müşteri tercihine göre.

### 5.2. SOAP vs REST

Bazı banka VPOS'ları hâlâ SOAP/XML. Spring `WebClient` + JAXB ile yönetilir. Adapter pattern bunu sakladığı için core kod fark etmez.

### 5.3. Test edilebilirlik

Her bankanın test environment'ı:
- Akbank: `sanalakpos.com/fim/api?test=1` (test merchant)
- Garanti: `sanalposprovtest.garanti.com.tr` (sanal POS test)
- İş Bankası: test endpoint + test card

Lumix CI'da bu sandbox'lar **integration test** olarak çalıştırılır. Production credential ile asla CI'da çalıştırılmaz.

### 5.4. Trade-off

- **Maintenance**: Her banka VPOS API'sinde değişim olursa adapter güncelleme. Banka sıkça değiştirmez ama TLS upgrade gibi şeyler olur.
- **Documentation eksikliği**: Bankaların VPOS doc'ları çoğu zaman PDF, eski. Community blog post'ları + test environment çok yardım eder.

## 6. Pratik örnek

### 6.1. Tenant payment provider config DDL + örnek row

```sql
INSERT INTO tenant_payment_provider_config (
    id, tenant_id, provider_id, is_active, credentials_secret_path, config_json
) VALUES (
    gen_random_uuid(),
    'a1b2c3d4-...',                                    -- tenant_id
    'AKBANK_VPOS',
    true,
    'kv/tenant/a1b2c3d4/payment/akbank-vpos',           -- Vault path
    '{
        "merchant_id": "100100000",
        "terminal_id": "00100001",
        "vpos_url": "https://www.sanalakpos.com/fim/api",
        "callback_url": "https://omer.lumix.io/api/v1/payments/akbank/callback",
        "currency_code": "949",
        "lang": "tr",
        "store_type": "3d_pay"
    }'::jsonb
);
```

### 6.2. Vault'tan credential resolver

```java
@Component
public class VaultCredentialResolver {

    private final VaultTemplate vaultTemplate;

    public VposCredentials resolve(String secretPath) {
        VaultResponse response = vaultTemplate.read(secretPath);
        if (response == null || response.getData() == null) {
            throw new CredentialNotFoundException(secretPath);
        }
        Map<String, Object> data = response.getData();
        return new VposCredentials(
                (String) data.get("provauth_user"),
                (String) data.get("provauth_password"),
                (String) data.get("store_key"));
    }
}
```

### 6.3. Akbank VPOS adapter (özet)

```java
package com.lumix.finance.adapter.payment.akbank;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
public class AkbankVposAdapter implements PaymentPort {

    private final RestTemplate restTemplate;
    private final TenantPaymentConfigRepository configRepo;
    private final VaultCredentialResolver credentialResolver;
    private final AkbankSignatureCalculator signer;

    @Override
    public PaymentProviderId providerId() {
        return PaymentProviderId.AKBANK_VPOS;
    }

    @Override
    public AuthorizationResult authorize(AuthorizationRequest request) {
        TenantPaymentProviderConfig cfg = configRepo.findActive(request.tenantId())
                .filter(c -> c.providerId() == PaymentProviderId.AKBANK_VPOS)
                .orElseThrow();

        VposCredentials creds = credentialResolver.resolve(cfg.credentialsSecretPath());
        AkbankConfig akbank = AkbankConfig.fromJson(cfg.configJson());

        String orderId = request.paymentId().toString();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("clientid", akbank.merchantId());
        form.put("oid", orderId);
        form.put("amount", request.amount().toPlainString());
        form.put("currency", akbank.currencyCode());
        form.put("okUrl", akbank.callbackUrl() + "?status=ok");
        form.put("failUrl", akbank.callbackUrl() + "?status=fail");
        form.put("rnd", UUID.randomUUID().toString());
        form.put("storetype", "3d_pay");
        form.put("hash", signer.computeHash(form, creds.storeKey()));

        // 3D Secure HTML form return (browser submits to bank)
        String htmlForm = buildHtmlForm(akbank.vposUrl(), form);
        return new AuthorizationResult.ThreeDSecureRequired(htmlForm);
    }

    @Override
    public CallbackVerification verifyCallback(CallbackPayload payload) {
        Map<String, String> params = payload.params();
        String received = params.get("HASH");
        String calculated = signer.computeReturnHash(params, /* storeKey */);
        if (!received.equals(calculated)) {
            return CallbackVerification.invalid("Hash mismatch");
        }
        if (!"Approved".equals(params.get("Response"))) {
            return CallbackVerification.failed(params.get("ErrMsg"));
        }
        return CallbackVerification.valid(
                params.get("oid"),       // orderId
                params.get("AuthCode"),
                params.get("ProcReturnCode"));
    }

    @Override
    public CaptureResult capture(CaptureRequest request) { /* ... */ }
    @Override
    public RefundResult refund(RefundRequest request) { /* ... */ }
    @Override
    public PaymentStatus query(String providerTxId) { /* ... */ }
}
```

### 6.4. Callback controller

```java
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentCallbackController {

    private final PaymentCallbackUseCase useCase;

    @PostMapping("/akbank/callback")
    public ResponseEntity<String> akbankCallback(
            @RequestParam Map<String, String> params,
            HttpServletRequest req) {

        CallbackPayload payload = new CallbackPayload(
                params, req.getHeader("X-Forwarded-For"));
        useCase.handleCallback(PaymentProviderId.AKBANK_VPOS, payload);
        return ResponseEntity.ok("OK");
    }

    @PostMapping("/garanti/callback")
    public ResponseEntity<String> garantiCallback(/* ... */) { /* ... */ }
}
```

### 6.5. Callback use case

```java
@Service
@RequiredArgsConstructor
public class PaymentCallbackUseCase {

    private final PaymentAdapterFactory factory;
    private final PaymentRepository repository;
    private final AuditLogger audit;
    private final FinanceEventPublisher events;

    @Transactional
    public void handleCallback(PaymentProviderId providerId, CallbackPayload payload) {
        PaymentPort adapter = factory.forProvider(providerId);
        CallbackVerification verification = adapter.verifyCallback(payload);

        if (!verification.isValid()) {
            audit.log("payment.callback.invalid", null, null,
                    verification.errorMessage());
            throw new InvalidCallbackException(verification.errorMessage());
        }

        UUID paymentId = UUID.fromString(verification.orderId());
        Payment payment = repository.findById(paymentId).orElseThrow();

        if (verification.isFailed()) {
            payment.markFailed(verification.errorCode(), verification.errorMessage());
            events.publishPaymentFailed(payment);
        } else {
            payment.markAuthorized(verification.providerTxId(), verification.authCode());
            events.publishPaymentAuthorized(payment);
        }

        repository.save(payment);
        audit.log("payment.callback.processed", null, paymentId.toString(), verification.toString());
    }
}
```

## 7. Dikkat edilecek tuzaklar

- **Callback signature verify atlama**. Hash/MAC doğrulanmadan trust = spoofing açığı.
- **Sensitive credential DB'de saklama**. Vault path tut; runtime resolve.
- **Hard-coded VPOS URL**. Test ve prod URL'leri config'ten gelmeli; CI/CD'de yanlış env'e prod URL gönderme.
- **Tenant config olmadan provider seçme**. Default provider = security risk + audit eksikliği.
- **Multiple active provider per tenant**. UNIQUE constraint koy `(tenant_id) WHERE is_active=true`.
- **Callback idempotency yok**. Aynı callback iki kez gelebilir (provider retry). Payment id ile lookup + state guard.
- **Logging'de kart bilgisi**. PAN, CVV ASLA log'a düşmemeli (PCI-DSS). Cardholder name, last 4 digit yeter.
- **Network timeout yok**. VPOS 30sn+ takılabilir; client timeout + retry policy şart.
- **Currency mismatch**. TRY = 949 (ISO 4217); banka API her zaman code expect eder. Hard-code etme.
- **3DS bypass**. Production'da non-3DS = compliance riski + chargeback artışı. 3DS zorunlu.
- **Test env'de prod callback URL**. Cross-env callback = data leak.
- **VPOS adapter'da business logic**. Adapter sadece protocol mapping yapar; state machine domain'de.

## 8. Diğer konularla ilişkisi

- [Payment Adapter Pattern](./payment-adapter-pattern) — port + adapter mantığı
- [Payment State Machine](./payment-state-machine) — Pending → Authorized → Captured
- [Refund Handling](./refund-handling) — iade akışı
- [Hexagonal Architecture](../02-architecture-patterns)
- [Vault](../security-compliance) — credential resolution
- [Audit Log](../security-compliance/audit-log-design) — payment audit zorunlu

## 9. Daha derine inmek için

- BKM — [Bankalararası Kart Merkezi](https://bkm.com.tr/)
- BKM — [3D Secure rehber](https://bkm.com.tr/3d-secure/)
- TCMB — [Ödeme sistemleri](https://www.tcmb.gov.tr/)
- Visa — [3-D Secure 2.0 Specification](https://www.visa.com/3dsecure)
- Mastercard — [Identity Check](https://www.mastercard.com/global/en/business/issuers/safety-security/identity-check.html)
- Araştırma keyword'leri: `akbank sanal pos integration java`, `garanti sanal pos 3d secure`, `türkiye vpos integration guide`, `3d secure 2.0 message flow`

## 10. Sözlük

- **VPOS (Virtual POS)** — Bankaların online kart işlem API'si.
- **BKM** — Bankalararası Kart Merkezi; Türkiye kart ekosistemi düzenleyicisi.
- **3D Secure** — Visa/Mastercard kart sahibi doğrulama protokolü; Türkiye'de zorunlu.
- **Issuer bank** — Kart sahibinin bankası (örn. müşterinin Akbank kartı varsa Akbank issuer).
- **Acquiring bank** — Üye işyerinin bankası (mağazanın bankası).
- **Merchant ID** — Üye işyeri numarası; VPOS config'inde gerekli.
- **Terminal ID** — VPOS terminal numarası.
- **Callback URL** — Bankanın işlem sonucunu POST ettiği URL.
- **MAC / Hash** — Callback signature; spoofing engellemek için.
- **Currency code (ISO 4217)** — TRY için 949, USD için 840.
- **Settlement** — Para mağaza hesabına geçme; T+1 / T+2.
- **Chargeback** — Müşterinin işlemi reddi; geri çekme.
