---
title: MJML ile Email Template Rendering
description: MJML responsive email template, variable substitution, i18n (TR/EN), template versioning, preview tool.
sidebar_position: 5
---

## Bu sayfa ne anlatıyor?

Lumix'in **email template'lerini** nasıl yazdığını, **MJML**'in (Mailjet Markup Language) **neden HTML+CSS yerine seçildiğini**, **responsive cross-client uyumu**nu, **variable substitution** yapısını, **i18n (TR/EN/diğer)** yönetimini, **template versioning** stratejisini ve **preview tool**'unu anlatır. SMS ve push template'leri de aynı i18n + versioning kurallarını paylaşır — bu sayfa **email-spesifik** detaylarla başlar.

## 1. Bu nedir? (Sıfırdan)

### 1.1. Günlük hayattan analoji

Bir gazete tasarımcısı düşün. Her makaleyi sıfırdan dizmek yerine **standart şablonlar** kullanır: manşet, alt başlık, fotoğraf yerleştirme. Her gazete farklı içerikle aynı şablona oturur.

Email template aynı: ortak header, footer, button stili, marka renkleri. Her email'in **yapısı** template'tedir, **içeriği** variable substitution ile değişir.

### 1.2. Email HTML'in zorluğu

Modern web'de CSS3, flexbox, grid kullanırsın. **Email'de bunların çoğu çalışmaz**. Email client'ları (Gmail, Outlook, Apple Mail, Yahoo, native mobile mail) farklı render engine kullanır:

- Outlook 2007-2019: **Word render engine** (modern CSS yok, `<table>` ile layout!)
- Gmail web: WebKit ama bazı CSS strip'lenir
- iOS Mail: WebKit, modern ama dark mode kuralları farklı
- Outlook.com: WebKit ama Outlook desktop'tan farklı

Sonuç: doğrudan HTML+CSS yazarsan **bir client'ta güzel, diğerinde bozuk**. Tarihte buna "**email HTML cehennemi**" denir.

### 1.3. MJML'in çözümü

**MJML** (Mailjet Markup Language) **abstraction layer**. JSX-benzeri component'ler yazarsın, MJML compile eder, **cross-client uyumlu HTML+CSS** üretir. Outlook table-based, Gmail responsive, iOS dark mode — hepsi MJML compile output'unda.

Örnek MJML:

```xml
<mjml>
  <mj-body>
    <mj-section>
      <mj-column>
        <mj-text>Merhaba {{name}}!</mj-text>
        <mj-button href="{{url}}">Tıkla</mj-button>
      </mj-column>
    </mj-section>
  </mj-body>
</mjml>
```

Compile sonucu: 200+ satır Outlook-uyumlu, mobile-responsive HTML.

## 2. Hangi problemi çözüyor?

### 2.1. Cross-client uyumluluk

Yukarıdaki email HTML cehenneminden kurtulmak. MJML test edilmiş, broad coverage.

### 2.2. Maintainability

200 satır HTML yazmak yerine 30 satır MJML. Designer veya developer kolayca değiştirir. Brand update'i tek yerden.

### 2.3. i18n

Aynı template farklı dilde içerikle çağrılır. Layout sabit, sadece variable değişir.

### 2.4. Template management

Production'da template değişikliği için **redeploy** olmasın. Template'ler DB'de versioned olsun, hot-reload edilebilsin.

## 3. Nasıl çalışıyor? (Çalışma prensibi)

### 3.1. Render pipeline

```text
Notification trigger
       │
       ▼
NotificationUseCase
       │
       ├── Load template (key + locale)
       │   - DB'den MJML source çek
       │   - Versioned (template_versions table)
       │
       ├── Render variables
       │   - Mustache / Pebble / Handlebars
       │   - {{name}}, {{date}}, {{url}}
       │
       ├── Compile MJML → HTML
       │   - mjml-java library
       │   - Caching layer (compiled template hash)
       │
       ├── Extract plain text
       │   - HTML → text (jsoup + sanitizer)
       │
       └── EmailMessage(htmlBody, textBody, subject)
```

### 3.2. Subject + body templating

Email subject de template'lenir:

```text
Template key: ATTENDANCE_ABSENT_PARENT
Locale: tr
Subject: "{{student_name}} {{date}} tarihinde okula gelmedi"
HTML body: <mjml>...</mjml>
Text body: (otomatik HTML→text)
```

Push notification:

```text
Template key: NEW_MESSAGE_PUSH
Locale: tr
Title: "{{sender_name}}"
Body: "{{body_preview}}"
Data: { "type": "message", "conversation_id": "{{conversation_id}}" }
```

SMS:
```text
Template key: ATTENDANCE_ABSENT_SMS
Locale: tr
Body: "Sayin {{parent_name}}, {{student_name}} {{date}} {{lesson}} dersine girmedi. {{school_phone}}"
```

### 3.3. Template storage

```sql
CREATE TABLE notification_templates (
    template_key VARCHAR(128) NOT NULL,
    locale VARCHAR(8) NOT NULL,         -- 'tr', 'en', 'tr-TR', vs.
    channel VARCHAR(16) NOT NULL,       -- 'EMAIL', 'SMS', 'PUSH'
    version INTEGER NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT false,
    subject TEXT,                        -- Email için
    body_mjml TEXT,                      -- Email için MJML source
    body_text TEXT,                      -- SMS/text fallback
    title TEXT,                          -- Push için
    data_json JSONB,                     -- Push data payload template
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (template_key, locale, channel, version)
);

CREATE UNIQUE INDEX idx_templates_active
    ON notification_templates(template_key, locale, channel)
    WHERE is_active = true;
```

`UNIQUE WHERE is_active`: her template-locale-channel için tek aktif versiyon.

### 3.4. Versioning ve rollback

```text
1. Yeni template version oluştur (is_active=false)
2. Admin panel preview → onay
3. Aktif yap:
   UPDATE notification_templates
     SET is_active = (version = NEW_VERSION)
     WHERE template_key=? AND locale=? AND channel=?
4. Rollback: önceki version'a is_active çevir.
```

Tek SQL ile rollback < 1 saniye.

### 3.5. Caching

Template her notification'da DB'den çekilmez. Redis cache:
- `template:{key}:{locale}:{channel}:active` → version + serialized template
- TTL 5 dakika veya invalidation event ile

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. MJML rendering library

Java için `mjml-java` (kotlin wrapper veya direkt JNI ile) ya da **mjml-cli** subprocess. Performance için **in-process** tercih edilir.

Alternatif: **Pebble template engine** ile MJML'i variable substitute, sonra HTTP'den **MJML API** veya yerel Node.js subprocess'i çağırmak. Lumix'te basit yol: **mjml4j** (Java port) veya **JNode** ile MJML çağrı.

### 4.2. Template engine

**Pebble** seçimi (Mustache veya Handlebars alternatif). Sebep:
- Java native
- Inheritance + macro
- Auto-escape (XSS koruması)
- Spring entegrasyonu var

```xml
<mj-text>Merhaba {{ parent_name }}!</mj-text>
<mj-text>{{ student_name }} {{ date | date('dd MMMM yyyy', 'tr-TR') }} tarihinde okula gelmedi.</mj-text>
```

### 4.3. i18n strategy

Template her dil için ayrı kayıt. Fallback chain:

```text
locale="tr-TR" → not found → "tr" → not found → "en" (default)
```

ResourceBundle yaklaşımı kullanılmaz; her template-locale ayrı kayıt. Çünkü email yapısı bir dilde farklı olabilir (örn. Arapça RTL).

### 4.4. Common partials (header, footer)

Lumix template'leri ortak header/footer paylaşır. MJML `<mj-include>` ile:

```xml
<mjml>
  <mj-include path="header.mjml" />
  <mj-body>
    <!-- template content -->
  </mj-body>
  <mj-include path="footer.mjml" />
</mjml>
```

Lumix'te include'lar DB'de "fragment" olarak tutulur, render öncesi inline edilir.

### 4.5. Preview tool

Admin panel'inde template preview:
- Template seç
- Sample variables doldur (UI'dan)
- "Preview" → MJML compile → iframe'de göster
- Send test email (kendi adresine)

Production'a deploy'dan önce QA için kritik.

### 4.6. Brand variables

Her tenant kendi brand'ı:

```json
{
  "logo_url": "https://omer.lumix.io/branding/logo.png",
  "primary_color": "#0066CC",
  "footer_text": "Ömer Okulları © 2026",
  "support_email": "destek@omer-okullari.k12.tr"
}
```

Template render'da bu brand variable'lar her zaman implicit eklenir:

```xml
<mj-section background-color="{{ brand.primary_color }}">
  <mj-image src="{{ brand.logo_url }}" />
</mj-section>
```

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. MJML vs alternatifleri

| Alternatif | Neden elendi |
|---|---|
| **Raw HTML email** | Cross-client cehennem; designer maliyeti yüksek |
| **Foundation for Emails (Inky)** | Benzer abstraction ama community küçük, mjml daha aktif |
| **Maizzle** | Tailwind-based, modern ama bağımlılık ağırlığı |
| **MailerSend, SendGrid Dynamic Templates** | Provider lock-in, multi-provider strategy ile uyumsuz |
| **MJML** | Açık kaynak, geniş community, broad client support |

### 5.2. Template engine seçimi

| Engine | Avantaj | Dezavantaj |
|---|---|---|
| **Mustache** | Basit, logic-less | Conditional/loop sınırlı |
| **Handlebars** | Mustache + helper | Java port aktif değil |
| **Pebble** (seçim) | Spring-friendly, secure, feature-rich | Mustache standardı değil |
| **Thymeleaf** | Spring web standart | Email template için fazla heavy |

### 5.3. Trade-off

- **MJML compile cost**: Her email render = MJML compile. Redis cache + memoization şart.
- **Subprocess overhead** (JNode): mjml-java tercih edilir; subprocess kullanılacaksa pool gerekir.
- **Designer learning curve**: MJML syntax öğrenmek lazım; HTML/CSS bilenler için kolay.
- **i18n maintenance**: Her template için her dil = ekstra row. Lumix MVP TR + EN; sonra arttırılır.

### 5.4. Ne değişirse kararı tekrar gözden geçiririz?

- MJML community küçülürse Foundation for Emails veya direct HTML'e geri dönülür (büyük effort).
- Designer-friendly visual builder (Stripo, BeeFree) tercih edilirse template format değişir.

## 6. Pratik örnek

### 6.1. Sample email template (MJML)

```xml
<mjml>
  <mj-head>
    <mj-title>Karne Hazır</mj-title>
    <mj-preview>{{ student_name }} öğrencimizin karnesi yayınlandı</mj-preview>
    <mj-attributes>
      <mj-all font-family="Helvetica, Arial, sans-serif" />
    </mj-attributes>
  </mj-head>
  <mj-body background-color="#F5F5F5">

    <mj-section background-color="{{ brand.primary_color }}" padding="20px">
      <mj-column>
        <mj-image src="{{ brand.logo_url }}" width="120px" />
      </mj-column>
    </mj-section>

    <mj-section background-color="#FFFFFF" padding="40px">
      <mj-column>
        <mj-text font-size="24px" font-weight="bold">
          Karne Hazır
        </mj-text>
        <mj-text>
          Sayın {{ parent_name }},
        </mj-text>
        <mj-text>
          <strong>{{ student_name }}</strong> öğrencimizin <strong>{{ term_name }}</strong>
          dönemine ait karnesi yayınlandı. Aşağıdaki butona tıklayarak karnesini
          görüntüleyebilirsiniz.
        </mj-text>
        <mj-button href="{{ report_card_url }}" background-color="{{ brand.primary_color }}">
          Karneyi Görüntüle
        </mj-button>
        <mj-text>
          Sorularınız için: {{ brand.support_email }}
        </mj-text>
      </mj-column>
    </mj-section>

    <mj-section background-color="#F5F5F5" padding="20px">
      <mj-column>
        <mj-text font-size="11px" color="#666666" align="center">
          {{ brand.footer_text }}
        </mj-text>
      </mj-column>
    </mj-section>

  </mj-body>
</mjml>
```

### 6.2. Java template renderer

```java
@Component
@RequiredArgsConstructor
public class EmailTemplateRenderer {

    private final NotificationTemplateRepository templateRepository;
    private final BrandConfigProvider brandConfigProvider;
    private final PebbleEngine pebbleEngine;
    private final MjmlCompiler mjmlCompiler;
    private final RenderedTemplateCache cache;

    public RenderedEmail render(String templateKey, Locale locale,
                                 UUID tenantId, Map<String, Object> variables) {
        NotificationTemplate template = loadTemplate(templateKey, locale, Channel.EMAIL);

        Map<String, Object> context = new HashMap<>(variables);
        context.put("brand", brandConfigProvider.forTenant(tenantId));

        String subject = renderString(template.subject(), context);
        String mjmlSource = renderString(template.bodyMjml(), context);

        String htmlBody = cache.computeIfAbsent(
                hash(mjmlSource), () -> mjmlCompiler.compile(mjmlSource));
        String textBody = htmlToText(htmlBody);

        return new RenderedEmail(subject, htmlBody, textBody);
    }

    private String renderString(String tpl, Map<String, Object> vars) {
        try (StringWriter writer = new StringWriter()) {
            pebbleEngine.getTemplate(tpl).evaluate(writer, vars);
            return writer.toString();
        } catch (IOException e) {
            throw new TemplateRenderException("Failed to render template", e);
        }
    }

    private String htmlToText(String html) {
        return Jsoup.parse(html).text();
    }

    private NotificationTemplate loadTemplate(String key, Locale locale, Channel channel) {
        return templateRepository.findActive(key, locale, channel)
                .or(() -> templateRepository.findActive(key, locale.getLanguage(), channel))
                .or(() -> templateRepository.findActive(key, "en", channel))
                .orElseThrow(() -> new TemplateNotFoundException(key, locale, channel));
    }
}
```

### 6.3. MJML compiler (subprocess or library)

```java
@Component
public class MjmlCompiler {

    private final ProcessExecutor executor; // veya mjml4j library

    public String compile(String mjmlSource) {
        ProcessResult result = executor.command("mjml", "-i")
                .redirectInput(new ByteArrayInputStream(mjmlSource.getBytes()))
                .readOutput(true)
                .timeout(5, TimeUnit.SECONDS)
                .execute();
        if (result.getExitValue() != 0) {
            throw new MjmlCompilationException(result.outputString());
        }
        return result.outputString();
    }
}
```

Production'da daha hızlı: `mjml4j` library veya HTTP MJML service (containerized).

### 6.4. Preview endpoint

```java
@RestController
@RequestMapping("/api/v1/notification-templates")
@RequiredArgsConstructor
public class TemplatePreviewController {

    private final EmailTemplateRenderer renderer;

    @PostMapping("/{templateKey}/preview")
    @PreAuthorize("hasAuthority('notification:template:preview')")
    public ResponseEntity<TemplatePreviewResponse> preview(
            @PathVariable String templateKey,
            @RequestParam(defaultValue = "tr") String locale,
            @RequestBody Map<String, Object> sampleVariables,
            @AuthenticationPrincipal UserPrincipal user) {
        RenderedEmail rendered = renderer.render(
                templateKey, Locale.forLanguageTag(locale),
                user.tenantId(), sampleVariables);
        return ResponseEntity.ok(new TemplatePreviewResponse(
                rendered.subject(), rendered.htmlBody(), rendered.textBody()));
    }
}
```

### 6.5. Test email

```java
@PostMapping("/{templateKey}/send-test")
@PreAuthorize("hasAuthority('notification:template:test')")
public ResponseEntity<Void> sendTest(
        @PathVariable String templateKey,
        @RequestParam(defaultValue = "tr") String locale,
        @RequestParam String toAddress,
        @RequestBody Map<String, Object> sampleVariables,
        @AuthenticationPrincipal UserPrincipal user) {
    // Allow-list check
    if (!isAllowedTestRecipient(user, toAddress)) {
        return ResponseEntity.status(403).build();
    }
    testEmailUseCase.sendTest(templateKey, locale, user.tenantId(),
            toAddress, sampleVariables);
    return ResponseEntity.ok().build();
}
```

## 7. Dikkat edilecek tuzaklar

- **Plain HTML email yazma**. Cross-client cehennem. MJML zorunlu.
- **Variable escape eksik**. XSS riski. Pebble auto-escape ON.
- **Compile sonucu cache yok**. Her email için MJML compile = CPU yağmuru.
- **Template DB'de plain HTML**. MJML source tut, render-time compile.
- **i18n fallback yok**. tr-TR yoksa tr; tr yoksa en; en yoksa exception. Chain implement et.
- **Brand variables hard-coded**. Tenant başına brand config; template'e implicit inject.
- **Test email allow-list yok**. Production'da rastgele adrese send = spam riski.
- **Versioning yok**. Aktif template yanlış değişti, rollback yok = production incident.
- **Locale tag inconsistent**. `tr_TR` vs `tr-TR` vs `tr`. IETF BCP 47 standart kullan.
- **Subject character limit**. Bazı client'lar 60+ char truncate. Subject'i kısa tut.
- **Preview text (mj-preview) atlama**. Inbox preview line'ı boş = düşük açılma oranı.
- **Image URL relative**. Email'de mutlaka absolute URL; CDN'den gelen logo.
- **SMS template MJML kullanma**. SMS plain text; MJML email-only.
- **Push template payload size**. FCM 4 KB limit; data payload + notification toplam.

## 8. Diğer konularla ilişkisi

- [Notification Adapter Pattern](./notification-adapter-pattern)
- [Email Providers](./email-providers) — MJML→HTML email body
- [SMS Providers](./sms-providers) — text-only template
- [Push Providers](./push-providers) — title/body/data template
- [Frontend Admin Panel](../11-admin-panels) — template editor UI
- [i18n](../frontend-architecture) — locale yönetimi cross-system

## 9. Daha derine inmek için

- MJML — [Official Documentation](https://documentation.mjml.io/)
- MJML — [Try Online](https://mjml.io/try-it-live)
- Pebble — [Documentation](https://pebbletemplates.io/)
- IETF BCP 47 — [Language tags](https://datatracker.ietf.org/doc/html/rfc5646)
- Litmus — [Email Client Compatibility](https://litmus.com/) (testing service)
- Araştırma keyword'leri: `mjml java integration`, `responsive email template best practices`, `email template versioning database`, `cross client email testing`

## 10. Sözlük

- **MJML** — Cross-client uyumlu email HTML üreten abstraction language.
- **Template** — Variable substitution ile render edilen mesaj şablonu.
- **Variable substitution** — Template içindeki `{{var}}` placeholder'larının runtime değerlerle değiştirilmesi.
- **Pebble** — Java template engine (Mustache-benzeri).
- **Locale** — Dil + bölge kodu (`tr-TR`, `en-US`).
- **Fallback chain** — Eksik lokal için sıralı geri düşme (`tr-TR` → `tr` → `en`).
- **Versioning** — Template'in geçmiş versiyonlarını tutma, rollback için.
- **Preview tool** — Render edilmiş template'i tarayıcıda gösteren admin UI.
- **MJML compile** — MJML source → cross-client HTML dönüşümü.
- **Brand variables** — Tenant brand bilgisi (logo, renk, footer).
- **Preview text** — Inbox'ta subject altında görünen ek tanıtım satırı (`<mj-preview>`).
- **Partial / include** — Header/footer gibi paylaşılan template fragment'i.
