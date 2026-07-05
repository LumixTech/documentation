---
title: Push Provider Detayı — FCM ve APNs Unified
description: FCM (Android + Web), APNs (iOS), unified service via FCM v1 API, topic vs token-based push, Lumix mobile entegrasyonu, device token lifecycle.
sidebar_position: 4
---

## Bu sayfa ne anlatıyor?

Lumix'in **push notification** kanalını nasıl kurguladığını, **FCM (Firebase Cloud Messaging) v1 API** üzerinden **Android, iOS ve Web** unified push akışını, **APNs alternatif** stratejisini, **topic vs token-based** push arasındaki farkı, **device token lifecycle**'ını ve **mobile app entegrasyonu**nu anlatır. Push notification user'a en hızlı temas yolu; düzgün yönetilirse en güvenilir kanal.

## 1. Bu nedir? (Sıfırdan)

### 1.1. Günlük hayattan analoji

Telefonunda bir app açık değil ama bildirim geldi: "Mesaj geldi". Bu nasıl mümkün? Telefon **uyurken bile** Apple/Google'ın sunucularına bağlı kalır. Sunucu bir bildirim gelirse → telefonun OS'una iletir → OS app'i tetikler → bildirim ekranda.

App yapımcısı **doğrudan telefona** bildirim göndermez. Apple veya Google'ın **gateway**'lerine gönderir; onlar telefona iletir.

### 1.2. FCM ve APNs

İki temel push gateway:

- **APNs (Apple Push Notification service)** — iOS, iPadOS, macOS. Apple'a ait, Apple ekosistemiyle sıkı entegre.
- **FCM (Firebase Cloud Messaging)** — Android, iOS (APNs üzerinden), Web. Google'a ait, multi-platform.

Eskiden Android için ayrı SDK, iOS için ayrı SDK gerekirdi. **FCM v1 API** ile **unified**: FCM iOS push'larını APNs'ye **kendi** iletir. Yani Lumix bir tek FCM'e gönderir; Android'e direkt, iOS'a APNs aracılığıyla.

### 1.3. Device token

Push gönderebilmek için her cihazın **device token**'ı gerekir:

- Android: FCM registration token (cihaz başına unique string)
- iOS: APNs device token (FCM ile maskelenir; FCM token alınır)
- Web: VAPID + FCM web push subscription

Token cihaz dilini bilir. Yeniden kurulumda değişebilir; auth'unu kaybedebilir; expire olabilir. Token lifecycle yönetimi push'ın en kritik kısmı.

## 2. Hangi problemi çözüyor?

### 2.1. Real-time engagement

Web sayfası kapalıysa, mobile app açık değilse, kullanıcı **sistemden haberdar olmaz**. Push, app/sayfa kapalıyken bile bilgi iletme tek yol. WebSocket gibi long-lived connection değil; OS-level "bilgi sakla, app açıldığında ver" mekaniği.

### 2.2. Email/SMS'e göre avantaj

- **Ücretsiz** (provider'a ek bir tarife yok; sadece deliverability)
- **Anlık** (saniyeler içinde)
- **Etkileşim** (tap → app içindeki belirli ekrana git)
- **Rich** (görsel, action button, custom data)

### 2.3. SaaS scenario

Lumix'te:
- Yeni mesaj geldi → push
- Devamsızlık bildirimi → push (SMS fallback)
- Ödev verildi → push
- Karne hazırlandı → push (email primary)

Mobile app + Web app her ikisinde de aktif.

## 3. Nasıl çalışıyor? (Çalışma prensibi)

### 3.1. Push gönderim akışı

```text
Server (notification-service)
       │
       │ POST /v1/projects/{project_id}/messages:send
       │   Authorization: Bearer {oauth_token}
       │   { "message": { "token": "<device_token>", ... } }
       ▼
   FCM Gateway
       │
       ├── Android → cihaza direkt
       │
       └── iOS → APNs gateway → cihaza
                 (FCM bu yönlendirmeyi otomatik yapar)
                                       │
                                       ▼
                                  Cihaz OS
                                       │
                                       ▼
                                 App / notification UI
```

### 3.2. Token-based vs topic-based

| Mod | Hedef | Senaryo |
|---|---|---|
| **Token-based** | Tek cihaz token'ı | Kişiye özel notification ("Yeni mesajınız var") |
| **Topic-based** | Topic ismi (`/topics/tenant-abc-class-11A`) | Çoklu hedef ("11-A sınıfı veliler için duyuru") |
| **Device group** | Çoklu token | Bir kullanıcının birden fazla cihazı |

Lumix:
- **Token-based** default (her notification belirli user'a)
- **Topic-based** opsiyonel (sınıf-bazlı duyurular)
- **Device group** — bir user'ın multi-device durumunda (telefon + tablet)

### 3.3. Notification vs Data message

FCM iki tür mesaj destekler:

- **Notification message**: FCM SDK app'i otomatik bildirir, OS notification gösterir. App arka planda olsa bile.
- **Data message**: Custom data; app açıkken handle eder. Background'da iken iOS'ta sınırlı (special background mode lazım).

Lumix **notification + data hybrid** kullanır:
```json
{
  "message": {
    "token": "...",
    "notification": {
      "title": "Yeni Mesaj",
      "body": "Hüseyin Öğretmen size mesaj gönderdi"
    },
    "data": {
      "type": "message",
      "conversation_id": "uuid",
      "deep_link": "/messages/uuid"
    },
    "android": { "priority": "high" },
    "apns": { "headers": { "apns-priority": "10" } }
  }
}
```

### 3.4. Device token lifecycle

```text
1. App ilk açıldı → FCM SDK token üretir
2. App → POST /devices { token, platform, app_version }
3. notification-service → user_device_tokens tablosuna kaydet
4. Push gönderilecekken: SELECT token FROM user_device_tokens WHERE user_id = ?
5. Token expire/invalid → FCM 404 response → silent unregister + tablodan sil
6. App güncellendi veya yeniden kuruldu → yeni token → tekrar register
7. User çıkış yaptı → tablodan sil
```

Stale token = wasted push attempt. Periodic cleanup.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. FCM unified service

Lumix tüm push'larını FCM v1 API üzerinden gönderir:
- Android cihazlar: FCM direkt
- iOS cihazlar: FCM → APNs (FCM Apple Push certificate'ını kendi tutar)
- Web (PWA): FCM Web Push

Tek API, tek credential set (`service_account.json`). APNs certificate ayrıca FCM console'unda yüklenmiş.

### 4.2. User device tokens

```sql
CREATE TABLE user_device_tokens (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    user_id UUID NOT NULL,
    fcm_token VARCHAR(512) NOT NULL,
    platform VARCHAR(16) NOT NULL,    -- 'ANDROID', 'IOS', 'WEB'
    app_version VARCHAR(32),
    device_model VARCHAR(128),
    locale VARCHAR(8),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (fcm_token)
);

CREATE INDEX idx_udt_user ON user_device_tokens(user_id);
CREATE INDEX idx_udt_tenant ON user_device_tokens(tenant_id);
```

`UNIQUE(fcm_token)`: aynı token başka user'da olamaz; cihaz başka kullanıcıya geçtiyse onun token'ı geçersiz olur.

### 4.3. FCM service account credential

FCM v1 API **OAuth 2.0** ile auth. Google service account credential JSON Vault'ta:

```text
Vault path: kv/installation/{installation_id}/fcm/service-account
{
  "type": "service_account",
  "project_id": "lumix-omer-okullari",
  "private_key_id": "...",
  "private_key": "-----BEGIN PRIVATE KEY-----\n...",
  "client_email": "firebase-adminsdk-xxx@lumix-omer-okullari.iam.gserviceaccount.com",
  ...
}
```

Her installation kendi Firebase project'ine sahip (KVKK + tenant isolation).

### 4.4. Deep link / tap action

Push notification tap → app belirli ekrana açılmalı:

```text
Data payload:
  deep_link: "lumix://messages/{conversation_id}"
  type: "message"
```

App tap'i intercept eder, deep link parse eder, router'a verir.

Web push için:
```text
click_action: "https://omer.lumix.io/messages/abc-123"
```

### 4.5. Notification categories ve user preferences

User notification preferences:

```sql
CREATE TABLE user_notification_preferences (
    user_id UUID NOT NULL,
    category VARCHAR(64) NOT NULL,      -- 'MESSAGE', 'ATTENDANCE', 'HOMEWORK', 'BILLING'
    push_enabled BOOLEAN NOT NULL DEFAULT true,
    email_enabled BOOLEAN NOT NULL DEFAULT true,
    sms_enabled BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (user_id, category)
);
```

NotificationUseCase her category için preference check.

### 4.6. Token cleanup job

Daily scheduled Temporal workflow:
- Token kullanılmamış (last_used > 90 gün) → silinir
- FCM `UNREGISTERED` veya `INVALID_ARGUMENT` → silinir
- Test token (CI'da kullanılmış) → temizlik

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. FCM vs direct APNs vs OneSignal

| Konu | FCM | Direct APNs | OneSignal |
|---|---|---|---|
| Multi-platform | ✓ unified | iOS only | ✓ |
| Ücret | Ücretsiz | Ücretsiz | Free tier sınırlı |
| Lock-in | Google | Apple | OneSignal |
| Server-side complexity | Tek API | iOS için ayrı + token | Tek API |
| Data privacy | Google'a metadata | Apple'a metadata | OneSignal tracks |
| Türkiye uyum | OK | OK | OK (extra processor) |

**Lumix tercihi**: FCM. Adapter pattern sayesinde gerekirse APNs adapter ayrı yazılır.

### 5.2. OneSignal alternatifi

OneSignal **3rd party SaaS** — kolay kurulum ama:
- Pricing tier vendor-managed
- KVKK için extra data processor agreement
- Lock-in (template, segmentation OneSignal'da)

Lumix self-host felsefesi ile **OneSignal default değil**. Adapter yine var (müşteri isterse).

### 5.3. Trade-off

- **Google bağımlılığı**: FCM Google servisi; outage olursa push çalışmaz. Backup yok (APNs için direkt fallback adapter eklenebilir).
- **Quota**: FCM gönderim quota'ları (tipik 600,000 mesaj / dakika project başına). Lumix scale'inde sorun değil.
- **iOS background restriction**: Data-only message iOS'ta arka planda sınırlı; notification message tercih edilir.
- **Web push limitations**: Safari'de sınırlı destek (iOS 16.4+ ile düzeldi).

## 6. Pratik örnek

### 6.1. FCM adapter

```java
@Component
@RequiredArgsConstructor
public class FcmPushAdapter implements PushPort {

    private final FirebaseAppResolver firebaseResolver;
    private final UserDeviceTokenRepository tokenRepo;

    @Override
    public PushProviderId providerId() {
        return PushProviderId.FCM;
    }

    @Override
    public PushDeliveryResult send(PushMessage msg) {
        FirebaseApp app = firebaseResolver.resolveForTenant(msg.tenantId());
        FirebaseMessaging messaging = FirebaseMessaging.getInstance(app);

        Message.Builder builder = Message.builder()
                .setToken(msg.deviceToken())
                .setNotification(Notification.builder()
                        .setTitle(msg.title())
                        .setBody(msg.body())
                        .setImage(msg.imageUrl().orElse(null))
                        .build())
                .putAllData(msg.data())
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setNotification(AndroidNotification.builder()
                                .setClickAction(msg.deepLink().orElse(null))
                                .build())
                        .build())
                .setApnsConfig(ApnsConfig.builder()
                        .setAps(Aps.builder()
                                .setContentAvailable(true)
                                .setAlert(ApsAlert.builder()
                                        .setTitle(msg.title())
                                        .setBody(msg.body())
                                        .build())
                                .build())
                        .build());

        try {
            String messageId = messaging.send(builder.build());
            return PushDeliveryResult.success(messageId);
        } catch (FirebaseMessagingException e) {
            return handleFcmError(e, msg);
        }
    }

    private PushDeliveryResult handleFcmError(FirebaseMessagingException e, PushMessage msg) {
        switch (e.getErrorCode()) {
            case INVALID_ARGUMENT, UNREGISTERED -> {
                // Token expired or invalid
                tokenRepo.deleteByFcmToken(msg.deviceToken());
                return PushDeliveryResult.tokenInvalid(msg.deviceToken());
            }
            case QUOTA_EXCEEDED, UNAVAILABLE -> {
                return PushDeliveryResult.retryable(e.getMessage());
            }
            default -> {
                return PushDeliveryResult.failed(e.getErrorCode().name(), e.getMessage());
            }
        }
    }
}
```

### 6.2. FirebaseAppResolver

```java
@Component
@RequiredArgsConstructor
public class FirebaseAppResolver {

    private final VaultTemplate vaultTemplate;
    private final ConcurrentMap<UUID, FirebaseApp> apps = new ConcurrentHashMap<>();

    public FirebaseApp resolveForTenant(UUID tenantId) {
        UUID installationId = lookupInstallation(tenantId);
        return apps.computeIfAbsent(installationId, this::createApp);
    }

    private FirebaseApp createApp(UUID installationId) {
        try {
            VaultResponse vr = vaultTemplate.read(
                    "kv/installation/" + installationId + "/fcm/service-account");
            String json = vaultTemplate.opsForKeyValue("kv")
                    .get("installation/" + installationId + "/fcm/service-account")
                    .getRequiredData()
                    .toString();

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(
                            new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))))
                    .build();
            return FirebaseApp.initializeApp(options, "fcm-" + installationId);
        } catch (IOException e) {
            throw new IllegalStateException("FCM init failed", e);
        }
    }
}
```

### 6.3. Multi-device send

```java
@Service
@RequiredArgsConstructor
public class PushUseCase {

    private final FcmPushAdapter pushAdapter;
    private final UserDeviceTokenRepository tokenRepo;

    public void sendToUser(UUID userId, String title, String body,
                            Map<String, String> data, String deepLink) {
        List<UserDeviceToken> tokens = tokenRepo.findActiveByUserId(userId);
        if (tokens.isEmpty()) {
            log.info("No active push tokens for user: {}", userId);
            return;
        }

        for (UserDeviceToken token : tokens) {
            PushMessage msg = new PushMessage(
                    UuidV7Generator.generate(),
                    token.tenantId(),
                    token.fcmToken(),
                    token.platform(),
                    title, body, data,
                    Optional.empty(),
                    Optional.ofNullable(deepLink));

            PushDeliveryResult result = pushAdapter.send(msg);
            if (result.isTokenInvalid()) {
                tokenRepo.deleteById(token.id());
            }
        }
    }
}
```

### 6.4. Token register endpoint (mobile app uses this)

```java
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceTokenController {

    private final DeviceTokenUseCase useCase;

    @PostMapping
    public ResponseEntity<Void> register(
            @Valid @RequestBody DeviceTokenRegisterDto dto,
            @AuthenticationPrincipal UserPrincipal user) {
        useCase.register(user.userId(), user.tenantId(),
                dto.fcmToken(), dto.platform(),
                dto.appVersion(), dto.deviceModel(), dto.locale());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{token}")
    public ResponseEntity<Void> unregister(
            @PathVariable String token,
            @AuthenticationPrincipal UserPrincipal user) {
        useCase.unregister(user.userId(), token);
        return ResponseEntity.noContent().build();
    }
}
```

### 6.5. Topic subscribe

Sınıf bazlı duyuru için topic subscription:

```java
@Service
@RequiredArgsConstructor
public class PushTopicService {

    private final FirebaseAppResolver appResolver;

    public void subscribeToClassTopic(UUID tenantId, String fcmToken, UUID classId) {
        FirebaseApp app = appResolver.resolveForTenant(tenantId);
        String topicName = "tenant-" + tenantId + "-class-" + classId;
        FirebaseMessaging.getInstance(app)
                .subscribeToTopic(List.of(fcmToken), topicName);
    }

    public void publishToClassTopic(UUID tenantId, UUID classId,
                                     String title, String body) {
        FirebaseApp app = appResolver.resolveForTenant(tenantId);
        String topicName = "tenant-" + tenantId + "-class-" + classId;
        Message msg = Message.builder()
                .setTopic(topicName)
                .setNotification(Notification.builder()
                        .setTitle(title).setBody(body).build())
                .build();
        FirebaseMessaging.getInstance(app).send(msg);
    }
}
```

## 7. Dikkat edilecek tuzaklar

- **Token invalidation handle etmemek**. Stale token = wasted call. `UNREGISTERED` → sil.
- **Multi-device unutma**. Bir user'ın telefon + tablet'i; her ikisine de gönder.
- **Background mode iOS sınırlı**. Data-only message iOS'ta her zaman teslim edilmez; notification message ile gönder.
- **Topic'e PII koyma**. Topic isimleri Firebase Console'da görünür; tenant-internal id'ler kullan.
- **Service account JSON DB'de plain**. Vault'ta sakla, runtime resolve.
- **Per-installation Firebase project paylaşma**. KVKK + tenant isolation için ayrı project.
- **Quota aşımı**. Yüksek-throughput senaryoda batch endpoint (`send_all`) kullan; tek tek `send` rate limit'e takılır.
- **Click action / deep link test edilmemiş**. iOS ve Android farklı deep link convention; QA matrix.
- **Image size**. `notification.image` küçük (max 1 MB pratikte); büyük dosya delivery'i geciktirir.
- **Sound + badge ignored**. iOS sound/badge ayarları config'te eksik = sessiz notification.
- **Localized message**. Title/body locale-aware olmalı; template renderer'dan geçsin.
- **User preferences atlamak**. Push opt-out edilen user'a gönderme.

## 8. Diğer konularla ilişkisi

- [Notification Adapter Pattern](./notification-adapter-pattern)
- [Email Providers](./email-providers)
- [SMS Providers](./sms-providers)
- [MJML Template Rendering](./mjml-template-rendering) — push template
- [Frontend Mobile](../10-frontend-mobile) — token register
- [WebSocket](../07-realtime-websocket) — real-time alternatif
- [Domain Servisleri](../01-tenancy-and-domain-model/domain-services-overview) — notification-service

## 9. Daha derine inmek için

- Firebase — [Cloud Messaging Documentation](https://firebase.google.com/docs/cloud-messaging)
- FCM Admin SDK — [Java Reference](https://firebase.google.com/docs/reference/admin/java/reference/com/google/firebase/messaging/package-summary)
- Apple — [APNs Provider API](https://developer.apple.com/documentation/usernotifications/sending_push_notifications_using_command-line_tools)
- Web Push Protocol — [RFC 8030](https://datatracker.ietf.org/doc/html/rfc8030)
- VAPID — [RFC 8292](https://datatracker.ietf.org/doc/html/rfc8292)
- Araştırma keyword'leri: `fcm v1 api java integration`, `device token lifecycle best practices`, `fcm topic vs token`, `apns vs fcm comparison`

## 10. Sözlük

- **FCM (Firebase Cloud Messaging)** — Google'ın push notification gateway servisi.
- **APNs (Apple Push Notification service)** — Apple'ın push gateway'i.
- **Device token** — Cihazın push hedefi olarak tanımlayıcı string.
- **Token-based push** — Belirli bir cihaza gönderim.
- **Topic-based push** — Bir topic'e subscribe olmuş tüm cihazlara gönderim.
- **Device group** — Bir user'ın birden fazla cihazını grup olarak adresleme.
- **Notification message** — FCM SDK'sının OS notification olarak gösterdiği mesaj.
- **Data message** — App'in kendi handle ettiği custom data.
- **Service account** — FCM v1 API için OAuth credential.
- **VAPID** — Web push için public/private key tabanlı auth.
- **Deep link** — Notification tap → app içindeki belirli ekran açılması.
- **Stale token** — Geçersiz hale gelmiş device token.
