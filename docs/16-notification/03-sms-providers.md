---
title: SMS Provider Detayı — Netgsm, İletimerkezi, Twilio
description: Türkiye SMS sağlayıcıları (Netgsm, İletimerkezi, Mobildev), uluslararası (Twilio, Vonage), opt-out yönetimi, KVKK + İYS uyumu, cost control.
sidebar_position: 3
---

## Bu sayfa ne anlatıyor?

Lumix'in **SMS kanalı** için Türkiye yerel sağlayıcılarını (**Netgsm, İletimerkezi, Mobildev**), **uluslararası alternatifleri** (**Twilio, Vonage**), **opt-out yönetimi**ni, **KVKK ve İYS** (İleti Yönetim Sistemi) uyumunu, **cost control** kararlarını ve adapter implementasyon detaylarını anlatır. Bizden istek üzerine: Türkiye'ye özgü regulatory bağlamı **özellikle** ele alır.

## 1. Bu nedir? (Sıfırdan)

### 1.1. Günlük hayattan analoji

Telefonuna gelen bir SMS düşün. Operatör (Turkcell, Vodafone, Türk Telekom) sadece **iletim katmanı**. SMS'i kim gönderdi? Bir kurumdur, ama doğrudan operatör API'sini kullanmaz. Araya bir **SMS sağlayıcı** girer:
- Kurum → SMS sağlayıcı (Netgsm, vs.) → operatör → telefon

SMS sağlayıcı birden fazla operatörle anlaşma yapar, aggreagate eder. Bu yüzden tek API ile tüm Türkiye'ye SMS gönderebilirsin.

### 1.2. Türkiye SMS ekosistemi

Türkiye'de SMS göndermek için:
- **Mesaj başlığı (originator)** lisanslı olmalı (BTK ya da operatörlerden onaylı)
- **İYS (İleti Yönetim Sistemi)** kaydı zorunlu — ticari mesajlar için
- **Aydınlatma + onay** gerekli ticari içerikte
- **Saatler arası kısıtlama** (gece 24:00 - 08:00 ticari SMS yasak)

Lumix transactional SMS yoğun (ödeme onayı, devamsızlık, ödev). Bunlar **rıza temelli** değil **sözleşmenin ifası** kapsamında — ama yine de İYS kaydı + opt-out altyapısı şart.

### 1.3. Provider listesi

| Provider | Türkiye yerel | Uluslararası | Özellik |
|---|---|---|---|
| **Netgsm** | ✓ | sınırlı | En yaygın TR provider, kuvvetli rest API |
| **İletimerkezi** | ✓ | sınırlı | Bayi/dealer ekosistemi |
| **Mobildev** | ✓ | sınırlı | Kurumsal, B2B odaklı |
| **Twilio** | sınırlı | ✓ | Global standart, Türkiye'de TL fiyat değil |
| **Vonage (Nexmo)** | sınırlı | ✓ | Global |
| **MessageBird** | sınırlı | ✓ | Avrupa odaklı |

Türkiye-ağırlıklı müşteri için **Netgsm default**.

## 2. Hangi problemi çözüyor?

### 2.1. Türkiye-spesifik regulatory

İYS kaydı olmayan kurum ticari SMS gönderirse:
- Operatörler bloklar
- BTK ceza kesebilir
- Tüketici şikayetleri toplu olarak gelir

Transactional SMS için bile **onay kayıtları** sistemli tutulmalı.

### 2.2. Sender ID lisansı

Mesaj başlığı (örn. "LUMIX") operatörden lisanslı olmalı. Lumix MVP'de:
- Lumix'in kendi sender ID'si (örn. "LUMIX") — Lumix sağlar
- Müşterinin sender ID'si (örn. "OMEROKUL") — müşteri kendi başvurusu, Lumix config'ine girer

### 2.3. Cost control

SMS pahalı: TR yerel ~0.05-0.10 TL/SMS, global Twilio $0.05-0.10/SMS. Bin SMS = 50-100 TL → ay binlerce öğrenci × günlük devamsızlık SMS = ciddi maliyet.

Lumix kontrolleri:
- Tenant başına daily/monthly SMS quota
- Critical only SMS (devamsızlık, ödeme failure) — info SMS push'a düşer
- Bulk discount provider ile pazarlık

## 3. Nasıl çalışıyor? (Çalışma prensibi)

### 3.1. SMS gönderim akışı

```text
Domain event (örn. attendance.marked ABSENT)
    │
    ▼
notification-service consumer
    │
    ├── recipient resolve (veli telefon)
    ├── opt-out check
    ├── İYS consent check (commercial ise)
    ├── quota check
    ├── template render (locale-aware)
    ├── SmsPort.send(SmsMessage)
    │       │
    │       ▼
    │   SmsAdapter (Netgsm/...)
    │       │
    │       ▼
    │   Provider HTTP API
    │       │
    │       ▼
    │   Operator → cep telefonu
    │
    └── delivery log + cost track
```

### 3.2. Phone number normalization

Tüm telefonlar **E.164 format**'ında store:
- "0532 123 45 67" → `+905321234567`
- "532-123-45-67" → `+905321234567`
- "5321234567" → `+905321234567` (TR varsayılan)
- "+447700..." → `+447700...` (UK)

`libphonenumber-java` kullanılır.

### 3.3. İYS entegrasyonu

```text
1. User opt-in olduğunda: İYS'ye consent kayıt
2. SMS göndermeden önce: İYS lookup ("Bu kullanıcının onayı var mı?")
3. Opt-out olduğunda: İYS'ye revoke
```

Lumix'te İYS bir özel adapter:

```java
public interface ConsentRegistryPort {
    boolean hasConsent(String phoneE164, ConsentScope scope);
    void recordConsent(String phoneE164, ConsentScope scope);
    void revokeConsent(String phoneE164, ConsentScope scope);
}

@Component
public class IysConsentAdapter implements ConsentRegistryPort {
    // İYS API entegrasyonu
}
```

Transactional SMS (devamsızlık) için consent gerekmez — sözleşmenin ifası. Ama **opt-out** her zaman geçerlidir.

### 3.4. Opt-out

Kullanıcı "İPTAL" yazıp `4140` short code'una SMS yollarsa operatör opt-out kaydı tutar; sonraki SMS bloke edilir. Lumix bunu ayrıca **internal opt-out table**'da tutar:

```sql
CREATE TABLE sms_opt_outs (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    phone_e164 VARCHAR(16) NOT NULL,
    opted_out_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reason VARCHAR(255),
    UNIQUE (tenant_id, phone_e164)
);
```

### 3.5. Delivery report (DLR)

Provider gönderim sonrası bir webhook ile DLR (Delivery Report) gönderir:
- Delivered
- Failed (operator down, invalid number)
- Expired (recipient phone off, retry exhausted)

Netgsm DLR HTTP GET; Twilio DLR HTTP POST + signature.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Netgsm default ayarlar

```json
// Netgsm config_json (tenant_notification_provider_config)
{
  "originator": "OMEROKUL",
  "api_endpoint": "https://api.netgsm.com.tr/sms/send/get",
  "callback_url": "https://omer.lumix.io/api/v1/notifications/webhooks/netgsm",
  "encoding": "UTF-8",
  "max_unicode_length": 70,
  "max_gsm7_length": 160
}
```

Credentials Vault'ta: `kv/tenant/{tenant_id}/notification/netgsm` → `{ "username": "...", "password": "..." }`.

### 4.2. Unicode vs GSM-7

SMS standardı 160 karakter (7-bit). Türkçe karakter (`ı`, `ğ`, `ö`, vs.) **GSM-7** içinde tam değil:
- ASCII karakter SMS → 160 char/segment
- Türkçe karakter içeren SMS → **UCS-2 Unicode** → 70 char/segment
- 70 karaktere bölünür, 70'i geçerse multi-part SMS (her part ayrı ücretlendirilir)

Template tasarımında Türkçe karakter sayısı dikkate alınmalı. Mümkünse ASCII fallback:
- "Yarınki sınav" → "Yarinki sinav" (cost saving) — ama UX kötü
- Lumix kuralı: Türkçe karakter tut, multi-part kabul, cost analyze

### 4.3. Quota ve rate limiting

Per-tenant Redis counter:
```text
sms:quota:tenant:{tenant_id}:day:{yyyy-mm-dd} → count
sms:quota:tenant:{tenant_id}:month:{yyyy-mm} → count
```

Limit aşıldığında:
- Email fallback (varsa)
- Push fallback (varsa)
- Admin alert
- Critical SMS only (devamsızlık olsa bile blockla cancellation alert at)

### 4.4. SMS template örneği

```text
Sayın {parent_name}, {student_name} {date} tarihinde {lesson_name} dersine girmedi.
İletişim: {school_phone}
İPTAL için: İPTAL gönderin
```

Template ortalama 150 karakter Türkçe = ~3 segment. Kontrolde tutulmalı.

### 4.5. Auditing

```sql
SELECT
    DATE_TRUNC('day', sent_at) AS day,
    COUNT(*) FILTER (WHERE status = 'DELIVERED') AS delivered,
    COUNT(*) FILTER (WHERE status = 'FAILED') AS failed,
    SUM(segment_count) AS segments_billed
FROM notification_logs
WHERE channel = 'SMS' AND tenant_id = 'xxx'
GROUP BY day;
```

Cost reporting müşteri admin paneline yansır.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. Netgsm vs İletimerkezi vs Mobildev

| Konu | Netgsm | İletimerkezi | Mobildev |
|---|---|---|---|
| API olgunluk | REST + SOAP | REST + SOAP | REST + SOAP |
| Documentation | İyi | Orta | Eski |
| TL fiyat | Rekabetçi | Daha düşük (volume) | Pazarlık |
| Sender ID setup | 24-48h | 1-3 gün | 1-3 gün |
| DLR support | ✓ | ✓ | ✓ |
| TR coverage | 100% | 100% | 100% |
| Uluslararası | Sınırlı | Sınırlı | Sınırlı |

Lumix MVP: **Netgsm default** + diğerleri opsiyon.

### 5.2. Twilio neden default değil?

- Türkiye SMS fiyatı USD bazlı, kur dalgalanması var
- TL faturalama olmadan kurumsal SaaS müşteri için muhasebe sıkıntısı
- Türkiye-spesifik sender ID süreçleri için Netgsm gibi yerel provider daha esnek

Ama Twilio adapter var çünkü:
- Global müşteriler için tek provider
- WhatsApp Business API (Twilio'da var, ileride feature)
- Dev experience iyi (testing, mock)

### 5.3. Trade-off

- **Multi-provider operational cost**: Her provider için sözleşme + İYS başvurusu + sender ID. Müşteri başına setup süresi var.
- **Unicode cost**: Türkçe karakter = 2x cost. Trade-off net iletişim ile karşılaştırılmalı.
- **Critical SMS only policy**: Push notification mobile install gerektirir; web kullanıcısı için SMS şart. Critical filter kullanıcıyı ilgilendiren olayları kaybetmemeli.

## 6. Pratik örnek

### 6.1. Netgsm adapter (REST API)

```java
@Component
@RequiredArgsConstructor
public class NetgsmSmsAdapter implements SmsPort {

    private static final String API_URL = "https://api.netgsm.com.tr/sms/send/get";

    private final RestTemplate restTemplate;
    private final NetgsmCredentialResolver credentialResolver;
    private final NetgsmConfigResolver configResolver;
    private final SmsOptOutRepository optOutRepo;
    private final SmsSegmentCounter segmentCounter;

    @Override
    public SmsProviderId providerId() {
        return SmsProviderId.NETGSM;
    }

    @Override
    public SmsDeliveryResult send(SmsMessage msg) {
        if (optOutRepo.isOptedOut(msg.tenantId(), msg.toPhoneNumber())) {
            return SmsDeliveryResult.optedOut();
        }

        NetgsmCredentials creds = credentialResolver.resolveForTenant(msg.tenantId());
        NetgsmConfig cfg = configResolver.resolveForTenant(msg.tenantId());

        String phoneWithoutPlus90 = msg.toPhoneNumber().replaceFirst("^\\+90", "");

        Map<String, String> params = new LinkedHashMap<>();
        params.put("usercode", creds.username());
        params.put("password", creds.password());
        params.put("gsmno", phoneWithoutPlus90);
        params.put("message", msg.body());
        params.put("msgheader", cfg.originator());
        params.put("filter", "0");
        params.put("encoding", containsTurkish(msg.body()) ? "TR" : "0");

        String response;
        try {
            response = restTemplate.getForObject(buildUrl(params), String.class);
        } catch (RestClientException e) {
            return SmsDeliveryResult.failed("NETWORK_ERROR", e.getMessage());
        }

        return parseResponse(response, msg);
    }

    private SmsDeliveryResult parseResponse(String response, SmsMessage msg) {
        if (response == null) {
            return SmsDeliveryResult.failed("EMPTY_RESPONSE", "Provider returned empty");
        }
        // Netgsm format: "00 jobId" success, "20-99 errorCode" error
        if (response.startsWith("00 ")) {
            String jobId = response.substring(3).trim();
            int segments = segmentCounter.count(msg.body());
            return SmsDeliveryResult.success(jobId, segments);
        }
        String errorCode = response.split(" ")[0];
        return SmsDeliveryResult.failed(errorCode, mapErrorMessage(errorCode));
    }

    private boolean containsTurkish(String body) {
        return body.codePoints().anyMatch(c ->
                c == 'ı' || c == 'İ' || c == 'ğ' || c == 'Ğ' ||
                c == 'ü' || c == 'Ü' || c == 'ş' || c == 'Ş' ||
                c == 'ö' || c == 'Ö' || c == 'ç' || c == 'Ç');
    }
}
```

### 6.2. Twilio adapter

```java
@Component
@RequiredArgsConstructor
public class TwilioSmsAdapter implements SmsPort {

    private final TwilioClientResolver clientResolver;
    private final SmsOptOutRepository optOutRepo;

    @Override
    public SmsProviderId providerId() {
        return SmsProviderId.TWILIO;
    }

    @Override
    public SmsDeliveryResult send(SmsMessage msg) {
        if (optOutRepo.isOptedOut(msg.tenantId(), msg.toPhoneNumber())) {
            return SmsDeliveryResult.optedOut();
        }

        TwilioCredentials creds = clientResolver.resolveCredentials(msg.tenantId());
        Twilio.init(creds.accountSid(), creds.authToken());

        try {
            Message twilioMsg = Message.creator(
                    new PhoneNumber(msg.toPhoneNumber()),
                    new PhoneNumber(creds.fromNumber()),
                    msg.body()
            ).setStatusCallback(URI.create(creds.callbackUrl())).create();

            return SmsDeliveryResult.success(twilioMsg.getSid(), 1);
        } catch (ApiException e) {
            return SmsDeliveryResult.failed(String.valueOf(e.getCode()), e.getMessage());
        }
    }
}
```

### 6.3. Segment counter

```java
@Component
public class SmsSegmentCounter {

    public int count(String body) {
        boolean unicode = containsUnicode(body);
        int len = body.length();

        if (unicode) {
            // UCS-2: 70 chars per segment, 67 per part if multi-part
            if (len <= 70) return 1;
            return (int) Math.ceil(len / 67.0);
        }
        // GSM-7: 160 chars per segment, 153 per part if multi-part
        if (len <= 160) return 1;
        return (int) Math.ceil(len / 153.0);
    }

    private boolean containsUnicode(String body) {
        return body.codePoints().anyMatch(c -> c > 127);
    }
}
```

### 6.4. Opt-out webhook

```java
@PostMapping("/api/v1/notifications/webhooks/netgsm/dlr")
public ResponseEntity<Void> handleNetgsmDlr(@RequestParam Map<String, String> params) {
    String jobId = params.get("jobid");
    String status = params.get("status");
    String phone = params.get("number");

    if ("13".equals(status)) {  // Netgsm: opt-out / blacklist
        smsOptOutRepository.recordOptOut(/* tenant resolved from jobId */, "+90" + phone);
    }

    notificationLogRepository.findByProviderMessageId(jobId).ifPresent(log -> {
        log.updateDeliveryStatus(mapNetgsmStatus(status));
        notificationLogRepository.save(log);
    });

    return ResponseEntity.ok().build();
}
```

### 6.5. Quota check use case

```java
@Service
@RequiredArgsConstructor
public class SmsQuotaService {

    private final RedisTemplate<String, String> redis;
    private final TenantQuotaRepository quotaRepo;

    public void enforceAndIncrement(UUID tenantId) {
        TenantQuota quota = quotaRepo.findByTenantId(tenantId).orElseThrow();
        String dayKey = String.format("sms:quota:%s:day:%s", tenantId, LocalDate.now());

        Long count = redis.opsForValue().increment(dayKey);
        if (count != null && count == 1L) {
            redis.expire(dayKey, Duration.ofHours(36));
        }
        if (count != null && count > quota.dailySmsLimit()) {
            throw new QuotaExceededException("Daily SMS quota exceeded");
        }
    }
}
```

## 7. Dikkat edilecek tuzaklar

- **İYS kaydı atlama**. Ticari SMS için yasal zorunluluk. Transactional için ayrı muhasebe.
- **Phone format inconsistency**. E.164 normalize her yerde; DB'de format karışık olmasın.
- **Türkçe karakter sayma yanlış**. Multi-part hesabını yanlış yapma; cost surprise.
- **Sender ID lisans yok**. Bilinmeyen başlık ile SMS = operatör reject.
- **Opt-out audit eksikliği**. "Kullanıcı opt-out istemedi" diye savunabilmek için kayıt şart.
- **Quota olmadan production**. Bir bug'lı consumer milyon SMS yollayabilir; quota şart.
- **Critical/non-critical ayırımı yok**. Push'tan etkilenmemesi gereken SMS'leri bloklamak yanlış.
- **Provider down olduğunda retry yok**. Soft failure → DLQ → manuel inceleme.
- **DLR webhook signature verify atlama**. Spoofed DLR delivery report sahteleştirebilir.
- **Encoding yanlış**. UTF-8 yerine Netgsm "TR" encoding bekleyebilir; doc'a göre.
- **Test environment'tan canlı SMS**. CI'da gerçek SMS gönderme; mock adapter.
- **Cost tracking eksikliği**. Aylık fatura geldiğinde sürpriz olur; runtime track.

## 8. Diğer konularla ilişkisi

- [Notification Adapter Pattern](./01-notification-adapter-pattern.md)
- [Email Providers](./02-email-providers.md) — parallel
- [Push Providers](./04-push-providers.md)
- [MJML Template Rendering](./05-mjml-template-rendering.md) — template support
- [Compliance](../security-compliance) — KVKK + İYS
- [Audit Log](../security-compliance/audit-log-design)

## 9. Daha derine inmek için

- Netgsm — [API Documentation](https://www.netgsm.com.tr/dokuman/)
- İletimerkezi — [API Documentation](https://www.iletimerkezi.com/api/)
- Twilio — [SMS Quickstart Java](https://www.twilio.com/docs/sms/quickstart/java)
- İYS — [İleti Yönetim Sistemi](https://iys.org.tr/)
- BTK — [Elektronik Haberleşme](https://www.btk.gov.tr/)
- libphonenumber — [GitHub](https://github.com/google/libphonenumber)
- Araştırma keyword'leri: `netgsm rest api java`, `iys integration api`, `gsm7 vs ucs2 sms segment`, `e164 phone number normalization java`

## 10. Sözlük

- **SMS provider** — SMS gönderim cloud servisi (Netgsm, Twilio, vs.).
- **Originator (sender ID)** — Mesaj başlığı; lisans gerektirir.
- **E.164** — International phone number format (+905XXXXXXXXX).
- **GSM-7** — 7-bit SMS encoding; 160 char/segment.
- **UCS-2 / Unicode** — Türkçe karakter destekli; 70 char/segment.
- **Segment** — SMS ücretlendirme birimi.
- **DLR (Delivery Report)** — Gönderim sonrası provider webhook.
- **İYS (İleti Yönetim Sistemi)** — Türkiye'de ticari ileti onay yönetim sistemi.
- **Opt-out** — Kullanıcının SMS almama tercihi.
- **Transactional SMS** — Sözleşmenin ifası kapsamında SMS (consent gerekmez).
- **Commercial SMS** — Pazarlama/reklam SMS (İYS consent gerekir).
- **Quota** — Tenant başına dönem başına SMS limiti.
