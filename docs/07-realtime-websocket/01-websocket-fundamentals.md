---
title: WebSocket Temelleri
description: WebSocket nedir, HTTP request-response ile farkı, full-duplex, connection ownership, browser API, Spring WebSocket setup.
sidebar_position: 1
---

## Bu sayfa ne anlatıyor?

Real-time iletişimin temel taşı **WebSocket** protokolünü sıfırdan açıklar. HTTP request-response ile farkı, full-duplex'in ne demek olduğu, browser tarafında `WebSocket` API'sinin nasıl kullanıldığı, Spring Boot'ta WebSocket endpoint'in nasıl tanımlandığı ve bağlantı sahipliği (connection ownership) kavramı. STOMP ve Redis Pub/Sub backplane (Lumix kararları) sonraki sayfalarda.

## 1. Bu nedir? (Sıfırdan)

**HTTP** = istek/yanıt protokolüdür. Client soru sorar, sunucu cevap verir, **bağlantı kapanır** (HTTP/1.1 keep-alive ile bağlantı bir süre açık kalır ama iletişim hâlâ "sen sor, ben cevaplayayım").

**WebSocket** = **iki yönlü** (full-duplex), **uzun ömürlü** TCP-tabanlı bir protokoldür. Bir kez handshake yapılır, sonra ne client ne sunucu birbirine kimseden izin almadan istediği an mesaj gönderebilir.

### Günlük analoji

- **HTTP** = telefonla bilgi almak: arar, sorarsın, cevap alır, kapatırsın. Sunucu sana habersiz telefon edemez.
- **WebSocket** = walkie-talkie kanalı: bir kez bağlandın mı, iki taraf da istediği zaman konuşabilir. Sunucu "yeni mesaj geldi" diye seni proaktif çağırabilir.

### Protokol işleyişi

```
1. HTTP Upgrade handshake:
   Client → GET /ws HTTP/1.1
            Upgrade: websocket
            Connection: Upgrade
            Sec-WebSocket-Key: ...
            Sec-WebSocket-Version: 13

   Server → HTTP/1.1 101 Switching Protocols
            Upgrade: websocket
            Connection: Upgrade
            Sec-WebSocket-Accept: ...

2. Bu aşamadan sonra TCP soketi WebSocket protokolüne döner:
   - Mesaj frame'leri (binary veya text)
   - Ping/Pong (heartbeat)
   - Close frame (graceful kapanış)

3. Client → "Merhaba" → Server
   Server → "Selam" → Client
   (sırasız, full-duplex)
```

### URL şeması

```
ws://example.com/ws          (cleartext, geliştirme)
wss://example.com/ws         (TLS üzeri, production)
```

`wss://` = HTTPS'in WebSocket karşılığı. Lumix production'da **sadece wss://**.

## 2. Hangi problemi çözüyor?

### 2.1. Sunucudan client'a "push"
HTTP polling = "her 5 saniyede `/messages/new` çağır". 100 kullanıcı = saniyede 20 istek. Sunucu yüklenir, gerçek-zamanlı değil.

### 2.2. Düşük gecikme
Yeni mesaj geldi, kullanıcı 5sn'den daha az sürede görmeli. Polling ortalama 2.5sn gecikme demek.

### 2.3. Bidirectional komut/state akışı
Chat, dashboard live update, multiplayer durumlar — iki tarafın da spontan mesaj atabildiği senaryolar.

### 2.4. Connection-overhead azaltma
Her HTTP isteğinin TCP/TLS handshake + HTTP header overhead'i var. WebSocket bir kez kurulur, sonra her mesaj sadece kendi frame overhead'i (2-14 byte).

### 2.5. Server-Sent Events (SSE) yeterli olmayan senaryolar
SSE tek yönlü (sunucu → client). Bidirectional gereken yerlerde WebSocket lazım.

## 3. Nasıl çözüyor? (Mekanizma + diyagram)

### 3.1. Lifecycle

```
   [CLIENT]                                  [SERVER]
       │                                          │
       │ ─── HTTP GET /ws (Upgrade) ──────────►   │
       │                                          │
       │ ◄── HTTP 101 Switching Protocols ─────   │
       │                                          │
       │ ═══ WebSocket TCP soketi ═══════════════ │
       │                                          │
       │ ─── send("hi") ──────────────────────►   │
       │                                          │
       │ ◄── send("hello!") ──────────────────    │
       │                                          │
       │ ◄── send("event: new_message") ──────    │ (server initiated)
       │                                          │
       │ ─── send("ack") ─────────────────────►   │
       │                                          │
       │ ─── ping ────────────────────────────►   │
       │ ◄── pong ────────────────────────────    │ (heartbeat)
       │                                          │
       │ ─── close frame ─────────────────────►   │
       │ ◄── close frame ─────────────────────    │
       │                                          │
       └─ socket closed                           └─ socket closed
```

### 3.2. Connection ownership

Bu **kritik** bir kavram. WebSocket bağlantısı **TCP'dir**, TCP iki ucu arasındadır.

```
   [Browser]                  [Load Balancer]                [Pod-1]
       ╔════════════════════════════════════════════════════════════╗
       ║          Persistent TCP — Pod-1'e bağlı                   ║
       ╚════════════════════════════════════════════════════════════╝

   [Browser]                  [Load Balancer]                [Pod-2]
                                                              (boşta)

   [Browser]                  [Load Balancer]                [Pod-3]
                                                              (boşta)
```

Sonuç: **A pod'una bağlı olan client'a Pod-2 doğrudan mesaj gönderemez**. Bu Lumix'in en büyük design kararını doğuruyor: **Redis Pub/Sub backplane**. Detay: [Redis Pub/Sub Backplane](./03-redis-pubsub-backplane.md) ve [User-Pod Mapping](./04-user-pod-mapping-and-reconnect.md).

### 3.3. Ping/Pong (heartbeat)

WebSocket protokolünde ping/pong frame'leri var. Sebebi:
- **Dead connection detection:** TCP "yarı-kapalı" durumda kalabilir (NAT timeout, ağ kesintisi). Heartbeat olmadan sunucu bunu fark etmez.
- **Idle keep-alive:** Load balancer/proxy idle timeout'unu sıfırlamak.

Lumix: client her 25sn ping, sunucu pong. 60sn cevap yoksa bağlantı kapanmış sayılır.

### 3.4. Mesaj formatı

WebSocket payload **text** veya **binary**. İçeriği protokole bağlı:
- Raw text mesaj → uygulama kendi format'ını seçer (JSON yaygın)
- **STOMP** → metin-tabanlı, frame'li yapı (sonraki sayfa)

Lumix STOMP üstüne JSON payload kullanır.

### 3.5. Authentication

WebSocket handshake **HTTP üstündedir**. Yani kimlik doğrulama HTTP başlığı (cookie + Authorization) ile yapılır. Handshake sırasında Spring Security filter çalışır, geçemezse 401.

Connect sonrası kimlik bilgisi `WebSocketSession.principal` olarak tutulur, mesaj akışında kullanılır.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Tek WebSocket endpoint

Lumix tüm real-time trafiği tek WebSocket endpoint üzerinden götürür:

```
wss://api.lumix.example.com/ws
```

Bu endpoint **STOMP** sub-protokolünü konuşur (`Sec-WebSocket-Protocol: v12.stomp`). Destination'lara abone olmak/mesaj göndermek için ek endpoint açılmaz.

### 4.2. Hangi servis?

Lumix mimari kararı: **realtime adapter** her servise gömülü olabilir ya da `notification-service` orta merkez olabilir. **Karar:**

- WebSocket endpoint **her servis** kendisi açar (Spring WebSocket native).
- Ortak realtime backplane (Redis Pub/Sub) tüm pod'lar arası fan-out yapar.

Bu sayede `academic-service` yoklama event'lerini doğrudan kendi pod'undaki WebSocket session'larına gönderebilir; Redis backplane diğer pod'lara dağıtır.

### 4.3. Ingress + Kong + WebSocket

WebSocket trafiği Kong Gateway'den geçer:
- Kong WebSocket protokolünü transparent şekilde proxy eder.
- JWT auth plugin handshake sırasında çalışır (Cookie + Authorization).
- Idle timeout: Kong default 60sn ping/pong ile yenilenir.
- TLS terminasyon Traefik'te.

### 4.4. Versiyon

- **Spring 7.x WebSocket** (Spring Boot 4.x ile gelir)
- **STOMP v1.2** (`Sec-WebSocket-Protocol: v12.stomp`)
- **SockJS fallback:** Lumix kullanmaz (modern browser'lar native WS destekli, fallback gerekirse polling overhead getirir)

### 4.5. Hangi senaryolarda kullanılır?

| Senaryo | Destination | Açıklama |
|---|---|---|
| Notification push | `/user/queue/notifications` | Per-user bildirim |
| Force logout / auth revoked | `/user/queue/auth.revoked` | Permission change, suspend |
| Permission update | `/user/queue/auth.permission.updated` | Soft invalidation hint |
| Yoklama topic | `/topic/attendance.class.{id}` | Sınıf-bazlı broadcast |
| Chat mesaj | `/topic/chat.class.{id}` ve `/user/queue/chat.dm` | Class + direct message |
| Dashboard live update | `/topic/dashboard.tenant.{id}` | Tenant-scoped metric |
| Send komutları | `/app/...` | Client→server (chat send, ack) |

### 4.6. Kullanılmayan WebSocket senaryoları

| Senaryo | Niye değil |
|---|---|
| File upload progress | Pre-signed URL ile direkt S3; client-side progress yeterli |
| Long-running batch job status | Temporal workflow + polling endpoint daha uygun |
| Mobile background notification | Push notification (FCM/APNs); WS sadece foreground |

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

| Alternatif | Tartışma |
|---|---|
| **HTTP long-polling** | Basit ama gecikmeli + yüksek bağlantı maliyeti. **Elendi.** |
| **Server-Sent Events (SSE)** | Tek yönlü; Lumix chat, ack, ack-of-read için bidirectional gerek. **Elendi.** |
| **WebTransport (QUIC üstü)** | Geleceğin protokolü ama browser/library olgunluk eksik. **Şimdilik elendi.** |
| **gRPC streaming** | Browser native değil (grpc-web ile yarı), karmaşık. Server-to-server'da kullanılır ama frontend için **WebSocket** seçildi. |
| **WebSocket native** | ✓ Browser+server geniş destek, Spring native. |
| **WebSocket + raw JSON (no STOMP)** | Subscription/topic semantiği elle yazmak gerek. Spring `simpMessagingTemplate` ile büyük kazanç. **Elendi.** |
| **WebSocket + STOMP (Lumix)** | ✓ Subscription, destination, user mapping out-of-box. |

### Trade-off'lar

- **Persistent connections = pod sahipliği.** Bu zorluğu Redis Pub/Sub backplane ile çözüyoruz. Detay [Redis Pub/Sub Backplane](./03-redis-pubsub-backplane.md).
- **TCP idle timeout:** NAT/proxy timeout ile sessiz kopma. Ping/pong + reconnect mantığı şart.
- **Sticky sessions opsiyonel.** Lumix sticky session **kullanmıyor**, çünkü Redis backplane ile her pod aynı view'ı görür.

## 6. Pratik örnek

### 6.1. Spring WebSocket configuration

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns(
                "https://app.lumix.example.com",
                "https://admin.lumix.example.com");
        // SockJS fallback YOK
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{25_000, 25_000})
                .setTaskScheduler(heartbeatScheduler());
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Bean
    public TaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(2);
        s.setThreadNamePrefix("ws-heartbeat-");
        s.initialize();
        return s;
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration r) {
        r.setMessageSizeLimit(64 * 1024);             // 64KB max
        r.setSendBufferSizeLimit(512 * 1024);
        r.setSendTimeLimit(10_000);                   // 10sn slow client kick
    }
}
```

### 6.2. WebSocket authentication (handshake)

```java
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    private final JwtDecoder jwtDecoder;
    private final TokenStore tokenStore;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                     ServerHttpResponse response,
                                     WebSocketHandler wsHandler,
                                     Map<String, Object> attributes) throws Exception {
        String authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            Jwt jwt = jwtDecoder.decode(authHeader.substring(7));
            String jti = jwt.getId();
            if (!tokenStore.isActive(jti)) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            attributes.put("userId", jwt.getSubject());
            attributes.put("tenantId", jwt.getClaimAsString("tenant_id"));
            attributes.put("jti", jti);
            return true;
        } catch (JwtException e) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override public void afterHandshake(ServerHttpRequest req, ServerHttpResponse res,
                                          WebSocketHandler h, Exception ex) {}
}
```

### 6.3. Endpoint registry'ye interceptor ekle

```java
@Override
public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.addEndpoint("/ws")
        .setAllowedOriginPatterns("https://app.lumix.example.com")
        .addInterceptors(webSocketAuthInterceptor);
}
```

### 6.4. Frontend WebSocket bağlantısı (raw)

```ts
const ws = new WebSocket('wss://api.lumix.example.com/ws',
                         'v12.stomp');

ws.onopen = () => {
  console.log('WS open');
  // STOMP CONNECT frame gönderilecek
};

ws.onmessage = (event) => {
  console.log('frame:', event.data);
};

ws.onclose = (event) => {
  console.log('WS closed', event.code, event.reason);
  // Reconnect mantığı (sonraki sayfa)
};

ws.onerror = (event) => {
  console.error('WS error', event);
};
```

Pratikte raw WebSocket yerine STOMP client kullanılır (sonraki sayfa). Bu sadece protokolün altını göstermek için.

### 6.5. application.yml

```yaml
lumix:
  ws:
    endpoint: /ws
    allowed-origins:
      - https://app.lumix.example.com
      - https://admin.lumix.example.com
    heartbeat-ms: 25000
    message-size-limit-bytes: 65536
    slow-client-kick-ms: 10000
```

## 7. Dikkat edilecek tuzaklar

- **CORS / origin allowlist'i wildcard yapmak.** `*` ile WebSocket origin doğrulamasını bypass etmek = browser CORS bypass çünkü WebSocket aynı SOP'a tabi değil ama Lumix `setAllowedOriginPatterns` ile manuel zorlar. **Kural:** explicit allowlist.
- **JWT'yi query string'de göndermek (`?token=...`).** Log'lara yazılır. **Çözüm:** `Authorization` header (browser native WS API header set etmez ama STOMP CONNECT frame içinde gönderilebilir — sonraki sayfa).
- **Heartbeat yok.** Kong/Traefik 60sn idle'da bağlantıyı kapatır. **Çözüm:** STOMP heartbeat 25/25sn.
- **Sticky session zorunlu sanmak.** Lumix Redis backplane ile sticky'ye gerek bırakmaz. Sticky kullanırsan pod restart'ta tüm bağlı kullanıcılar etkilenir.
- **WebSocket'i HTTP cache header'ı ile kontrol etmeye çalışmak.** WS HTTP değil; cache header'ları geçersiz.
- **Mesaj boyutu sınırı yok.** Bir kötü client 100MB frame gönderir, OOM. **Kural:** `setMessageSizeLimit`.
- **Slow client'lar.** Yavaş okuyan client'lar send buffer'ı şişirir. **Çözüm:** `setSendTimeLimit` + buffer limit + kick.
- **`ws://` production'da.** TLS olmadan handshake sniff'lenebilir. **Kural:** `wss://` zorunlu.
- **Connection sayısı limitsiz.** Bir kullanıcı 10000 WebSocket bağlantısı açar → DoS. **Kural:** user başına max-N (Lumix: 5 cihaz × 1 socket ≈ 5).
- **Sayfa kapanışında graceful close yok.** Browser çoğu zaman gönderir ama kesik network/forced close olabilir. Server-side heartbeat timeout ile temizlenir.

## 8. Diğer konularla ilişkisi

- [STOMP Protokolü](./02-stomp-protocol.md) — bu WebSocket üzerine binen abonelik katmanı
- [Redis Pub/Sub Backplane](./03-redis-pubsub-backplane.md) — cross-pod mesaj fan-out
- [User-Pod Mapping & Reconnect](./04-user-pod-mapping-and-reconnect.md) — bağlantı kopması ve missed events
- [Fully Stateful Token Modeli](../04-authentication-authorization/01-stateful-token-model.md) — WebSocket handshake auth

## 9. Daha derine inmek için

- IETF: [RFC 6455 — The WebSocket Protocol](https://datatracker.ietf.org/doc/html/rfc6455)
- Spring: [WebSocket Reference](https://docs.spring.io/spring-framework/reference/web/websocket.html)
- MDN: [WebSocket API](https://developer.mozilla.org/en-US/docs/Web/API/WebSockets_API)
- HTML Standard: [Sec-WebSocket-Protocol](https://html.spec.whatwg.org/multipage/web-sockets.html)
- Search keywords:
  - `websocket vs http long polling`
  - `websocket heartbeat ping pong`
  - `spring boot websocket configuration`
  - `websocket authentication patterns`
  - `wss tls handshake`

## 10. Sözlük

- **WebSocket** — Full-duplex, persistent TCP üstü protokol. RFC 6455.
- **Full-duplex** — Aynı anda iki yönlü iletişim.
- **Handshake** — HTTP Upgrade ile başlayan ilk anlaşma adımı.
- **Frame** — WebSocket mesaj birimi (text/binary/close/ping/pong).
- **Heartbeat (Ping/Pong)** — Bağlantının canlı olduğunu doğrulayan periyodik frame'ler.
- **Connection ownership** — Persistent bir bağlantının sadece tek bir sunucu instance'ında var olabilmesi.
- **Backplane** — Aynı uygulamanın farklı instance'ları arasında mesajı dağıtan altyapı (Lumix'te Redis Pub/Sub).
- **Sticky session** — Load balancer'ın bir client'ı hep aynı backend instance'ına yönlendirmesi.
- **SockJS** — WebSocket desteklemeyen tarayıcılar için fallback (Lumix kullanmaz).
- **STOMP** — WebSocket üstüne abonelik/destination semantiği bindiren mesajlaşma protokolü (sonraki sayfa).

