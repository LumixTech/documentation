---
title: User-Pod Mapping & Reconnect Stratejisi
description: convertAndSendToUser cross-pod problemi, Redis Hash user:pod:{userId} mapping, reconnect exponential backoff + missed events fetch endpoint, pod restart davranışı, heartbeat tuning.
sidebar_position: 4
---

## Bu sayfa ne anlatıyor?

WebSocket Lumix'te neden "client hangi pod'a bağlı?" bilgisini tutuyoruz, bu Redis Hash nasıl kurulur, pod restart edince ne oluyor, frontend bağlantı koptuğunda nasıl reconnect ediyor, kayıp event'leri nasıl yakalıyor? Bu sayfa Lumix WebSocket katmanının **dayanıklılık (resilience)** stratejisini anlatır.

## 1. Bu nedir? (Sıfırdan)

WebSocket bağlantısı **persistent TCP** olduğundan, bir kullanıcının soketi her zaman **tek bir pod'a** bağlıdır. "User-pod mapping" = hangi user hangi pod'a bağlı bilgisini tutan, Redis Hash tabanlı bir lookup table.

Neden? Çünkü `convertAndSendToUser(userId, ...)` çağrısı, kullanıcının bağlı olduğu pod'u bilmeden mesajı doğru yere yönlendiremez. [Redis Pub/Sub Backplane](./03-redis-pubsub-backplane.md) sayfasındaki user-specific channel (`ws:user:{podName}`) için bu mapping gerek.

```
Redis Hash:  user:pod:{userId} → "academic-service-7d4-abcde"
```

### Reconnect ne demek?

Bağlantı koparsa (ağ, pod restart, idle timeout) frontend yeniden bağlanmalı. **"Naïf"** reconnect: anında yeniden dene. **Olgun:** exponential backoff + jitter + missed events fetch.

### Günlük analoji

Restoranın bir masasındasın (pod), garsonun (TCP) siparişini taşıyor. Garson izne çıktı (pod restart):
- Naïf: kalkıp başka garson çağırırsın, hangi siparişin geldiğini bilmezsin (missed events kayıp).
- Olgun: yeni garson masaya gelince "ben az önce X getiriyordum" der ve kayıpları telafi eder.

## 2. Hangi problemi çözüyor?

### 2.1. Cross-pod user message routing
Pod-1 mesaj yayınlamak istiyor, kullanıcı Pod-2'de. Mapping olmadan "her pod'a yayınla" demek wasteful (her pod tüm user mesajını alır + filtreler).

### 2.2. Pod restart toleransı
Pod düştü, kullanıcının bağlantısı koptu. Frontend yeniden bağlandığında **kayıp event'leri** nasıl alacak?

### 2.3. Thundering herd reconnect
1000 kullanıcı aynı anda bağlantı kaybetti (pod restart). Hepsi anında reconnect denerse sunucu yine kilitlenir. **Jitter** zorunlu.

### 2.4. Heartbeat timeouts
Sessizliğe gömülmüş bağlantılar fark edilemezse "ghost session" birikir. Lokal pod heartbeat + load balancer idle ayarı doğru olmalı.

### 2.5. Mapping stale (ölü kayıt)
Pod düştü, mapping silinmedi → diğer pod'lar hâlâ "kullanıcı oradaymış" diye yönlendirir. **TTL + heartbeat update** zorunlu.

## 3. Nasıl çözüyor? (Mekanizma + diyagram)

### 3.1. Mapping lifecycle

```
[Pod-1] WebSocket SessionConnectEvent (kullanıcı bağlandı)
     │
     ▼
   userId = principal.getName()
   sessionId = headerAccessor.getSessionId()
     │
     ▼
   Redis:
     HSET user:pod:{userId} pod "<self-pod-name>"
                              session "<session-id>"
                              connected_at "<now>"
     EXPIRE user:pod:{userId} 60s            (heartbeat ile yenilenir)

     SADD pod:users:{self-pod} {userId}      (pod'un user listesi)


[Pod-1] her 30sn (heartbeat tick)
     │
     ▼
   Her aktif user için EXPIRE user:pod:{userId} 60s


[Pod-1] WebSocket SessionDisconnectEvent
     │
     ▼
   Redis: HDEL user:pod:{userId}
           SREM pod:users:{self-pod} {userId}
```

### 3.2. Pod restart davranışı

```
                  Pod-1 düşüyor
                       │
                       ▼
            ┌──────────────────────┐
            │  Spring shutdown     │
            │  hook (best-effort)  │
            │  - mapping temizle    │
            │  - graceful close WS │
            └──────────┬───────────┘
                       │
                       ▼
                  (SIGKILL veya OOM)
                       │
                       ▼
        Frontend WebSocket onclose tetiklenir
                       │
                       ▼
            ┌──────────────────────┐
            │  K8s yeni Pod-1 ayağa │
            │  kaldırır (Pod-1')    │
            └──────────┬───────────┘
                       │
                       ▼
       Frontend reconnect (exp. backoff)
                       │
                       ▼
       Yeni connect → Load Balancer → herhangi pod
       (muhtemelen Pod-1' veya Pod-2 veya Pod-3)
                       │
                       ▼
       user:pod:{userId} yeni pod'a yazılır
                       │
                       ▼
       Frontend "missed events" endpoint'ten kayıpları al
```

### 3.3. Mapping TTL ve heartbeat

Her 30sn'de pod kendi user'larının mapping TTL'ini yeniler:

```
Scheduled: every 30s
  SMEMBERS pod:users:{self-pod} → tüm userId'ler
  EXPIRE her user:pod:{userId} 60s
```

Pod aniden öldü → 60sn içinde mapping kendiliğinden silinir. Diğer pod'lar mesajı yanlış yönlendirmemek için stale mapping'i sürekli refresh check eder.

### 3.4. Reconnect strategy (frontend)

```
Connect attempt #N başarısız olduğunda bekle:
  delay = min(MAX_DELAY,
              BASE_DELAY * 2^N * (1 + jitter))

  BASE_DELAY = 1000ms
  MAX_DELAY  = 30000ms
  jitter     = random(-0.3, +0.3)

#1: 1s + jitter
#2: 2s + jitter
#3: 4s + jitter
#4: 8s + jitter
#5: 16s + jitter
#6: 30s + jitter (cap)
```

### 3.5. Missed events fetch

Reconnect sonrası frontend "ben en son şunu görmüştüm" diyerek kayıpları çeker:

```
GET /api/v1/realtime/missed-events
  Query: since=<last_event_ts veya last_event_id>
         channels=/topic/attendance.class.11A,/user/queue/notifications

Response:
{
  "events": [ ... ],
  "as_of": "..."
}
```

Sunucu bu sorguyu:
- Audit/event store'dan (Kafka log, ES, dedicated event_log) okuyarak
- Tenant + scope filter ile
- since cursor'undan itibaren

idempotent şekilde döndürür. Frontend bu event'leri tekrar uyguladığında **idempotent dispatch** (event_id ile dedup) yapar.

### 3.6. Heartbeat tuning

```
STOMP heartbeat:    25000 / 25000
Spring task scheduler:  pool=2, prefix=ws-heartbeat-
WebSocket idle close: 60s (heartbeat'siz)
Kong/Traefik idle:  90s (heartbeat'in 2-3 katı)
```

Frontend ek olarak `setInterval` ile "ping check": 30sn'de pong'a yanıt yoksa client-side close + reconnect.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Redis key matrix

| Key | Tip | TTL | Açıklama |
|---|---|---|---|
| `user:pod:{userId}` | Hash | 60sn (heartbeat refresh) | Pod, session, connected_at |
| `pod:users:{podName}` | Set | yok (manual) | O pod'a bağlı user listesi |
| `user:sessions:ws:{userId}` | Set | yok (manual) | Kullanıcının aktif tüm WS session id'leri (multi-tab) |

### 4.2. Multi-tab davranışı

Bir kullanıcı 3 sekmede açık → 3 WebSocket session, 3 ayrı `sessionId`. Hepsi aynı pod'a düşerse `user:sessions:ws:{userId}` set'i 3 sessionId tutar; pod farklıysa **mapping en son connect'i** taşır (write-last-wins) ya da `user:pod:{userId}` Hash içinde session listesi tutulur.

Lumix kararı: **multi-pod aynı user**'a izin veriliyor. `user:pod:{userId}` HASH'i `{pod1: [sid1, sid2], pod2: [sid3]}` formatı yerine ayrı bir tasarım:

```
user:pods:{userId} → Hash { podA: count, podB: count }
```

`publishToUser` her pod'a yayınlamak yerine, pod'un user'a sahip olduğu count'a göre o pod'a tek mesaj atar. Pratikte çoğu kullanıcı tek pod'da → optimize edilmiş yol mevcut.

### 4.3. Disconnect cleanup

`@EventListener SessionDisconnectEvent`:

```java
@EventListener
public void onDisconnect(SessionDisconnectEvent e) {
    String sessionId = e.getSessionId();
    UUID userId = extractUserId(e);
    podMap.unregister(userId, sessionId, selfPod);
}
```

### 4.4. Missed events store

Lumix'in `/realtime/missed-events` endpoint'i şu kaynaklardan okur:
- **Kafka audit topic** (sliding window 24h, replayable)
- **PostgreSQL `realtime_events` cache table** (son 1h, partition'lı)

Maliyet: her event hem WebSocket hem persistent store'a gider. Çoğu event zaten audit'e gittiği için ek maliyet düşük.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

| Alternatif | Tartışma |
|---|---|
| **Sticky session (LB-level)** | Pod restart = tüm kullanıcılar etkilenir. **Elendi.** |
| **Pod'lara user broadcast (her mesajı her pod'a)** | 100 pod × N user = bandwidth israfı. **Elendi.** |
| **Mapping in-memory (no Redis)** | Cross-pod görünmez. **Elendi.** |
| **Redis Hash + TTL refresh (Lumix)** | ✓ Esnek, cross-pod görünür, stale temizlik otomatik. |
| **Naïf reconnect (immediate retry)** | Thundering herd → server kilit. **Elendi.** |
| **Exponential backoff + jitter (Lumix)** | ✓ Standart practice. |
| **No missed events fetch (kaybı tolere et)** | Bazı event'ler kritik (notification, mesaj). **Elendi.** |
| **Missed events fetch endpoint (Lumix)** | ✓ Eventual completeness garanti. |

### Trade-off'lar

- **Heartbeat refresh ek Redis yükü.** 1000 user × 30sn = saniyede ~33 HSET/EXPIRE. İhmal edilebilir.
- **TTL race:** Pod yeniden başlatılırken eski mapping varsa, yeni connect attempt eski pod'a yönlendirilebilir. **Çözüm:** mapping write-last-wins + 60sn TTL.
- **Missed events idempotency:** Frontend duplicate event görebilir. **Çözüm:** event_id dedup.
- **Multi-pod aynı user complexity:** Çoğu durumda tek pod'a düşer; multi-pod kodu nadiren tetiklenir.

## 6. Pratik örnek

### 6.1. UserPodMappingService

```java
@Component
@RequiredArgsConstructor
public class UserPodMappingService {

    private final StringRedisTemplate redis;

    @Value("${HOSTNAME:unknown-pod}")
    private String selfPod;

    private static final Duration TTL = Duration.ofSeconds(60);

    public void register(UUID userId, String sessionId) {
        String key = "user:pod:" + userId;
        Map<String, String> hash = Map.of(
            "pod", selfPod,
            "session", sessionId,
            "connected_at", Instant.now().toString());
        redis.opsForHash().putAll(key, hash);
        redis.expire(key, TTL);

        redis.opsForSet().add("pod:users:" + selfPod, userId.toString());
    }

    public void unregister(UUID userId, String sessionId) {
        // Sadece bu pod'un kaydını siliyoruz
        String key = "user:pod:" + userId;
        String currentPod = (String) redis.opsForHash().get(key, "pod");
        if (selfPod.equals(currentPod)) {
            String currentSession = (String) redis.opsForHash().get(key, "session");
            if (sessionId.equals(currentSession)) {
                redis.delete(key);
            }
        }
        redis.opsForSet().remove("pod:users:" + selfPod, userId.toString());
    }

    public String findPod(UUID userId) {
        return (String) redis.opsForHash().get("user:pod:" + userId, "pod");
    }

    @Scheduled(fixedDelay = 30_000)
    public void refreshTTLs() {
        Set<String> myUsers = redis.opsForSet().members("pod:users:" + selfPod);
        if (myUsers == null) return;
        for (String userId : myUsers) {
            redis.expire("user:pod:" + userId, TTL);
        }
    }
}
```

### 6.2. WebSocket session event listeners

```java
@Component
@RequiredArgsConstructor
public class WebSocketSessionLifecycle {

    private final UserPodMappingService mapping;

    @EventListener
    public void onConnect(SessionConnectedEvent event) {
        StompHeaderAccessor h = StompHeaderAccessor.wrap(event.getMessage());
        Principal p = h.getUser();
        if (p == null) return;
        UUID userId = UUID.fromString(p.getName());
        String sessionId = h.getSessionId();
        mapping.register(userId, sessionId);
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        Principal p = event.getUser();
        if (p == null) return;
        UUID userId = UUID.fromString(p.getName());
        mapping.unregister(userId, event.getSessionId());
    }
}
```

### 6.3. Missed events controller

```java
@RestController
@RequestMapping("/api/v1/realtime")
@RequiredArgsConstructor
public class MissedEventsController {

    private final RealtimeEventStore eventStore;
    private final ScopeResolver scopeResolver;
    private final RequestContext ctx;

    @GetMapping("/missed-events")
    @PreAuthorize("isAuthenticated()")
    public MissedEventsResponse get(
            @RequestParam Instant since,
            @RequestParam(required = false) List<String> channels) {

        if (since.isBefore(Instant.now().minus(Duration.ofHours(24)))) {
            throw new BadRequestException("since_too_far_in_past");
        }

        ScopeResult scope = scopeResolver.resolveScope(ctx.userId(), ctx.tenantId());

        List<RealtimeEvent> events = eventStore.fetchSince(
            since, ctx.tenantId(), scope, channels);

        return new MissedEventsResponse(events, Instant.now());
    }
}
```

### 6.4. Frontend reconnect logic

```ts
import { Client } from '@stomp/stompjs';

class LumixWebSocket {
  private stomp: Client;
  private attempt = 0;
  private lastEventTs: string | null = null;

  constructor() {
    this.stomp = new Client({
      brokerURL: 'wss://api.lumix.example.com/ws',
      connectHeaders: () => ({
        Authorization: `Bearer ${store.getState().auth.accessToken}`,
      }),
      heartbeatIncoming: 25000,
      heartbeatOutgoing: 25000,
      reconnectDelay: 0,
      debug: () => {},
    });

    this.stomp.onConnect = this.onConnect.bind(this);
    this.stomp.onWebSocketClose = this.onClose.bind(this);
    this.stomp.onStompError = this.onError.bind(this);
  }

  private async onConnect() {
    this.attempt = 0;
    this.subscribeAll();
    await this.fetchMissedEvents();
  }

  private onClose() {
    this.attempt += 1;
    const base = 1000, max = 30000;
    const expo = Math.min(max, base * Math.pow(2, this.attempt - 1));
    const jitter = (Math.random() * 0.6 - 0.3) * expo;
    const delay = Math.max(0, expo + jitter);
    console.warn(`[WS] disconnected. Retrying in ${Math.round(delay)}ms (attempt ${this.attempt})`);
    setTimeout(() => this.stomp.activate(), delay);
  }

  private onError(frame: any) {
    console.error('[WS] STOMP error', frame.headers, frame.body);
  }

  private subscribeAll() {
    this.stomp.subscribe('/user/queue/notifications', (frame) => {
      const ev = JSON.parse(frame.body);
      this.lastEventTs = ev.occurred_at;
      store.dispatch(notificationActions.received(ev));
    });
  }

  private async fetchMissedEvents() {
    if (!this.lastEventTs) return;
    const r = await api.get('/api/v1/realtime/missed-events', {
      params: { since: this.lastEventTs }
    });
    for (const ev of r.data.events) {
      store.dispatch(realtimeRouter(ev));      // idempotent dispatcher
    }
  }

  connect() {
    this.stomp.activate();
  }
}
```

### 6.5. Idempotent dispatcher (event_id dedup)

```ts
const seenEventIds = new LRU<string, true>({ max: 5000 });

export const realtimeRouter = (ev: RealtimeEvent) =>
  (dispatch: AppDispatch) => {
    if (seenEventIds.has(ev.event_id)) return;
    seenEventIds.set(ev.event_id, true);

    switch (ev.type) {
      case 'AttendanceMarked':
        return dispatch(attendanceActions.updated(ev.data));
      case 'NotificationCreated':
        return dispatch(notificationActions.received(ev.data));
      // ...
    }
  };
```

## 7. Dikkat edilecek tuzaklar

- **Mapping TTL yok.** Pod aniden öldü → mapping kalır → diğer pod'lar yanlış yönlendirir. **Kural:** TTL + heartbeat refresh.
- **Reconnect anında.** Thundering herd → server kilit. **Kural:** exp backoff + jitter.
- **Missed events endpoint authz olmadan.** Saldırgan başka kullanıcı event'lerini çekebilir. **Kural:** authz + scope.
- **`since` parametresi unbounded.** Saldırgan since=1970-01-01 ile DB drown. **Kural:** max 24h backstop.
- **Event_id idempotency yok.** Frontend duplicate event görür, UI flaş eder. **Kural:** seenEventIds dedup.
- **WebSocket session disconnect event'ini guarantee sanmak.** Pod crash'lerinde event yayınlanmaz. **Çözüm:** TTL ile self-healing.
- **Multi-pod aynı user'da yanlış lookup.** "user X tek pod'da" varsayımı bozulur. **Kural:** Hash count'lar ile multi-pod desteği.
- **Heartbeat interval mismatch (client 30s, server 60s idle).** Client heartbeat yetmezse server kapatır. **Kural:** server idle > client heartbeat × 2.
- **Reconnect sırasında access token expired.** STOMP CONNECT 401. **Çözüm:** önce `/auth/refresh`, sonra WS reconnect.
- **`pod:users:{podName}` set'i temizlenmez.** Pod restart sonrası eski set kalır. **Çözüm:** Pod start'ta `DEL pod:users:{selfPod}`.

## 8. Diğer konularla ilişkisi

- [WebSocket Temelleri](./01-websocket-fundamentals.md) — heartbeat ve connection ownership
- [STOMP Protokolü](./02-stomp-protocol.md) — `convertAndSendToUser` semantiği
- [Redis Pub/Sub Backplane](./03-redis-pubsub-backplane.md) — backplane bu mapping'i kullanır
- [Session & Device Lifecycle](../04-authentication-authorization/02-session-device-lifecycle.md) — WS session vs auth session ayrımı
- [Cache Invalidation](../08-caching-redis/04-cache-invalidation.md) — event-driven UI updates

## 9. Daha derine inmek için

- AWS: "WebSocket scale-out patterns"
- Cloudflare: "WebSocket reconnect strategies"
- Spring: [WebSocket Session Events](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/application-context-events.html)
- "Exponential backoff and jitter" — AWS Architecture blog
- Search keywords:
  - `websocket reconnect exponential backoff jitter`
  - `redis user pod mapping pattern`
  - `missed events fetch realtime`
  - `stomp session disconnect handling`
  - `idempotent event dispatch frontend`

## 10. Sözlük

- **User-Pod Mapping** — `user:pod:{userId}` Hash'i; user'ın bağlı olduğu pod adı.
- **Heartbeat refresh** — Pod'un mapping TTL'ini periyodik yenilemesi.
- **Stale mapping** — Pod öldü ama TTL dolmadan mevcut kayıt.
- **Reconnect backoff** — Bağlantı koparsa giderek artan bekleme süresi.
- **Jitter** — Backoff süresine eklenen rastgele varyans; thundering herd engeli.
- **Missed events** — Bağlantı kopukken üretilen ve client'a ulaşmayan event'ler.
- **Idempotent dispatch** — Aynı event_id'nin iki kez işlenmemesi.
- **Sticky session** — LB'nin client'ı sabit bir pod'a kilitlemesi (Lumix kullanmaz).
- **Pod restart** — Kubernetes'in pod'u sonlandırıp yenisini ayağa kaldırması.
- **Graceful disconnect** — Sunucu close frame yollar, client temiz kapatır.

