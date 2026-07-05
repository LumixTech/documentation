---
title: Notification Provider Adapter Pattern
description: Email/SMS/Push provider adapter, port + adapter yapısı, tenant config-driven factory, multi-channel orchestration.
sidebar_position: 1
---

## Bu sayfa ne anlatıyor?

Lumix'in **bildirim sağlayıcılarının (email, SMS, push) neden adapter pattern üzerinde** organize edildiğini, **EmailProvider / SMSProvider / PushProvider** port'larını, **SES/SendGrid/Twilio/Netgsm/FCM/APNs** gibi somut adapter'ları, **tenant config** ile factory selection'ı ve **multi-channel orchestration** mekaniğini anlatır. Payment adapter pattern ile aynı felsefe; bu sefer notification domain'inde.

## 1. Bu nedir? (Sıfırdan)

### 1.1. Günlük hayattan analoji

Bir **kurye şirketi** düşün. Bir paket gönderirken iletim kanalı seçilebilir:
- Yurt içi standart (Yurtiçi Kargo)
- Yurt içi express (MNG)
- Uluslararası (DHL)
- Kurum içi kurye (motokurye)

Kurye şirketi yazılımı her birinin API'sini bilir ama dış kullanıcı için **tek bir interface** sunar: "Gönder". Hangi kanaldan gittiği detay.

Notification de aynı:
- Email kanalı için: SES, SendGrid, Mailgun, SMTP relay
- SMS kanalı için: Twilio, Netgsm, İletimerkezi, Mobildev, Vonage
- Push kanalı için: FCM, APNs, OneSignal

Lumix tek bir `NotificationUseCase` sunar; arkada hangi provider çağrıldığı tenant config'inden gelir.

### 1.2. Adapter pattern reminder

Önceki payment adapter sayfasındaki aynı yapı:

```text
┌────────────────────────────┐
│   Domain                    │
│   NotificationUseCase       │
└────────────┬────────────────┘
             │ uses
             ▼
┌────────────────────────────┐
│   Ports (interfaces)         │
│   EmailPort / SmsPort /      │
│   PushPort                   │
└──┬──────────┬─────────┬──────┘
   │          │         │
   ▼          ▼         ▼
┌─────┐  ┌──────┐  ┌────┐
│ SES │  │Netgsm│  │FCM │
│Adp  │  │ Adp  │  │Adp │
└─────┘  └──────┘  └────┘
```

Her port domain core'a soyut bir interface. Adapter'lar somut provider implementasyonları.

### 1.3. Notification türleri

Lumix 3 kanal destekler:

| Kanal | Kullanım |
|---|---|
| **Email** | Karne, fatura, makbuz, davet, raporlar |
| **SMS** | Veliye yoklama bilgisi, ödeme onayı, kritik uyarılar |
| **Push** | Mobile app içi bildirim (yeni mesaj, ödev, yorum) |

Bazıları **multi-channel** (örn. payment failure hem email hem SMS).

## 2. Hangi problemi çözüyor?

### 2.1. Provider tercihi çeşitliliği

- Bazı tenant'lar **AWS SES** kullanmak istiyor (kurumsal AWS hesabı var)
- Bazıları **SendGrid** ile sözleşmeli
- Bazıları **kendi SMTP**'sini kullanmak istiyor (kurumsal mail server)
- SMS için Türkiye'de **Netgsm** yaygın, ama global müşteri için **Twilio**
- Push: tek API üzerinden çoklu cihaz desteği için **FCM v1** (Android + iOS unified)

### 2.2. Provider lock-in olmamalı

Müşteri "ben SendGrid'den Mailgun'a geçmek istiyorum" derse → adapter değiştirilir, core kod değişmez.

### 2.3. Test ve dev environment

Dev'de gerçek email göndermek istenmez. Mock adapter çalışır:
- `MockEmailAdapter` log'a yazar
- `MailHogAdapter` local SMTP catcher'a gönderir

### 2.4. Per-tenant config

Müşteri başına farklı provider, farklı API key, farklı sender email/sms originator. Veri DB + Vault'ta.

## 3. Nasıl çalışıyor? (Çalışma prensibi)

### 3.1. Port tanımları

```java
public interface EmailPort {
    EmailDeliveryResult send(EmailMessage message);
    EmailProviderId providerId();
}

public interface SmsPort {
    SmsDeliveryResult send(SmsMessage message);
    SmsProviderId providerId();
}

public interface PushPort {
    PushDeliveryResult send(PushMessage message);
    PushProviderId providerId();
}
```

### 3.2. Message modelleri

```java
public record EmailMessage(
        UUID notificationId,
        UUID tenantId,
        String toAddress,
        String fromAddress,
        String subject,
        String htmlBody,
        String textBody,
        List<EmailAttachment> attachments,
        Map<String, String> headers
) {}

public record SmsMessage(
        UUID notificationId,
        UUID tenantId,
        String toPhoneNumber,        // E.164: +905XXXXXXXXX
        String originator,           // Sender ID veya phone number
        String body
) {}

public record PushMessage(
        UUID notificationId,
        UUID tenantId,
        String deviceToken,          // FCM token veya APNs token
        DevicePlatform platform,     // ANDROID, IOS, WEB
        String title,
        String body,
        Map<String, String> data,
        Optional<String> imageUrl,
        Optional<String> deepLink
) {}
```

### 3.3. Factory

```java
@Component
public class NotificationProviderFactory {

    private final Map<EmailProviderId, EmailPort> emailAdapters;
    private final Map<SmsProviderId, SmsPort> smsAdapters;
    private final Map<PushProviderId, PushPort> pushAdapters;
    private final TenantNotificationConfigRepository configRepo;

    public EmailPort emailFor(UUID tenantId) {
        var config = configRepo.findActiveEmailConfig(tenantId)
                .orElseThrow(() -> new NoProviderConfigException("email", tenantId));
        return emailAdapters.get(config.providerId());
    }

    public SmsPort smsFor(UUID tenantId) { /* ... */ }
    public PushPort pushFor(UUID tenantId) { /* ... */ }
}
```

### 3.4. NotificationUseCase

```java
@Service
@RequiredArgsConstructor
public class NotificationUseCase {

    private final NotificationProviderFactory factory;
    private final TemplateRenderer templateRenderer;
    private final RecipientResolver recipientResolver;
    private final NotificationLogRepository logRepository;

    public void send(UUID userId, String templateKey, Map<String, Object> variables) {
        Recipient recipient = recipientResolver.resolve(userId);
        Template template = templateRenderer.load(templateKey, recipient.locale());

        // Channel selection based on template + recipient preference
        for (NotificationChannel channel : template.channels()) {
            switch (channel) {
                case EMAIL -> sendEmail(recipient, template, variables);
                case SMS -> sendSms(recipient, template, variables);
                case PUSH -> sendPush(recipient, template, variables);
            }
        }
    }

    private void sendEmail(Recipient r, Template t, Map<String, Object> vars) {
        EmailMessage msg = templateRenderer.renderEmail(t, r, vars);
        EmailPort port = factory.emailFor(r.tenantId());
        EmailDeliveryResult result = port.send(msg);
        logRepository.save(NotificationLog.from(msg, result));
    }
    // sendSms, sendPush benzer
}
```

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Tenant notification config tablosu

```sql
CREATE TABLE tenant_notification_provider_config (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    channel VARCHAR(16) NOT NULL,       -- 'EMAIL', 'SMS', 'PUSH'
    provider_id VARCHAR(64) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    credentials_secret_path TEXT NOT NULL,
    config_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, channel, provider_id)
);

CREATE UNIQUE INDEX idx_tnpc_active ON tenant_notification_provider_config(tenant_id, channel)
    WHERE is_active = true;
```

Her kanal başına **tek aktif provider** kuralı (UNIQUE index).

### 4.2. Provider ID enum'ları

```java
public enum EmailProviderId { SES, SENDGRID, MAILGUN, SMTP_RELAY, MOCK }
public enum SmsProviderId { TWILIO, NETGSM, ILETIMERKEZI, MOBILDEV, VONAGE, MOCK }
public enum PushProviderId { FCM, APNS, ONESIGNAL, MOCK }
```

### 4.3. Notification trigger akışı (event-driven)

```text
Domain Service → Kafka event
                    │
                    ▼
            ┌──────────────────────┐
            │ notification-service │
            │ Kafka consumer       │
            └──────────┬───────────┘
                       │ map event → template
                       │ resolve recipient
                       │ render template
                       │ factory.emailFor(tenantId)
                       ▼
                   EmailPort.send()
                       │
                       ▼
                  Provider API
                       │
                       ▼
                  Log delivery result
```

Lumix Kafka consumer'ları:
- `academic.attendance.marked.v1` → veliye SMS
- `communication.message.sent.v1` → push notification
- `finance.invoice.created.v1` → email + push
- `finance.payment.failed.v1` → email + SMS
- `assessment.report_card.generated.v1` → email

### 4.4. Channel selection logic

Bir notification template hangi kanaldan gönderilecek?

| Karar faktörü | Açıklama |
|---|---|
| Template tanımı | Template'in `default_channels` field'ı |
| Tenant config | Tenant SMS kapatabilir (cost saving) |
| User preference | User SMS'i opt-out etmiş olabilir |
| Recipient capability | User'ın device token'ı yoksa push gönderilmez |
| Urgency | Critical alert → multi-channel zorla |

### 4.5. NotificationLog

```sql
CREATE TABLE notification_logs (
    notification_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    user_id UUID NOT NULL,
    channel VARCHAR(16) NOT NULL,
    provider_id VARCHAR(64) NOT NULL,
    provider_message_id VARCHAR(255),
    template_key VARCHAR(255) NOT NULL,
    locale VARCHAR(8) NOT NULL,
    status VARCHAR(32) NOT NULL,        -- 'QUEUED', 'SENT', 'DELIVERED', 'FAILED', 'BOUNCED'
    error_code VARCHAR(64),
    error_message TEXT,
    sent_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delivered_at TIMESTAMPTZ
);

CREATE INDEX idx_nl_tenant_sent ON notification_logs(tenant_id, sent_at DESC);
CREATE INDEX idx_nl_user ON notification_logs(user_id);
```

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. Düşünülen alternatifler

| Alternatif | Neden elendi |
|---|---|
| **Tek provider lock-in (sadece SES)** | Müşteri çeşitliliği kısıtlı; Türkiye-spesifik SMS provider için ayrı gerek |
| **Notification SaaS (Courier.com, Knock.app)** | Vendor lock-in, ekstra cost, KVKK riski (cloud'a recipient data gönderilir) |
| **Strategy pattern (no port + adapter)** | Aynı şey isim farkı; adapter pattern Spring'de daha tanıdık |
| **Single multi-channel API (Twilio Notify)** | Cool ama lock-in + cost; bazı tenant SES kullanmak istiyor |

### 5.2. Trade-off

- **Boilerplate**: Her provider için adapter implementation. 3 kanal × 5 provider = 15 adapter ihtimali. Çoğu opsiyonel.
- **Feature parity**: Her provider farklı feature destekler (template management, A/B testing, vs.). Lumix common subset kullanır; provider-specific feature'lar adapter içinde extension.
- **Failover yok (MVP)**: Provider down olursa fallback yok. İleride circuit breaker + secondary provider eklenebilir.

### 5.3. Ne değişirse kararı tekrar gözden geçiririz?

- Notification volume çok artarsa **provider-managed orchestration** (Courier vs.) değerlendirilir. Bugün overkill.
- Multi-channel choreography karmaşık olursa **Temporal workflow** ile her notification workflow olarak çalışır.

## 6. Pratik örnek

### 6.1. EmailPort SES adapter

```java
@Component
@RequiredArgsConstructor
public class SesEmailAdapter implements EmailPort {

    private final SesClientResolver resolver;

    @Override
    public EmailProviderId providerId() {
        return EmailProviderId.SES;
    }

    @Override
    public EmailDeliveryResult send(EmailMessage msg) {
        SesV2Client client = resolver.resolveForTenant(msg.tenantId());

        SendEmailRequest req = SendEmailRequest.builder()
                .fromEmailAddress(msg.fromAddress())
                .destination(d -> d.toAddresses(msg.toAddress()))
                .content(c -> c.simple(s -> s
                        .subject(sub -> sub.data(msg.subject()))
                        .body(b -> b
                                .html(h -> h.data(msg.htmlBody()))
                                .text(t -> t.data(msg.textBody())))))
                .build();
        try {
            SendEmailResponse response = client.sendEmail(req);
            return EmailDeliveryResult.success(response.messageId());
        } catch (SesV2Exception e) {
            return EmailDeliveryResult.failed(e.awsErrorDetails().errorCode(),
                    e.awsErrorDetails().errorMessage());
        }
    }
}
```

### 6.2. SmsPort Netgsm adapter

```java
@Component
@RequiredArgsConstructor
public class NetgsmSmsAdapter implements SmsPort {

    private final RestTemplate restTemplate;
    private final NetgsmCredentialResolver credentialResolver;

    @Override
    public SmsProviderId providerId() {
        return SmsProviderId.NETGSM;
    }

    @Override
    public SmsDeliveryResult send(SmsMessage msg) {
        NetgsmCredentials creds = credentialResolver.resolveForTenant(msg.tenantId());

        Map<String, String> params = Map.of(
                "usercode", creds.username(),
                "password", creds.password(),
                "gsmno", msg.toPhoneNumber().replaceFirst("\\+90", ""),
                "message", msg.body(),
                "msgheader", msg.originator(),
                "filter", "0"
        );

        String response = restTemplate.postForObject(
                "https://api.netgsm.com.tr/sms/send/get", params, String.class);

        // Netgsm response format: "00 jobId" or "20 errorCode"
        if (response != null && response.startsWith("00 ")) {
            String jobId = response.substring(3);
            return SmsDeliveryResult.success(jobId);
        }
        return SmsDeliveryResult.failed(response);
    }
}
```

### 6.3. PushPort FCM adapter

```java
@Component
@RequiredArgsConstructor
public class FcmPushAdapter implements PushPort {

    private final FirebaseAppResolver firebaseResolver;

    @Override
    public PushProviderId providerId() {
        return PushProviderId.FCM;
    }

    @Override
    public PushDeliveryResult send(PushMessage msg) {
        FirebaseApp app = firebaseResolver.resolveForTenant(msg.tenantId());
        FirebaseMessaging messaging = FirebaseMessaging.getInstance(app);

        Message fcmMessage = Message.builder()
                .setToken(msg.deviceToken())
                .setNotification(Notification.builder()
                        .setTitle(msg.title())
                        .setBody(msg.body())
                        .setImage(msg.imageUrl().orElse(null))
                        .build())
                .putAllData(msg.data())
                .build();
        try {
            String messageId = messaging.send(fcmMessage);
            return PushDeliveryResult.success(messageId);
        } catch (FirebaseMessagingException e) {
            return PushDeliveryResult.failed(e.getErrorCode().name(), e.getMessage());
        }
    }
}
```

### 6.4. NotificationUseCase (multi-channel)

```java
@Service
@RequiredArgsConstructor
public class NotificationUseCase {

    private final NotificationProviderFactory factory;
    private final TemplateRenderer templateRenderer;
    private final RecipientResolver recipientResolver;
    private final NotificationLogRepository logRepository;
    private final NotificationPreferenceService preferenceService;

    @Transactional
    public void send(UUID recipientUserId, String templateKey, Map<String, Object> variables) {
        Recipient recipient = recipientResolver.resolve(recipientUserId);
        Template template = templateRenderer.load(templateKey, recipient.locale());
        Set<NotificationChannel> channels = preferenceService.effectiveChannels(
                recipient, template);

        for (NotificationChannel ch : channels) {
            UUID notificationId = UuidV7Generator.generate();
            try {
                switch (ch) {
                    case EMAIL -> sendEmail(notificationId, recipient, template, variables);
                    case SMS   -> sendSms(notificationId, recipient, template, variables);
                    case PUSH  -> sendPush(notificationId, recipient, template, variables);
                }
            } catch (Exception e) {
                logRepository.saveFailed(notificationId, recipient, ch, templateKey,
                        e.getMessage());
            }
        }
    }
    // sendEmail / sendSms / sendPush detayları
}
```

### 6.5. Kafka consumer örneği

```java
@Component
@RequiredArgsConstructor
public class AttendanceMarkedNotifier {

    private final NotificationUseCase useCase;
    private final ParentLookup parentLookup;

    @KafkaListener(
            topics = "academic.attendance.marked.v1",
            groupId = "notification-service-attendance",
            containerFactory = "protoKafkaListenerContainerFactory"
    )
    public void onAttendanceMarked(AttendanceMarkedEvent ev) {
        if (!ev.getStatus().equals("ABSENT")) {
            return; // sadece devamsızlıkta veliyi bilgilendir
        }
        List<UUID> parentIds = parentLookup.findParentsByStudent(
                UUID.fromString(ev.getStudentId()));

        Map<String, Object> vars = Map.of(
                "studentName", ev.getStudentName(),
                "date", ev.getDate(),
                "lesson", ev.getLessonName());

        for (UUID parentId : parentIds) {
            useCase.send(parentId, "ATTENDANCE_ABSENT_PARENT", vars);
        }
    }
}
```

## 7. Dikkat edilecek tuzaklar

- **Domain'de provider-specific kod**. `SesClient` import etme domain'de; sadece adapter.
- **Credentials DB'de plain**. Vault path tut; runtime resolve.
- **Channel preference yok**. User opt-out edebilmeli (KVKK ticari mesaj kuralı).
- **Send-and-forget**. Provider response'unu loglama → bounce/failure görünmez.
- **Async olmadan send**. Notification sync olursa domain transaction yavaşlar. Kafka event-driven yap.
- **Rate limit yok**. Provider'lar throttling yapar; outgoing rate limit + queueing şart.
- **Template hard-coded**. Template'ler DB'de versioned; deploy etmeden değiştirilebilmeli.
- **i18n eksikliği**. Sadece TR template = İngilizce konuşan velide hatalı render.
- **Test ortamda canlı gönderim**. MockAdapter veya allowlist (sadece team email).
- **Bounce / suppression list yönetimi yok**. Bounce eden email'i tekrar göndermek = sender reputation çöker.
- **Notification log retention**. Sonsuza dek tutmak = DB şişer. Per-channel retention policy.
- **Multi-tenant credential paylaşma**. Tenant başına ayrı API key (cross-tenant isolation).

## 8. Diğer konularla ilişkisi

- [Email Providers](./email-providers)
- [SMS Providers](./sms-providers)
- [Push Providers](./push-providers)
- [MJML Template Rendering](./mjml-template-rendering)
- [Payment Adapter Pattern](../15-payment/payment-adapter-pattern) — aynı pattern
- [Domain Servisleri](../01-tenancy-and-domain-model/domain-services-overview) — notification-service
- [Hexagonal Architecture](../02-architecture-patterns)

## 9. Daha derine inmek için

- AWS SES — [Documentation](https://docs.aws.amazon.com/ses/)
- Twilio — [SMS API](https://www.twilio.com/docs/sms)
- Netgsm — [SMS API Docs](https://www.netgsm.com.tr/dokuman/)
- Firebase FCM — [Documentation](https://firebase.google.com/docs/cloud-messaging)
- Apple APNs — [Documentation](https://developer.apple.com/documentation/usernotifications)
- Araştırma keyword'leri: `notification adapter pattern multi-channel`, `email sms push unified api`, `tenant-aware notification provider`, `notification provider failover`

## 10. Sözlük

- **Notification provider** — Email, SMS veya push gönderen dış servis.
- **EmailPort / SmsPort / PushPort** — Lumix'in kanal başına soyut interface'leri.
- **Tenant config** — Tenant'ın hangi provider kullandığını tutan tablo.
- **Channel** — Email, SMS veya push iletim yolu.
- **Recipient** — Bildirimi alacak kişi (user + tercihler).
- **Template** — Render edilen mesaj şablonu (varies by locale).
- **Bounce** — Email'in alıcı tarafından reddedilmesi.
- **Suppression list** — Tekrar gönderilmemesi gereken adresler.
- **Opt-out** — Kullanıcının bildirim almama tercihi.
- **Originator** — SMS sender ID veya phone number.
- **Device token** — Push notification için cihaz tanımlayıcısı.
