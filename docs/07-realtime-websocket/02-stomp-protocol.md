---
title: STOMP Protokolü
description: STOMP nedir, WebSocket üstüne abonelik/destination semantiği, frame yapısı, Spring @MessageMapping, simpMessagingTemplate, destination prefix (/app, /topic, /queue, /user).
sidebar_position: 2
---

## Bu sayfa ne anlatıyor?

WebSocket sana sadece **iki yönlü TCP soketi** verir; "kanal", "abonelik", "konu" gibi kavramlar yoktur. **STOMP** bu eksiği doldurur: WebSocket üstüne text-tabanlı bir **mesajlaşma protokolü** koyar. Bu sayfa STOMP'un frame yapısını, Lumix'in destination convention'larını (`/app`, `/topic`, `/queue`, `/user`), Spring'in `@MessageMapping`, `@SubscribeMapping` ve `simpMessagingTemplate` API'sini anlatır.

## 1. Bu nedir? (Sıfırdan)

**STOMP (Simple Text-Oriented Messaging Protocol)** = mesaj broker'larla konuşmak için tasarlanmış, text-tabanlı, basit bir protokol. WebSocket'in üzerine bindirilebilir (Lumix'in yaptığı), TCP üstüne de bindirilebilir.

WebSocket'e neden STOMP eklediğimizi şöyle düşün:
- **WebSocket** = sokağa düşen iki uçlu telefon kablosu. Sinyal iletiyor ama kim arıyor, hangi konu, nasıl bağlanıyor — yok.
- **STOMP** = kablonun üzerine bindirilen **telefon santralı**: "1234 numaraya bağlan", "yeni mesaj geldiğinde haber ver".

### STOMP frame yapısı

Bir STOMP frame metin-tabanlı, 4 parçadan oluşur:

```
COMMAND
header1:value1
header2:value2

Body^@
```

- `COMMAND` — `CONNECT`, `SUBSCRIBE`, `SEND`, `MESSAGE`, `UNSUBSCRIBE`, `DISCONNECT`, `ACK`, `NACK`
- Boş satır komut/header'ı body'den ayırır
- `^@` = NULL byte (frame sonu işareti)

Örnek CONNECT:

```
CONNECT
accept-version:1.2
host:lumix
login:bearer
passcode:eyJhbG...
heart-beat:25000,25000

^@
```

Örnek SUBSCRIBE:

```
SUBSCRIBE
id:sub-1
destination:/topic/attendance.class.11A
ack:auto

^@
```

Örnek SEND:

```
SEND
destination:/app/chat.send
content-type:application/json

{"text":"Selam"}^@
```

### Destination

STOMP'ta her mesaj bir **destination** ile gider. Bu broker'a anlam ifade eder. Spring konvensiyonu:

| Prefix | Anlam | Örnek |
|---|---|---|
| `/app/...` | Client'tan **uygulamaya** mesaj (server-side `@MessageMapping` çalışır) | `/app/chat.send` |
| `/topic/...` | **Publish-subscribe**, herkes abone olabilir | `/topic/attendance.class.11A` |
| `/queue/...` | **Point-to-point** (genelde tek dinleyici) | `/queue/notifications` |
| `/user/{user}/queue/...` | **User-specific** queue (sadece o kullanıcıya gider) | `/user/queue/notifications` |

## 2. Hangi problemi çözüyor?

### 2.1. Abonelik (subscription) yapısı
Raw WebSocket'te "hangi olayları almak istiyorum?" diye söyleyemezsin; tüm mesajlar gelir. STOMP'ta SUBSCRIBE ile spesifik destination'a abone olursun.

### 2.2. Çoklu konu tek soket
Aynı WebSocket bağlantısı üzerinden 10 farklı konuya abone olabilirsin. Her birinin ayrı `subscription id`'si var.

### 2.3. User-specific routing
"Bu mesajı sadece kullanıcı X'e gönder" — `/user/{X}/queue/...` ile STOMP bunu doğal yapar. Lumix `convertAndSendToUser` ile bunu kullanır.

### 2.4. Request/Reply pattern (correlation)
ACK, receipt header'lar ile "mesajın gitti mi?" doğrulaması.

### 2.5. Broker abstraction
STOMP standart bir protokol olduğu için RabbitMQ, ActiveMQ gibi external broker'larla aynı semantikte konuşabilirsin. Lumix bu broker yerine **Redis Pub/Sub backplane** kullansa da STOMP'un semantiğini koruyor — frontend STOMP konuşur, backplane içeride değişebilir.

## 3. Nasıl çözüyor? (Mekanizma + diyagram)

### 3.1. STOMP session lifecycle

```
[Client]                            [Server / Spring]
   │ WebSocket handshake (Authorization header)
   │ ─────────────────────────────► │
   │ ◄───────────────────────────── │ 101 Switching Protocols
   │
   │ CONNECT (login, heart-beat)
   │ ─────────────────────────────► │
   │ ◄───────────────────────────── │ CONNECTED (version, session)
   │
   │ SUBSCRIBE id=sub-1
   │ destination=/topic/foo
   │ ─────────────────────────────► │
   │
   │ SEND destination=/app/chat.send
   │ body={"text":"..."}
   │ ─────────────────────────────► │ @MessageMapping("/chat.send")
   │                                 │ → service → simpMessagingTemplate
   │                                 │
   │ ◄───────────────────────────── │ MESSAGE destination=/topic/foo
   │                                 │   body={...}
   │
   │ UNSUBSCRIBE id=sub-1
   │ ─────────────────────────────► │
   │
   │ DISCONNECT
   │ ─────────────────────────────► │
```

### 3.2. Spring `@MessageMapping` (client → server)

```
Client: SEND destination=/app/chat.send body={"text":"hi"}
                                              │
                                              ▼
                              Spring SimpleBroker / handler
                                              │
                                              ▼
                                @MessageMapping("/chat.send")
                                public void onSend(ChatMessage m, Principal p)
                                              │
                                              ▼
                                              Service çağrısı, DB write,
                                              outbox event...
```

Yani `/app/...` prefix'i ile gelen mesajlar **Spring controller'larına** uğrar.

### 3.3. `simpMessagingTemplate` (server → client)

Server-side'dan client'a mesaj göndermek için:

```java
simpMessagingTemplate.convertAndSend("/topic/foo", payload);
simpMessagingTemplate.convertAndSendToUser(userId, "/queue/notifications", payload);
```

- `convertAndSend` — destination'a abone olanlara fan-out
- `convertAndSendToUser` — özel olarak o user'a (login'de session bilgisinden çıkar)

**Önemli (Lumix kararı):** `convertAndSendToUser` **lokal session lookup yapar**. Yani Pod-2'de oturan kullanıcıya Pod-1'den `convertAndSendToUser` çağırırsan mesaj kaybolur. Çözüm: Redis Pub/Sub backplane + user→pod mapping. Detay: [User-Pod Mapping](./user-pod-mapping-and-reconnect).

### 3.4. SimpleBroker vs Full Broker Relay

Spring iki mod sunar:

| Mod | Açıklama | Lumix kullanır mı? |
|---|---|---|
| **SimpleBroker** | In-memory broker; mesajlar Spring pod'unda lokal yaşar | ✓ (lokal pod fan-out için) |
| **External broker relay** | Spring STOMP'u RabbitMQ/ActiveMQ'ya relay eder; çoklu pod broker üstünden konuşur | ✗ (Lumix RabbitMQ kullanmıyor) |

Lumix'in seçimi: **SimpleBroker + custom Redis Pub/Sub backplane**. SimpleBroker pod-içi mesaj rotalar; Redis pod'lar arası dağıtır. Detay [Redis Pub/Sub Backplane](./redis-pubsub-backplane).

### 3.5. User mapping (`/user/{X}/queue/...`)

Spring `UserDestinationResolver` user mapping yapar. Bir client `/user/queue/notifications` destination'ına SUBSCRIBE yaparsa, Spring bunu internal olarak `/queue/notifications-user{sessionId}` benzeri unique destination'a çevirir. Server `convertAndSendToUser(userId, "/queue/notifications", ...)` çağırdığında Spring user'ın aktif WebSocket session'larını bulup hepsine gönderir.

```
Backend:   simpMessagingTemplate.convertAndSendToUser("user-123",
                                                       "/queue/notifications",
                                                       payload);
              │
              ▼
   Spring: user "user-123"'ün lokal pod'daki session'larını bul
              │
              ▼
   Her session için MESSAGE frame gönder
```

**Sorun:** "lokal pod'daki" → cross-pod handle edilmiyor. Lumix bunu Redis backplane ile çözüyor (sonraki sayfalar).

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Destination convention

| Pattern | Kullanım |
|---|---|
| `/app/{aggregate}.{action}` | Client → server commands. Örn: `/app/chat.send`, `/app/attendance.mark` |
| `/topic/{aggregate}.{scope-id}` | Pub/sub broadcast. Örn: `/topic/attendance.class.11A`, `/topic/notification.tenant.{id}` |
| `/user/queue/{purpose}` | User-specific. Örn: `/user/queue/notifications`, `/user/queue/auth.revoked` |

### 4.2. Subscribe yetkisi

Her SUBSCRIBE Lumix'te yetki kontrolünden geçer:

```java
@Component
public class StompChannelSecurityInterceptor implements ChannelInterceptor {

    @Override
    public Message<?> preSend(Message<?> msg, MessageChannel ch) {
        StompHeaderAccessor acc = StompHeaderAccessor.wrap(msg);
        if (StompCommand.SUBSCRIBE.equals(acc.getCommand())) {
            String dest = acc.getDestination();
            Principal user = acc.getUser();
            if (!destinationAuthz.canSubscribe(user, dest)) {
                throw new MessageDeliveryException("forbidden");
            }
        }
        return msg;
    }
}
```

Yani saldırgan `/topic/attendance.class.X` (başka sınıf) için SUBSCRIBE deneyemez.

### 4.3. Payload formatı

- Content-Type: `application/json`
- Envelope:
  ```json
  {
    "type": "AttendanceMarked",
    "event_id": "uuid-v7",
    "occurred_at": "...",
    "tenant_id": "...",
    "data": { ... }
  }
  ```

### 4.4. Ack semantiği

Lumix'te `ack:auto` (default) kullanılır. "Missed events" durumunda client manuel fetch endpoint'ten kontrol eder; STOMP-level NACK üzerinden tekrar gönderim yok (gereksiz karmaşa). Detay [Reconnect & Missed Events](./user-pod-mapping-and-reconnect).

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

| Alternatif | Tartışma |
|---|---|
| **Raw WebSocket + custom JSON protocol** | Subscription/destination/user routing'i elle yazmak; framework desteği yok. **Elendi.** |
| **Socket.IO** | Spring entegrasyon zayıf, kendi protokolü, broker abstraction yok. **Elendi.** |
| **MQTT over WebSocket** | IoT odaklı; QoS karmaşası overkill. **Elendi.** |
| **STOMP** | ✓ Spring native, abonelik + user routing + frame'ler hazır. |

### Trade-off'lar

- **STOMP `convertAndSendToUser` lokal sınırlama:** Lumix Redis Pub/Sub backplane ile bunu aşıyor. Detay [Redis Pub/Sub Backplane](./redis-pubsub-backplane).
- **Spring SimpleBroker tek pod-içinde:** Çoklu pod için backplane şart.
- **STOMP frame parsing maliyeti:** Text frame parse maliyeti var, ama mesaj boyutuna göre ihmal edilebilir.
- **MQTT/AMQP kadar gelişmiş QoS yok:** Lumix'in `auto-ack` semantiği bu trade-off'u kabul ediyor; eventual consistency + missed events fetch ile telafi.

## 6. Pratik örnek

### 6.1. Spring config (destination prefix'leri)

```java
@Override
public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.enableSimpleBroker("/topic", "/queue")
            .setHeartbeatValue(new long[]{25_000, 25_000})
            .setTaskScheduler(heartbeatScheduler());
    registry.setApplicationDestinationPrefixes("/app");
    registry.setUserDestinationPrefix("/user");
}
```

### 6.2. `@MessageMapping` ile mesaj alma

```java
@Controller
@RequiredArgsConstructor
public class ChatStompController {

    private final ChatService chatService;
    private final SimpMessagingTemplate broker;

    @MessageMapping("/chat.send")
    public void onSend(@Payload @Valid SendChatMessage cmd, Principal principal) {
        UUID userId = UUID.fromString(principal.getName());
        ChatMessage saved = chatService.save(userId, cmd);

        // Class topic'e fan-out (tüm sınıf üyeleri)
        broker.convertAndSend("/topic/chat.class." + cmd.classId(),
            new ChatMessageEvent(saved));
    }
}
```

### 6.3. `simpMessagingTemplate` ile push

```java
@Service
@RequiredArgsConstructor
public class NotificationPushService {

    private final SimpMessagingTemplate broker;

    public void pushToTenant(UUID tenantId, NotificationEvent ev) {
        broker.convertAndSend("/topic/notification.tenant." + tenantId, ev);
    }

    public void pushToUser(UUID userId, NotificationEvent ev) {
        broker.convertAndSendToUser(userId.toString(), "/queue/notifications", ev);
    }
}
```

### 6.4. Subscribe yetki kontrolü

```java
@Component
@RequiredArgsConstructor
public class DestinationAuthz {

    private static final Pattern CLASS_TOPIC =
        Pattern.compile("^/topic/attendance\\.class\\.([0-9a-fA-F-]+)$");

    private final ScopeResolver scopeResolver;

    public boolean canSubscribe(Principal principal, String destination) {
        UUID userId = UUID.fromString(principal.getName());
        // Always allowed:
        if (destination.startsWith("/user/")) return true;

        Matcher m = CLASS_TOPIC.matcher(destination);
        if (m.matches()) {
            UUID classId = UUID.fromString(m.group(1));
            return scopeResolver.isClassInScope(userId, classId);
        }

        // Default deny for unmatched patterns
        return false;
    }
}
```

### 6.5. Frontend STOMP client (stomp.js)

```ts
import { Client } from '@stomp/stompjs';

const stomp = new Client({
  brokerURL: 'wss://api.lumix.example.com/ws',
  connectHeaders: {
    Authorization: `Bearer ${store.getState().auth.accessToken}`,
  },
  heartbeatIncoming: 25000,
  heartbeatOutgoing: 25000,
  reconnectDelay: 0,                 // manuel exponential backoff (sonraki sayfa)
  debug: (s) => console.debug(s),
});

stomp.onConnect = () => {
  // User-specific notifications
  stomp.subscribe('/user/queue/notifications', (frame) => {
    const ev = JSON.parse(frame.body);
    store.dispatch(notificationActions.received(ev));
  });

  // Class topic
  const classId = '...';
  stomp.subscribe(`/topic/attendance.class.${classId}`, (frame) => {
    const ev = JSON.parse(frame.body);
    store.dispatch(attendanceActions.updated(ev));
  });
};

stomp.onStompError = (frame) => {
  console.error('STOMP error', frame);
};

stomp.activate();
```

### 6.6. Send mesaj frontend

```ts
stomp.publish({
  destination: '/app/chat.send',
  body: JSON.stringify({ classId: '...', text: 'Selam' }),
  headers: { 'content-type': 'application/json' },
});
```

## 7. Dikkat edilecek tuzaklar

- **`/topic/*` SUBSCRIBE'a yetki kontrolü koymamak.** Saldırgan her topic'e abone olur, hassas veri sızar. **Kural:** `StompChannelSecurityInterceptor` ile SUBSCRIBE'a authz uygula.
- **Topic adı içinde user-controlled içerik.** "/topic/chat." + user.id" yerine resource-id (class id) güvenli. Topic injection senaryosuna dikkat.
- **`convertAndSendToUser` cross-pod sandığını sanmak.** Lokal Spring SimpleBroker pod'unun ötesini görmez. **Çözüm:** Redis Pub/Sub backplane.
- **Heartbeat yok.** Soğuk bağlantı + load balancer idle kill. **Kural:** STOMP heartbeat `25000,25000`.
- **Message size limit yok.** Bir client büyük frame ile DoS. **Kural:** `setMessageSizeLimit(64*1024)`.
- **`ack:client-individual` modu kullanmak.** Lumix bunun karmaşıklığını üstlenmiyor; `auto` + missed events fetch yeterli.
- **STOMP frame body'sinde sensitive log.** Spring debug `setLogLevel(TRACE)` body'leri dump eder. **Kural:** production'da STOMP debug kapalı.
- **JWT'yi STOMP CONNECT body'sinde göndermek (visible) ve sonra log'lamak.** Sensitive data leak. **Kural:** log'lardan auth header maskele.
- **Subscription leak.** Component unmount'ta `unsubscribe` çağırmamak → memory leak + dead subscription'lara mesaj gönderme. **Kural:** her subscribe için unsubscribe path.
- **STOMP retry / ack ile guaranteed delivery sandığını sanmak.** STOMP delivery guarantee zayıftır. **Kural:** kritik event'ler için missed events fetch + idempotent client handling.

## 8. Diğer konularla ilişkisi

- [WebSocket Temelleri](./websocket-fundamentals) — alttaki katman
- [Redis Pub/Sub Backplane](./redis-pubsub-backplane) — `convertAndSend`'in cross-pod davranışını sağlayan adaptör
- [User-Pod Mapping & Reconnect](./user-pod-mapping-and-reconnect) — `convertAndSendToUser` cross-pod çözümü
- [Organizational Scope Resolver](../04-authentication-authorization/organizational-scope-resolver) — SUBSCRIBE yetkisi kontrolü

## 9. Daha derine inmek için

- STOMP: [Specification 1.2](https://stomp.github.io/stomp-specification-1.2.html)
- Spring: [STOMP Over WebSocket](https://docs.spring.io/spring-framework/reference/web/websocket/stomp.html)
- Spring: [User Destinations](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/user-destination.html)
- @stomp/stompjs: [Client API](https://stomp-js.github.io/stomp-websocket/)
- Search keywords:
  - `stomp protocol frame format`
  - `spring websocket destinations`
  - `simpMessagingTemplate convertAndSendToUser`
  - `stomp subscribe authorization`
  - `websocket subprotocol negotiation`

## 10. Sözlük

- **STOMP** — Simple Text-Oriented Messaging Protocol; WebSocket üstüne abonelik semantiği bindiren protokol.
- **Frame** — STOMP'un birim mesajı (COMMAND + headers + body + NULL).
- **Destination** — STOMP mesajının nereye gideceğini söyleyen string (`/topic/...`, `/queue/...`, `/app/...`).
- **SUBSCRIBE / UNSUBSCRIBE** — Client'ın bir destination'a abone olma/olmaktan çıkma frame'i.
- **MESSAGE** — Server'dan client'a mesaj frame'i.
- **`@MessageMapping`** — Spring'in `/app/...` mesajlarını route ettiği handler annotation.
- **`simpMessagingTemplate`** — Server-side server→client mesaj API'si.
- **SimpleBroker** — Spring'in in-memory STOMP broker'ı (pod-lokal).
- **Broker Relay** — Spring'in external broker'a (RabbitMQ vb.) mesajı relay etme modu. Lumix kullanmıyor.
- **User Destination** — `/user/{user}/queue/...` ile user-specific routing.
- **Ack** — Mesajın işlendiğinin doğrulaması (`auto`, `client`, `client-individual`).

