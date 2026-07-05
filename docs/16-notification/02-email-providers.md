---
title: Email Provider Detayı — SES, SendGrid, Mailgun, SMTP
description: Email adapter detayı, SMTP vs API-based, bounce handling, suppression list, DKIM/SPF/DMARC, AWS SES kurulum + alternatif provider'lar.
sidebar_position: 2
---

## Bu sayfa ne anlatıyor?

Lumix'in **email kanalı** için provider seçeneklerini (**AWS SES, SendGrid, Mailgun, SMTP relay**), **API-based vs SMTP-based** entegrasyon farkını, **bounce/complaint handling**'i, **suppression list** yönetimini, **DKIM/SPF/DMARC** authentication standartlarını ve **default provider olarak SES**'in nasıl kurulduğunu anlatır. Önceki sayfa adapter pattern'i çerçevesini verdi; bu sayfa **email-spesifik detaylar**.

## 1. Bu nedir? (Sıfırdan)

### 1.1. Günlük hayattan analoji

Bir **gazete dağıtım sistemi** düşün. Gazete üretiyorsun, ama eve teslim için bir dağıtım firmasıyla anlaşıyorsun:
- **Kendi posta servisi (SMTP)** ile kendi kuryeni kullanırsın — kontrol tam, ama scale ve reputation senin sorumluluğun
- **Mesleki dağıtım servisi (SES, SendGrid)** kiralarsın — onların reputation'ı, IP havuzları, deliverability mühendisliği

Email göndermenin teknik karmaşıklığı (SPF, DKIM, IP warm-up, bounce yönetimi) çoğu uygulama için ESP (Email Service Provider) çözmesi gereken bir konu.

### 1.2. ESP nedir?

**Email Service Provider** (ESP), uygulamaların email göndermesini kolaylaştıran bulut servis:
- API ile gönderim
- IP havuzu yönetimi (reputation)
- Bounce + complaint feedback
- Open/click tracking (opsiyonel)
- Template management (bazıları)
- DKIM/SPF kolay setup

Major ESP'ler:
- **AWS SES** — Amazon, fiyat/performans iyi
- **SendGrid** — Twilio'ya ait, feature-rich
- **Mailgun** — Sinch'e ait, dev-friendly
- **Postmark** — transactional email odaklı
- **SparkPost** — büyük scale
- **Mailchimp** — marketing odaklı (Lumix için uygun değil)

### 1.3. SMTP vs API

**SMTP**: Klasik mail protocol. Herhangi bir SMTP server'a (Postfix, Sendmail, ESP'lerin SMTP endpoint'i) bağlanıp mail gönderirsin.
- Pro: standart, herhangi ESP'ye config değiştirerek geçilebilir
- Con: synchronous, slow (TCP handshake + STARTTLS), error handling kısıtlı

**API**: ESP'lerin REST API'si.
- Pro: hızlı (HTTP/2), zengin response, async friendly, batch support
- Con: ESP-specific, lock-in

Lumix tercihi: **API-based** (SES, SendGrid). SMTP backup adapter olarak var.

## 2. Hangi problemi çözüyor?

### 2.1. Email deliverability complexity

Mail gönderiyorsun → spam klasörüne düşüyor → kullanıcı şikayet ediyor. Sebepler:
- SPF kayıt yok → spoofing şüphesi
- DKIM signature yok → authenticity yok
- IP reputation kötü → gateway block ediyor
- Bounce rate yüksek → algorithmic penalty
- Suppression list yok → bounced address'lere tekrar mail = reputation çöker

ESP'ler bu sorunları çözmek için **optimize edilmiş**.

### 2.2. Compliance

KVKK ticari mesaj kuralları:
- Açık rıza olmadan ticari mesaj yasak
- Unsubscribe linki zorunlu (ticari)
- Suppression list maintain edilmeli
- Audit log

Transactional email (fatura, makbuz, sistem bildirimleri) bu kapsamda değil ama Lumix tüm email'ler için iyi pratik kabul eder.

### 2.3. Cost ve scale

Bin email/ay küçük ölçekte SMTP yeter. 100K+ ay/email = ESP zorunlu. Lumix SaaS olarak ölçeklenecek; ESP day-1.

## 3. Nasıl çalışıyor? (Çalışma prensibi)

### 3.1. Email gönderim akışı

```text
Domain → Kafka event
           │
           ▼
   notification-service consumer
           │
           ├── template render (MJML → HTML + text)
           ├── recipient resolve
           ├── EmailPort.send(EmailMessage)
           │       │
           │       ▼
           │   EmailAdapter (SES/SendGrid/...)
           │       │
           │       ▼
           │   Provider API
           │       │
           │       ▼
           │   Provider IP'leri → ISP'ler (Gmail, Outlook, ...)
           │       │
           │       ▼
           │   Recipient inbox
           │
           ├── delivery log (provider message ID, status)
           │
           └── webhook (provider → Lumix)
                   - delivered
                   - bounced (hard / soft)
                   - complained (marked as spam)
                   - opened (optional)
```

### 3.2. Bounce types

| Type | Anlam | Action |
|---|---|---|
| **Hard bounce** | Permanent failure (address yok, domain yok) | Suppression list'e ekle, tekrar gönderme |
| **Soft bounce** | Temporary (mailbox full, server down) | Retry with backoff, X kez sonra suppress |
| **Complaint** | Kullanıcı "spam" işaretledi | Suppression list'e ekle hemen |
| **Reject** | Provider gönderimi reject etti (content, throttle) | Investigate, retry farklı strateji ile |

### 3.3. SPF, DKIM, DMARC

Email authentication standartları:

| Standard | Ne yapar |
|---|---|
| **SPF (Sender Policy Framework)** | DNS TXT record: "Bu domain'den mail gönderebilecek IP'ler şunlardır" |
| **DKIM (DomainKeys Identified Mail)** | Mail header'a kriptografik imza ekler; alıcı sunucu DNS'ten public key alıp doğrular |
| **DMARC** | SPF + DKIM uyumsuz mail'lere ne yapılacağını söyler (reject, quarantine, none) |

Lumix kuralı: her tenant domain'i için **SPF + DKIM + DMARC** setup zorunlu. ESP otomatik DKIM signature ekler; SPF + DMARC DNS'te.

### 3.4. Bounce/complaint webhook

ESP gönderim sonucu webhook'la bildirir:

```text
SES → SNS topic → SQS → Lumix consumer
SendGrid → Lumix webhook endpoint (HMAC signed)
```

Lumix consumer:
1. Webhook payload verify (signature)
2. Notification log update (status)
3. Hard bounce / complaint → suppression list update
4. Audit log

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Default provider: AWS SES

Çoğu tenant için **AWS SES** default. Sebep:
- Düşük fiyat ($0.10 / 1000 email)
- Yüksek deliverability (eğer warm-up yapılırsa)
- AWS ekosistemiyle entegrasyon (SNS, SQS)
- IP havuzu shared veya dedicated

İlk müşterilerde shared IP; volume artınca dedicated IP.

### 4.2. Alternatif provider'lar

| Provider | Ne zaman |
|---|---|
| **SendGrid** | Müşteri SendGrid hesabı varsa veya feature-rich (template management) istiyorsa |
| **Mailgun** | Avrupa data residency veya dev-friendly API tercih edilirse |
| **SMTP relay** | Müşterinin kurumsal Exchange/Postfix server'ı varsa |
| **Mailpit / MailHog** | Local dev environment (test smtp catcher) |

### 4.3. Suppression list

```sql
CREATE TABLE email_suppressions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    email_address VARCHAR(320) NOT NULL,
    suppression_type VARCHAR(32) NOT NULL,  -- 'HARD_BOUNCE', 'COMPLAINT', 'MANUAL'
    reason TEXT,
    suppressed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, email_address)
);

CREATE INDEX idx_email_suppressions_lookup ON email_suppressions(tenant_id, email_address);
```

EmailUseCase her send'den önce suppression check:

```java
if (suppressionRepository.isSuppressed(tenantId, toAddress)) {
    log.warn("Skipping email to suppressed: {}", toAddress);
    return EmailDeliveryResult.suppressed();
}
```

### 4.4. From address ve sender

Her tenant'ın kendi `from address`'i:
- `noreply@omer-okullari.lumix.io` (Lumix subdomain)
- `bilgi@omer-okullari.k12.tr` (tenant kendi domaini, SPF setup yapılmışsa)

`Reply-To` farklı olabilir — gerçek bir okul email adresine.

### 4.5. Rate limit ve quota

| Tenant tier | Daily email quota |
|---|---|
| Trial | 1,000 |
| Standard | 50,000 |
| Pro | 250,000 |
| Enterprise | Custom |

Limit aşımında **queue** + alert. Hard limit AWS SES'te de var.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. SES vs SendGrid vs Mailgun

| Konu | SES | SendGrid | Mailgun |
|---|---|---|---|
| Fiyat | En düşük | Pahalı | Orta |
| Deliverability | İyi (warm-up gerekli) | Çok iyi | İyi |
| Feature (template, A/B) | Az | Çok | Orta |
| EU data residency | Region seç | Var | Var (EU plan) |
| Webhook (bounce) | SNS/SQS | HTTPS POST | HTTPS POST |
| Lumix default | ✓ | Optional | Optional |

### 5.2. SMTP relay neden default değil?

SMTP daha yavaş (synchronous), feature-poor (bounce/complaint için ayrı mekanizma), TLS/STARTTLS karmaşık. Yine de adapter var çünkü:
- Kurumsal müşteri "kendi mail server"ımı kullan diyor
- Air-gapped environment'ta external API yok
- Test environment için Mailpit

### 5.3. Trade-off

- **Provider lock-in**: API-based = lock-in. Adapter pattern bunu Lumix tarafında çözer.
- **Webhook security**: HMAC signature verify zorunlu; spoofed webhook = state corruption.
- **Cost**: SaaS müşteri tarafına yansıtılır (per-tenant quota + overage charge).

## 6. Pratik örnek

### 6.1. SES adapter (detaylı)

```java
@Component
@RequiredArgsConstructor
public class SesEmailAdapter implements EmailPort {

    private final SesClientResolver clientResolver;
    private final EmailSuppressionRepository suppressionRepo;

    @Override
    public EmailProviderId providerId() {
        return EmailProviderId.SES;
    }

    @Override
    public EmailDeliveryResult send(EmailMessage msg) {
        if (suppressionRepo.isSuppressed(msg.tenantId(), msg.toAddress())) {
            return EmailDeliveryResult.suppressed();
        }

        SesV2Client client = clientResolver.resolveForTenant(msg.tenantId());

        SendEmailRequest.Builder reqBuilder = SendEmailRequest.builder()
                .fromEmailAddress(msg.fromAddress())
                .destination(d -> d.toAddresses(msg.toAddress()))
                .replyToAddresses(msg.replyTo() != null ? List.of(msg.replyTo()) : null)
                .configurationSetName("lumix-tracking") // bounce/complaint tracking
                .emailTags(t -> t.name("tenant").value(msg.tenantId().toString()),
                           t -> t.name("template").value(msg.templateKey()));

        if (msg.attachments().isEmpty()) {
            reqBuilder.content(c -> c.simple(s -> s
                    .subject(sub -> sub.data(msg.subject()))
                    .body(b -> b
                            .html(h -> h.data(msg.htmlBody()))
                            .text(t -> t.data(msg.textBody())))));
        } else {
            // MIME multipart for attachments
            byte[] rawMime = buildMimeMessage(msg);
            reqBuilder.content(c -> c.raw(r -> r.data(SdkBytes.fromByteArray(rawMime))));
        }

        try {
            SendEmailResponse resp = client.sendEmail(reqBuilder.build());
            return EmailDeliveryResult.success(resp.messageId());
        } catch (MessageRejectedException e) {
            return EmailDeliveryResult.failed("MESSAGE_REJECTED", e.getMessage());
        } catch (SesV2Exception e) {
            return EmailDeliveryResult.failed(e.awsErrorDetails().errorCode(),
                    e.awsErrorDetails().errorMessage());
        }
    }

    private byte[] buildMimeMessage(EmailMessage msg) {
        // jakarta.mail Multipart construction
        ...
    }
}
```

### 6.2. SES credential resolver

```java
@Component
@RequiredArgsConstructor
public class SesClientResolver {

    private final VaultTemplate vaultTemplate;
    private final TenantNotificationConfigRepository configRepo;
    private final ConcurrentMap<UUID, SesV2Client> clientCache = new ConcurrentHashMap<>();

    public SesV2Client resolveForTenant(UUID tenantId) {
        return clientCache.computeIfAbsent(tenantId, this::createClient);
    }

    private SesV2Client createClient(UUID tenantId) {
        var config = configRepo.findActiveEmailConfig(tenantId).orElseThrow();
        SesCredentials creds = readVault(config.credentialsSecretPath());

        return SesV2Client.builder()
                .region(Region.of(creds.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(creds.accessKey(), creds.secretKey())))
                .build();
    }

    private SesCredentials readVault(String path) {
        VaultResponse vr = vaultTemplate.read(path);
        var data = vr.getData();
        return new SesCredentials(
                (String) data.get("region"),
                (String) data.get("access_key"),
                (String) data.get("secret_key"));
    }
}
```

### 6.3. Bounce webhook handler (SES via SNS)

```java
@RestController
@RequestMapping("/api/v1/notifications/webhooks")
@RequiredArgsConstructor
public class EmailWebhookController {

    private final EmailWebhookUseCase useCase;
    private final SnsSignatureVerifier verifier;

    @PostMapping("/ses")
    public ResponseEntity<Void> handleSesWebhook(
            @RequestBody String rawBody,
            @RequestHeader Map<String, String> headers) {
        if (!verifier.verify(rawBody, headers)) {
            return ResponseEntity.status(403).build();
        }
        SnsMessage snsMsg = parseSnsMessage(rawBody);
        if ("SubscriptionConfirmation".equals(snsMsg.type())) {
            useCase.confirmSubscription(snsMsg);
            return ResponseEntity.ok().build();
        }
        SesNotification notification = parseSesNotification(snsMsg.message());
        useCase.handleNotification(notification);
        return ResponseEntity.ok().build();
    }
}

@Service
@RequiredArgsConstructor
public class EmailWebhookUseCase {

    private final NotificationLogRepository logRepo;
    private final EmailSuppressionRepository suppressionRepo;

    public void handleNotification(SesNotification n) {
        String messageId = n.mail().messageId();
        NotificationLog log = logRepo.findByProviderMessageId(messageId).orElse(null);

        switch (n.notificationType()) {
            case "Bounce" -> handleBounce(n.bounce(), log);
            case "Complaint" -> handleComplaint(n.complaint(), log);
            case "Delivery" -> {
                if (log != null) {
                    log.markDelivered();
                    logRepo.save(log);
                }
            }
        }
    }

    private void handleBounce(BounceData bounce, NotificationLog log) {
        if (log != null) {
            log.markBounced(bounce.bounceType(), bounce.bounceSubType());
            logRepo.save(log);
        }
        if ("Permanent".equals(bounce.bounceType())) {
            for (BouncedRecipient br : bounce.bouncedRecipients()) {
                suppressionRepo.save(EmailSuppression.bounce(
                        log != null ? log.tenantId() : null,
                        br.emailAddress(),
                        br.diagnosticCode()));
            }
        }
    }

    private void handleComplaint(ComplaintData complaint, NotificationLog log) {
        if (log != null) {
            log.markComplained();
            logRepo.save(log);
        }
        for (ComplainedRecipient cr : complaint.complainedRecipients()) {
            suppressionRepo.save(EmailSuppression.complaint(
                    log != null ? log.tenantId() : null,
                    cr.emailAddress(),
                    complaint.complaintFeedbackType()));
        }
    }
}
```

### 6.4. SMTP relay adapter (fallback)

```java
@Component
@ConditionalOnProperty("lumix.notification.email.smtp.enabled")
@RequiredArgsConstructor
public class SmtpEmailAdapter implements EmailPort {

    private final JavaMailSender mailSender;

    @Override
    public EmailProviderId providerId() {
        return EmailProviderId.SMTP_RELAY;
    }

    @Override
    public EmailDeliveryResult send(EmailMessage msg) {
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(msg.fromAddress());
            helper.setTo(msg.toAddress());
            helper.setSubject(msg.subject());
            helper.setText(msg.textBody(), msg.htmlBody());
            mailSender.send(mime);
            return EmailDeliveryResult.success(mime.getMessageID());
        } catch (Exception e) {
            return EmailDeliveryResult.failed("SMTP_ERROR", e.getMessage());
        }
    }
}
```

### 6.5. SES setup (DNS records)

Yeni tenant onboarding'inde:
```text
1. Verify sender domain in SES (DKIM CNAME records)
2. Set up SPF: TXT "v=spf1 include:amazonses.com -all"
3. DMARC: TXT "v=DMARC1; p=quarantine; rua=mailto:dmarc@lumix.io"
4. Configuration set: enable bounce + complaint to SNS topic
5. SNS → Lumix webhook subscription
```

Ansible playbook bunu otomatize eder.

## 7. Dikkat edilecek tuzaklar

- **DKIM eksik**. Recipient ISP'leri spam'a düşürür. Setup verify et.
- **SPF eksik veya yanlış**. SES'te `include:amazonses.com` şart; missing = spoofing accept.
- **DMARC `p=reject` ile başlama**. Önce `p=none` → monitor → `p=quarantine` → `p=reject`.
- **Hard bounce'a tekrar send**. Reputation çöker. Suppression check her send'den önce.
- **Webhook signature verify atlama**. SNS/SendGrid signature doğrulanmalı; spoofed = state corruption.
- **Catch-all error handling**. Soft bounce retry + backoff; immediate suppress yanlış.
- **HTML-only email**. Bazı recipient client'ları text-only. Her email iki body (HTML + text).
- **Bütçesiz quota**. SES daily limit aşıldığında reject; soft limit increase request 24h alır.
- **Suppression list per-tenant değil global**. KVKK + tenant ownership için per-tenant tut.
- **Email body'de PII trace**. Email log'u şifrelenmeli veya body redact edilmeli.
- **Open/click tracking otomatik**. Tracking pixel + redirect linkler eklemeden gönderme; KVKK rıza gerekir.
- **Test environment'tan canlı email**. Allow-list yok = team'e mail gönderirsin spam'a yakalanır.

## 8. Diğer konularla ilişkisi

- [Notification Adapter Pattern](./01-notification-adapter-pattern.md)
- [MJML Template Rendering](./05-mjml-template-rendering.md)
- [SMS Providers](./03-sms-providers.md) — parallel pattern
- [Push Providers](./04-push-providers.md)
- [Compliance](../security-compliance) — KVKK + suppression
- [Audit Log](../security-compliance/audit-log-design) — email gönderim audit

## 9. Daha derine inmek için

- AWS SES — [Developer Guide](https://docs.aws.amazon.com/ses/latest/dg/Welcome.html)
- SendGrid — [API Documentation](https://docs.sendgrid.com/api-reference)
- Mailgun — [Documentation](https://documentation.mailgun.com/)
- DKIM — [RFC 6376](https://datatracker.ietf.org/doc/html/rfc6376)
- DMARC — [dmarc.org](https://dmarc.org/)
- Araştırma keyword'leri: `aws ses configuration set bounce sns`, `dkim spf dmarc setup guide`, `email deliverability best practices`, `transactional email vs marketing`

## 10. Sözlük

- **ESP (Email Service Provider)** — Email gönderim cloud servisi (SES, SendGrid, vs.).
- **SMTP** — Klasik mail gönderim protokolü.
- **SPF** — Domain authorize edilmiş gönderici IP'leri tanımlayan DNS record.
- **DKIM** — Cryptographic signature ile email authenticity.
- **DMARC** — SPF + DKIM policy ve reporting.
- **Bounce** — Email gönderim başarısızlığı (hard veya soft).
- **Hard bounce** — Permanent failure (yok adres).
- **Soft bounce** — Temporary failure (mailbox full).
- **Complaint** — Recipient "spam" işaretledi.
- **Suppression list** — Tekrar gönderilmemesi gereken adresler.
- **Sender reputation** — IP/domain'in deliverability skoru.
- **IP warm-up** — Yeni IP'den volume'u kademeli artırma süreci.
- **Configuration set (SES)** — Event publishing config (SNS topic'e bounce/complaint).
