---
title: Push Notifications (FCM + APNs, Provider Adapter)
description: Lumix mobile push notification mimarisi — FCM (Android), APNs (iOS), notification-service backend adapter, token registration, permission UX, deep link.
sidebar_position: 3
---

## Bu sayfa ne anlatıyor?

Lumix mobile uygulamasının **push notification** mimarisi. Bu sayfada şunlar öğrenilecek:

- Push notification nedir, Android ve iOS'ta nasıl çalışır
- FCM (Firebase Cloud Messaging) ve APNs (Apple Push Notification service) ne işe yarar
- Lumix'in **provider-agnostic adapter** mantığı (backend tarafında)
- Mobile app'te token registration, permission isteme, foreground/background handler
- Notification payload modeli (data + visual)
- Deep link ile notification → ekran açma
- Privacy ve KVKK boyutu

Bu sayfa, [backend notification-service](../00-overview/02-technology-stack-decisions) doc'unun mobile ayağıdır.

## 1. Push notification nedir? (Sıfırdan)

**Push notification**, uygulamanız kapalıyken bile telefonunda bildirim göstermek. "WhatsApp mesajı geldi", "Yarın 09:00 toplantınız var" gibi.

İki anahtar oyuncu:

- **APNs** = Apple'ın push servisi. iOS cihazlara push göndermek için **mecburi** geçiş noktası. Apple operasyonunda.
- **FCM** = Google'ın push servisi. Android için yaygın; aslında FCM'in altında **GMS (Google Mobile Services)** veya **HMS (Huawei)** var.

### Günlük hayattan analoji

PTT (posta dağıtımı) düşün:

- **APNs / FCM** = PTT merkez ofisi. Sadece o mektubu kabul eder ve kullanıcıya götürür.
- **Token** = kullanıcının posta kodu. Telefon kuruluyor → PTT'den unique kod alıyor → bu kodu sunucuna kayıt ediyor.
- **Backend (notification-service)** = göndericisi (Lumix). Mektubu (notification payload) PTT'ye veriyor: "Şu posta koduna git."
- **Cihaz** = ev. PTT mektubu eve bırakıyor; ev sahibi bildirimi görüyor.

### Mobile push akışı (kuş bakışı)

```
1. App ilk açıldığında:
   - Notification permission iste (iOS şart, Android 13+ şart)
   - Device token al (APNs veya FCM'den)
   - Backend'e POST /api/v1/devices/register
        body: { token, platform: 'ios'|'android', tenantId, deviceId }

2. Backend bir olay → push gönderme kararı:
   - notification-service tetiklenir (Kafka event veya direct call)
   - User'ın device token'ları DB'den okunur
   - Provider adapter (FCM / APNs) çağrılır

3. APNs/FCM cihaza ulaştırır:
   - App background ise → OS bildirim gösterir
   - App foreground ise → notification handler tetiklenir → in-app banner

4. Kullanıcı bildirimi tap'ler:
   - Deep link tetiklenir (örn. lumix://messages/conv-123)
   - App açılır, ilgili ekrana navigate
```

## 2. Hangi problemi çözüyor?

Push olmadan:

- Kullanıcı mesaj geldiğinde haberi olmaz, sürekli app açıp bakması gerek
- Önemli duyurular (devamsızlık, ödev teslim) zamanında ulaşmaz
- Engagement düşer, app kullanılmaz hale gelir

Push ile:

- **Real-time bildirim**: backend event → 1-2 sn içinde cihaz
- **Background delivery**: app kapalı/arka planda olsa bile
- **Targeting**: belirli kullanıcı/grup/topic
- **Rich content**: title, body, badge, image, action button

## 3. Nasıl çözüyor? (Mekanizma)

### 3.1. iOS — APNs akışı

```
App: requestPermission()
  → OS sorar: "Bildirimleri kabul ediyor musun?"
  → Kullanıcı izin verir
                ↓
App: getDeviceToken()
  → iOS APNs'e gider, device token döner
  → 64 hex karakterlik string
                ↓
App: backend POST /devices/register { token }
                ↓
Backend bir olay olduğunda APNs'e POST gönderir:
  - apns-topic: bundle id (Lumix.ios)
  - device token
  - payload: { aps: { alert: { title, body }, badge, sound }, ...custom data }
                ↓
APNs cihaza pushlar
                ↓
iOS: bildirimi göster (background) veya handler'a yolla (foreground)
```

### 3.2. Android — FCM akışı

```
App startup: Firebase SDK init
                ↓
App: messaging().getToken()
  → FCM token döner (~200 karakterlik string)
                ↓
App: backend POST /devices/register { token, platform: 'android' }
                ↓
Backend bir olay olduğunda FCM'e POST gönderir:
  - server key veya OAuth (FCM HTTP v1 API)
  - device token
  - payload: { notification: {...}, data: {...} }
                ↓
FCM cihaza pushlar
                ↓
Android: bildirimi göster veya app içinde handler tetikle
```

### 3.3. Lumix'in provider adapter mantığı (backend)

Backend doğrudan FCM/APNs SDK'sına bağımlı olmasın diye:

```
notification-service
  ├── adapter: PushProvider
  │     ├── send(deviceToken, payload): Result
  │     └── platform: 'ios' | 'android'
  ├── FCMAdapter implements PushProvider
  ├── APNsAdapter implements PushProvider
  └── (gelecekte) OneSignalAdapter implements PushProvider
```

Müşteri kendi sağlayıcısını isterse adapter değiştirilebilir.

### 3.4. Foreground vs background

- **Background** (app kapalı veya başka app aktif): OS doğal bildirim gösterir
- **Foreground** (Lumix açık): bildirim göstermeyebilir; app içinde toast/banner gösterilir
- **Killed** (app tamamen kapalı): Android'de bildirim gelir; tap'lendiğinde app açılır + payload tetikler

## 4. Biz nasıl kullanıyoruz? (Lumix)

### 4.1. Kararlar

| Konu | Karar |
|---|---|
| iOS provider | **APNs** doğrudan veya FCM üzerinden iOS | Backend adapter |
| Android provider | **FCM** (HTTP v1 API) |
| Library | **`@react-native-firebase/messaging`** + **`@notifee/react-native`** (rich display, action button) |
| Permission UX | İlk login sonrası soft prompt; OS prompt onayla |
| Token refresh | Token rotate olabilir → her uygulama açılışında token check + backend re-register |
| Deep link | **`lumix://`** custom scheme + **universal links** (iOS) / **app links** (Android) |
| Notification topic | **User-based** (her cihaz bir user için kayıtlı); topic broadcast minimal |
| Foreground display | **Notifee** ile in-app banner |
| Privacy | Notification body'de **PII minimize**: "Yeni mesaj — Hüseyin öğretmen" |

### 4.2. Setup (mobile)

`apps/mobile/package.json` (özet):
```json
{
  "dependencies": {
    "@react-native-firebase/app": "^20.0.0",
    "@react-native-firebase/messaging": "^20.0.0",
    "@notifee/react-native": "^7.8.0"
  }
}
```

iOS: `ios/Lumix/AppDelegate.mm` içinde Firebase init, APNs registration; Xcode Capabilities → Push Notifications + Background Modes → Remote notifications.

Android: `android/app/google-services.json` (Firebase console'dan), `AndroidManifest.xml` `POST_NOTIFICATIONS` permission (API 33+), `FirebaseMessagingService` registration.

### 4.3. Permission isteme

```ts
// shared/lib/push/permission.ts
import messaging from '@react-native-firebase/messaging';
import { Platform, PermissionsAndroid } from 'react-native';

export async function requestPushPermission(): Promise<boolean> {
  if (Platform.OS === 'ios') {
    const authStatus = await messaging().requestPermission();
    return (
      authStatus === messaging.AuthorizationStatus.AUTHORIZED ||
      authStatus === messaging.AuthorizationStatus.PROVISIONAL
    );
  }
  if (Platform.OS === 'android' && Number(Platform.Version) >= 33) {
    const r = await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
    );
    return r === PermissionsAndroid.RESULTS.GRANTED;
  }
  return true;
}
```

UX akışı: **önce app içinde modal göster** ("Yeni mesaj/duyuru için bildirimleri açar mısınız?") → kullanıcı kabul ederse OS prompt'unu tetikle. Bu, OS prompt'una doğrudan red gelmesini azaltır.

### 4.4. Token registration

```ts
// shared/lib/push/registerDevice.ts
import messaging from '@react-native-firebase/messaging';
import { Platform } from 'react-native';
import { store } from '@lumix/core/store';
import { deviceApi } from '@lumix/core/devices/api';

export async function registerDeviceForPush() {
  const token = await messaging().getToken();
  if (!token) return;

  const platform = Platform.OS === 'ios' ? 'ios' : 'android';
  await store.dispatch(
    deviceApi.endpoints.registerDevice.initiate({
      token,
      platform,
      deviceModel: Platform.constants.Brand ?? 'unknown',
      appVersion: '1.0.0',
    }),
  );

  // Token rotation
  messaging().onTokenRefresh(async (newToken) => {
    await store.dispatch(
      deviceApi.endpoints.registerDevice.initiate({
        token: newToken,
        platform,
      }),
    );
  });
}
```

`deviceApi`:

```ts
// packages/core/src/devices/api.ts
export const deviceApi = lumixApi.injectEndpoints({
  endpoints: (build) => ({
    registerDevice: build.mutation<void, { token: string; platform: string; deviceModel?: string; appVersion?: string }>({
      query: (body) => ({ url: '/api/v1/devices/register', method: 'POST', body }),
    }),
    unregisterDevice: build.mutation<void, { token: string }>({
      query: (body) => ({ url: '/api/v1/devices/unregister', method: 'POST', body }),
    }),
  }),
});
```

### 4.5. Notification handler (foreground)

```ts
// shared/lib/push/handler.ts
import { useEffect } from 'react';
import messaging from '@react-native-firebase/messaging';
import notifee, { AndroidImportance } from '@notifee/react-native';
import { handleDeepLink } from './deepLink';

export function usePushHandlers() {
  useEffect(() => {
    // Foreground: Notifee ile in-app banner göster
    const unsubFg = messaging().onMessage(async (remote) => {
      const channelId = await notifee.createChannel({
        id: 'default',
        name: 'Genel',
        importance: AndroidImportance.HIGH,
      });
      await notifee.displayNotification({
        title: remote.notification?.title,
        body: remote.notification?.body,
        data: remote.data,
        android: { channelId, smallIcon: 'ic_notification' },
      });
    });

    // Notification tap → deep link
    const unsubOpen = messaging().onNotificationOpenedApp((remote) => {
      handleDeepLink(remote.data?.deepLink as string | undefined);
    });

    // Killed durumda tıklandı, app açıldı:
    messaging().getInitialNotification().then((initial) => {
      if (initial?.data?.deepLink) handleDeepLink(initial.data.deepLink as string);
    });

    // Notifee tap (foreground notification)
    notifee.onForegroundEvent(({ type, detail }) => {
      if (type === 1 /* PRESS */ && detail.notification?.data?.deepLink)
        handleDeepLink(detail.notification.data.deepLink as string);
    });

    return () => {
      unsubFg();
      unsubOpen();
    };
  }, []);
}
```

### 4.6. Background handler (Android)

Killed/background state'te native thread çalışır:

```ts
// index.js (app root)
import messaging from '@react-native-firebase/messaging';

messaging().setBackgroundMessageHandler(async (remote) => {
  // örn. badge güncelle, silent push işleme
});
```

### 4.7. Deep link router

```ts
// shared/lib/push/deepLink.ts
import { navigationRef } from '@/app/navigation/navigationRef';

export function handleDeepLink(url: string | undefined) {
  if (!url || !navigationRef.isReady()) return;
  // lumix://messages/conv-123
  if (url.startsWith('lumix://messages/')) {
    const conversationId = url.replace('lumix://messages/', '');
    navigationRef.navigate('MessageThread', { conversationId });
  }
  if (url.startsWith('lumix://attendance/')) {
    const classroomId = url.replace('lumix://attendance/', '');
    navigationRef.navigate('Attendance', { classroomId });
  }
}
```

### 4.8. Notification payload modeli (Lumix standart)

Backend gönderirken:

```json
{
  "notification": {
    "title": "Yeni mesaj",
    "body": "Hüseyin öğretmen size mesaj gönderdi"
  },
  "data": {
    "type": "message.new",
    "conversationId": "uuid",
    "tenantId": "uuid",
    "deepLink": "lumix://messages/uuid"
  },
  "android": { "priority": "high" },
  "apns": { "payload": { "aps": { "badge": 3, "sound": "default" } } }
}
```

- **`notification`** field → OS gösterir
- **`data`** field → app handler'a gider; deep link burada
- **`deepLink`** her zaman dahil; tap → ekran

### 4.9. Logout sırasında

```ts
export async function unregisterDeviceOnLogout() {
  const token = await messaging().getToken();
  if (token) {
    await store.dispatch(
      deviceApi.endpoints.unregisterDevice.initiate({ token }),
    );
  }
  await messaging().deleteToken();
}
```

## 5. Neden bu seçim? (Alternatifler)

| Alternatif | Neden elendi |
|---|---|
| **OneSignal** | Hızlı setup ama provider bağımlılığı; pricing tier; data privacy konu |
| **Pusher Beams** | Benzer concern; Lumix self-host önceliği |
| **Custom signal (WebSocket)** | App kapalıyken çalışmaz; sadece foreground gerçek-time için kullanıyoruz |
| **SMS / Email fallback** | Maliyet ve gecikme yüksek; push primary, diğerleri ek |
| **APNs direkt + FCM direkt** ✅ | En düşük maliyet, full control, provider bağımsızlığı (backend adapter ile gelecekte değiştirilebilir) |

### Trade-off

- **FCM Google bağımlılığı**: Çin pazarına girersek HMS adapter eklemek gerekecek (uzak ihtimal)
- **APNs sertifika yönetimi**: Yenileme yıllık; CI'da automated
- **iOS provisional notifications**: kullanıcı izin vermeden bile "sessiz" notif gönderilebilir; "kullanmaya başla" engagement için kullanırız

### Ne zaman gözden geçiririz?

- Multi-tenant müşteri kendi push sağlayıcısını dayatırsa → adapter zaten hazır
- Apple/Google policy değişikliği

## 6. Pratik örnek — End-to-end "yeni mesaj geldi"

### Backend (özet)

```kotlin
// communication-service: yeni mesaj geldiğinde
@EventListener(MessageCreatedEvent::class)
fun onMessageCreated(event: MessageCreatedEvent) {
    val recipients = userRepo.findByConversation(event.conversationId)
        .filter { it.id != event.senderId }
    notificationProducer.send(NotificationEvent(
        type = "message.new",
        userIds = recipients.map { it.id },
        tenantId = event.tenantId,
        payload = mapOf(
            "title" to "Yeni mesaj",
            "body" to "${event.senderName} size mesaj gönderdi",
            "deepLink" to "lumix://messages/${event.conversationId}",
            "conversationId" to event.conversationId.toString()
        )
    ))
}
```

```kotlin
// notification-service: Kafka consumer
@KafkaListener(topics = ["notification.events.v1"])
fun consume(event: NotificationEvent) {
    val devices = deviceRepo.findByUserIds(event.userIds)
    devices.forEach { device ->
        val provider = when (device.platform) {
            "ios" -> apnsAdapter
            "android" -> fcmAdapter
            else -> return@forEach
        }
        provider.send(device.token, PushPayload(
            title = event.payload["title"] as String,
            body = event.payload["body"] as String,
            data = event.payload + ("type" to event.type)
        ))
    }
}
```

### Mobile (app açıkken, mesaj sayfasında değilken)

```
1. FCM/APNs → device
2. messaging().onMessage tetiklenir
3. Notifee in-app banner: "Yeni mesaj — Hüseyin öğretmen..."
4. Kullanıcı tap → handleDeepLink('lumix://messages/uuid')
5. navigationRef.navigate('MessageThread', { conversationId })
6. RTK Query: useListMessagesQuery({ conversationId }) tetiklenir
7. Mesaj listesi yüklenir, kullanıcı okur
```

## 7. Tuzaklar

- **Permission isteğini zorla**: OS prompt'tan "red" alınca bir daha sorulamaz; soft prompt + manual settings yönlendir.
- **Foreground'da OS bildirim göstermesini beklemek**: iOS foreground'da otomatik göstermez; Notifee veya custom UI lazım.
- **Token'ı registration sırasında DB'de unique olarak tutmamak**: Aynı device token birden fazla user'a kayıtlı olabilir (account switch); en son user kazanır.
- **Token'ı logout'ta silmemek**: Eski user'a hâlâ push gider → veri sızıntısı.
- **Notification body'de PII**: "Ahmet 5 günden 4 gün okula gelmedi" → kilit ekranında görünür; veri ihlali. Genel sözlerle yaz, deep link açınca detay göster.
- **Silent push backend'in arka plana bağımlı task tetikleyici sayma**: iOS'ta delivery garantisi yok, OS karar verir.
- **APNs sertifika expire**: Yıllık yenileme; CI'da hatırlatma kur.
- **FCM legacy server key kullanmak**: Deprecated; HTTP v1 API + OAuth2 service account kullan.
- **iOS background mode unutmak**: Xcode Capabilities → Background Modes → Remote notifications aktif olmalı.
- **Notifee channel oluşturmamak (Android 8+)**: Channel olmadan notif gözükmez veya basit gözükür.
- **Deep link path collision**: `lumix://x` ve `lumix://x/y` route eşleşmesi belirsiz; explicit prefix yap.
- **Test device gerçek cihazda**: APNs simulator'da güvenilir test etmez (production environment vs sandbox); fiziksel cihaz veya TestFlight.

## 8. Diğer konularla ilişkisi

- [React Native Foundation](./react-native-foundation) — temel kurulum
- [Shared Business Logic](./shared-business-logic) — RTK Query device endpoint
- [App Store + Google Play Distribution](./app-store-google-play-distribution) — APNs sertifikası dağıtım pipeline'ında
- [Notification Provider Adapter (backend)](../00-overview/02-technology-stack-decisions) — backend tarafı
- [Permission Cache](../frontend-architecture/frontend-permission-cache) — push ile permission değişimi tetiklenebilir
- [Token Storage](../frontend-architecture/frontend-token-storage) — Keychain mobile

## 9. Daha derine

- React Native Firebase Messaging: https://rnfirebase.io/messaging/usage
- Notifee: https://notifee.app/
- FCM HTTP v1: https://firebase.google.com/docs/cloud-messaging/migrate-v1
- APNs: https://developer.apple.com/documentation/usernotifications
- Search keywords:
  - `react native firebase messaging fcm apns ios android`
  - `notifee channel android importance`
  - `react native deep link push notification navigation`
  - `apns http/2 token-based authentication`
  - `push notification permission ux soft prompt`

## 10. Sözlük

- **Push notification** — Sunucu tetiklemeli, cihaza gönderilen ve OS tarafından gösterilen bildirim.
- **APNs (Apple Push Notification service)** — iOS push servisi.
- **FCM (Firebase Cloud Messaging)** — Google'ın Android push servisi (eski adı GCM).
- **Device token** — Cihazın push servisinden aldığı unique kimlik.
- **Foreground / Background / Killed** — App'in çalışma durumları; her birinde notification farklı işlenir.
- **Deep link** — URL şeması ile belirli ekrana açılan link (`lumix://messages/123`).
- **Universal link (iOS) / App link (Android)** — HTTPS URL'ler, app açılır; web fallback'i var.
- **Provisional authorization (iOS)** — Permission istemeden sessiz push gönderme yetkisi.
- **Notification channel (Android 8+)** — Bildirim kategorisi; importance, sound, vibration kanalda tanımlanır.
- **Silent push** — UI göstermeyen, sadece data taşıyan push.
- **Provider adapter** — Push sağlayıcısı değişirse backend kodunun değişmemesini sağlayan soyutlama.
