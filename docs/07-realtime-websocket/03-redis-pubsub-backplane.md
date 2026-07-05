---
title: Redis Pub/Sub Backplane (KRİTİK Karar)
description: Lumix'in WebSocket çoklu pod fan-out kararı — RabbitMQ yerine Redis Pub/Sub. Kafka neden STOMP relay desteklemiyor, custom MessageBrokerAdapter, diyagram + Spring config + pseudo-code.
sidebar_position: 3
---

## Bu sayfa ne anlatıyor?

Lumix'in en kritik real-time mimari kararı **buradadır**. Spring STOMP normalde external broker (RabbitMQ, ActiveMQ) ile çoklu pod arası fan-out yapar. Lumix tek async broker olarak **Kafka**'yı seçti ama **Kafka STOMP relay'i desteklemez**. RabbitMQ eklemek ekstra operasyonel yük. Çözüm: **Redis Pub/Sub backplane** — Spring'in `SimpleBroker`'ı pod-içi kullan, pod-lar arası mesaj fan-out'u Redis Pub/Sub ile yap. Bu sayfa bu kararı, custom `MessageBrokerAdapter` tasarımını, akışı ve config'i anlatır.

## 1. Bu nedir? (Sıfırdan)

**Backplane** = aynı uygulamanın farklı instance'ları arasında **mesaj dağıtım altyapısı**. WebSocket persistent bağlantı olduğu için "bağlantı tek bir pod'a sabittir". Pod-1'deki kullanıcıya gönderilecek mesaj Pod-2'de doğsa, **Pod-1'e ulaştırılması gerek**. Bu ulaştırma katmanına "backplane" denir.

```
                     Backend domain event (örn. AttendanceMarked)
                              │
                              ▼
                     ┌──────────────────┐
                     │     POD-1        │
                     │  (event burada   │
                     │   doğdu)         │
                     └────────┬─────────┘
                              │
                              ▼
        ┌─────────────── BACKPLANE ───────────────┐
        │     (cross-pod mesaj dağıtıcısı)        │
        └─────────────────┬────────┬──────────────┘
                          │        │
                  ┌───────┘        └────────┐
                  ▼                          ▼
          ┌──────────────┐           ┌──────────────┐
          │   POD-2      │           │   POD-3      │
          │ Bağlı user'ı │           │ Bağlı user'ı │
          │ var → fan-out│           │ var → fan-out│
          └──────────────┘           └──────────────┘
```

Backplane teknolojileri: Redis Pub/Sub, RabbitMQ, Kafka, NATS, dedicated services (AWS API Gateway WebSocket vs.).

### Günlük analoji

Bir mahalle "Apartman yöneticisi" duyuru yapıyor. Her apartmanda farklı insanlar oturuyor.
- **Backplane yok:** Yönetici sadece 1 apartmana duyuru veriyor; diğer apartman sakinleri haberdar olmuyor.
- **Backplane var (Redis Pub/Sub):** Yönetici merkezi telsizden duyuruyor; her apartmanın kapıcısı dinliyor, kendi sakinlerine iletiyor.

## 2. Hangi problemi çözüyor?

### 2.1. `convertAndSendToUser` cross-pod problemi
Spring `convertAndSendToUser(userId, ...)` çağırınca **lokal pod**'daki user session'larını bulur. Eğer kullanıcı Pod-2'de oturuyorsa ve sen Pod-1'den çağırıyorsan **mesaj kayıp**.

### 2.2. `convertAndSend("/topic/...")` cross-pod problemi
Topic'e abone olan kullanıcılar farklı pod'larda olabilir. Pod-1 bunu sadece kendi pod'undaki subscriber'lara iletir.

### 2.3. Horizontal scaling kabul etmek
WebSocket pod'unu ölçekleyebilmek için backplane şart. Yoksa sticky session + tek pod = ölçeklenmez.

### 2.4. Pod restart sırasında diğer pod'ların etkilenmemesi
Bir pod restart oluyorsa, diğer pod'lardaki kullanıcılar etkilenmemeli; backplane var olduğu için diğer pod'lar tüm mesajı görüyor.

## 3. Nasıl çözüyor? (Mekanizma + diyagram)

### 3.1. Tek pod mimarisi (basit hal)

```
Backend Service → simpMessagingTemplate.convertAndSend("/topic/x", payload)
                              │
                              ▼
                    Spring SimpleBroker (pod-içi)
                              │
                              ▼
                    WebSocket session iterator → frame gönder
```

Bu **tek pod**'da harika çalışır. Lumix neredeyse her servis WebSocket endpoint açıyor; ama her servis birden çok replica'da. Bu yüzden cross-pod fan-out şart.

### 3.2. Lumix'in çözümü: Redis Pub/Sub Bridge

```
                   ┌─────────────────────────────────────────────────┐
                   │           BACKEND SERVICE — POD-1               │
                   │                                                 │
                   │  service code:                                  │
                   │    realtimePublisher.publish(                   │
                   │       "/topic/attendance.class.11A", payload);  │
                   │                                                 │
                   │  RealtimePublisherImpl:                         │
                   │    1. SimpleBroker.convertAndSend (LOKAL fan)   │
                   │    2. Redis PUBLISH "ws:bcast"                  │
                   │       payload: { dest, body, src_pod }          │
                   │                                                 │
                   └────────┬───────────────────────────┬────────────┘
                            │                           │
                            │ (lokal)                   │ (Redis)
                            ▼                           ▼
                  ┌─────────────────┐         ┌──────────────────┐
                  │ Lokal STOMP     │         │   Redis Pub/Sub  │
                  │ session'lar     │         │   Channel:       │
                  │ (Pod-1 user'lar)│         │   "ws:bcast"     │
                  └─────────────────┘         └──────────┬───────┘
                                                         │
                                          ┌──────────────┼──────────────┐
                                          │              │              │
                                          ▼              ▼              ▼
                              ┌─────────────────┐ ┌─────────────┐ ┌─────────────┐
                              │   POD-2         │ │   POD-3     │ │   POD-N     │
                              │                 │ │             │ │             │
                              │ subscriber dinler│ │  ...        │ │  ...        │
                              │ src_pod != self?│ │             │ │             │
                              │ → SimpleBroker  │ │             │ │             │
                              │   ile lokal fan │ │             │ │             │
                              │ (kendi user'lar)│ │             │ │             │
                              └─────────────────┘ └─────────────┘ └─────────────┘
```

**Anahtar prensip:** Mesaj iki tarafa da gider:
1. **Lokal SimpleBroker** ile pod-içindeki user'lara.
2. **Redis PUBLISH** ile diğer pod'lara; onlar da kendi pod'larında lokal fan-out yapar.

`src_pod` field'ı ile **echo loop** önlenir (Pod-1 yayınladı, Pod-1 kendi event'ini ikinci kez işlemez).

### 3.3. User-specific routing (cross-pod)

`/user/queue/notifications` için ek bir mekanizma var:

```
Servis: realtimePublisher.publishToUser(userId, "/queue/notifications", payload)
              │
              ▼
1. Redis lookup: user:pod:{userId} → pod adı
2a. Eğer self-pod → SimpleBroker.convertAndSendToUser (lokal)
2b. Eğer başka pod → Redis PUBLISH "ws:user:{podName}"
                     payload: { userId, dest, body }
                       │
                       ▼
3. O pod'un subscriber'ı dinler, SimpleBroker.convertAndSendToUser local
```

`user:pod:{userId}` Hash'i nasıl kuruluyor: kullanıcı bir pod'a WebSocket connect olunca o pod kendini bu Hash'e yazar (SubProtocol connect handler). Detay: [User-Pod Mapping](./user-pod-mapping-and-reconnect).

### 3.4. Redis kanal yapısı

| Channel | İçerik | Listener |
|---|---|---|
| `ws:bcast` | Topic/queue mesajları (tüm pod'lar dinler) | Tüm pod'lar |
| `ws:user:{podName}` | User-specific mesajlar | Sadece o pod |

Alternatif: tek channel + payload'da `pod_filter`. Lumix iki channel ayırarak gereksiz pod'ların CPU'sunu yormuyor.

### 3.5. Custom `RealtimePublisher` API

Servisler doğrudan `simpMessagingTemplate` kullanmaz; Lumix'in `RealtimePublisher` interface'i:

```java
public interface RealtimePublisher {
    void publish(String destination, Object payload);
    void publishToUser(UUID userId, String destination, Object payload);
}
```

Bu interface implementasyon detayını gizler: dev/local'da SimpleBroker only, prod'da Redis bridge'li.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Hangi Redis cluster?

**cache-redis** cluster'ı kullanılır (pub/sub için persistence gereksiz, ephemeral). Auth-redis cluster'ı **kullanılmaz** (tokens için ayrıdır). Detay: [Redis Sentinel Topology](../08-caching-redis/redis-sentinel-topology).

### 4.2. Connection durumu

- Spring'in `SimpleBroker`'ı her pod'da aktif (default Spring WebSocket konfigü).
- `RedisMessageListenerContainer` ile `ws:bcast` ve `ws:user:{podName}` dinlenir.
- Pod adı: `HOSTNAME` env (K8s pod name'i).

### 4.3. Mesaj envelope

```json
{
  "src_pod": "academic-service-7d4-abcde",
  "destination": "/topic/attendance.class.11A",
  "payload": { ... },
  "timestamp": "..."
}
```

### 4.4. Dev/Test mode

Dev (tek pod) için Redis bridge **disable** edilebilir:
```yaml
lumix:
  ws:
    backplane:
      enabled: false   # local-dev tek pod'da yeterli
```

### 4.5. Tilt + lokal cluster

Lokal geliştirme (Tilt + k3d) Redis ile çalışır; iki pod replica ayağa kalkar, gerçek backplane akışı test edilir.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

| Alternatif | Tartışma |
|---|---|
| **RabbitMQ broker relay** | Spring native; çok güzel STOMP relay desteği var. Ama **Lumix'te RabbitMQ yok** — sırf real-time için yeni broker eklemek operasyonel yük. **Elendi.** |
| **Kafka broker relay** | Spring STOMP doğal Kafka relay'i **desteklemiyor**. Kafka topic semantiği STOMP destination'a 1:1 map etmiyor. **Mümkün değil.** |
| **Sticky session + tek pod-per-user** | Single point of failure; restart = tüm bağlantılar kopar. **Elendi.** |
| **External commercial backplane (Ably, Pusher, AWS API Gateway WS)** | Self-host şartı + müşteri verisi 3.party'de istenmez. **Elendi.** |
| **NATS** | Hafif, hızlı; ama ekstra bileşen. Lumix zaten Redis kullanıyor; ek operasyonel cost'a değmez. **Elendi.** |
| **Redis Pub/Sub backplane** | ✓ **Lumix seçimi.** Mevcut Redis'i kullan, custom adapter yaz. |

### Trade-off'lar

- **Redis Pub/Sub at-most-once delivery.** Pod abone değilse mesaj kayıp. Lumix kabul: real-time event'ler kaybolabilir; missed events fetch endpoint client'a kayıpları kapatma imkanı verir.
- **Pub/Sub persistence yok.** Subscriber down ise mesajı görmez. Lumix bunun farkında; "guaranteed delivery" için Kafka audit topic ayrı yol.
- **Memory bandwidth:** Cross-pod fan-out büyük topic'lerde bandwidth yer. Pratikte küçük JSON payload'lar; sorun değil.
- **Custom adapter bakım yükü:** Lumix'in yazdığı `RedisBackplaneAdapter` ~200 satır kod. Bakım maliyeti düşük.
- **Echo loop kontrolü:** `src_pod` filter zorunlu, unutulursa sonsuz döngü.

### Tekrar değerlendirme şartı

- Mesaj kaybı tolere edilemez bir use-case eklendiğinde (örn. finansal real-time stream): Kafka + custom WS consumer pattern eklenebilir.
- Redis Sentinel failover sırasında 1-2sn pub/sub kesintisi yaşanır; "5 dokuz uptime" gereksinimi gelirse Redis Cluster + RedisStream'e dönülebilir.

## 6. Pratik örnek

### 6.1. RealtimePublisher interface

```java
public interface RealtimePublisher {
    void publish(String destination, Object payload);
    void publishToUser(UUID userId, String destination, Object payload);
}
```

### 6.2. Redis bridge implementation

```java
@Component
@Profile("!single-pod")
@RequiredArgsConstructor
public class RedisBackplaneRealtimePublisher implements RealtimePublisher {

    private final SimpMessagingTemplate localBroker;
    private final StringRedisTemplate redis;
    private final UserPodMappingService podMap;
    private final ObjectMapper json;

    @Value("${HOSTNAME:unknown-pod}")
    private String selfPod;

    private static final String BROADCAST_CHANNEL = "ws:bcast";

    @Override
    public void publish(String destination, Object payload) {
        // 1) Lokal SimpleBroker fan-out
        localBroker.convertAndSend(destination, payload);

        // 2) Diğer pod'lara yayın
        String envelope = json.writeValueAsString(Map.of(
            "src_pod", selfPod,
            "destination", destination,
            "payload", payload,
            "ts", Instant.now().toString()
        ));
        redis.convertAndSend(BROADCAST_CHANNEL, envelope);
    }

    @Override
    public void publishToUser(UUID userId, String destination, Object payload) {
        String pod = podMap.findPod(userId);
        if (pod == null) {
            // User offline; sessizce skip
            return;
        }
        if (selfPod.equals(pod)) {
            // Lokal
            localBroker.convertAndSendToUser(userId.toString(), destination, payload);
            return;
        }
        // Hedef pod'a yönlendir
        String userChannel = "ws:user:" + pod;
        String envelope = json.writeValueAsString(Map.of(
            "user_id", userId,
            "destination", destination,
            "payload", payload,
            "src_pod", selfPod
        ));
        redis.convertAndSend(userChannel, envelope);
    }
}
```

### 6.3. Backplane listener (her pod'da)

```java
@Configuration
public class RedisBackplaneListenerConfig {

    @Value("${HOSTNAME:unknown-pod}")
    private String selfPod;

    @Bean
    public RedisMessageListenerContainer container(
            RedisConnectionFactory cf,
            BroadcastMessageHandler broadcastHandler,
            UserMessageHandler userHandler) {

        RedisMessageListenerContainer c = new RedisMessageListenerContainer();
        c.setConnectionFactory(cf);

        c.addMessageListener(broadcastHandler,
            new ChannelTopic("ws:bcast"));
        c.addMessageListener(userHandler,
            new ChannelTopic("ws:user:" + selfPod));

        return c;
    }
}
```

### 6.4. Broadcast handler

```java
@Component
@RequiredArgsConstructor
public class BroadcastMessageHandler implements MessageListener {

    private final SimpMessagingTemplate localBroker;
    private final ObjectMapper json;

    @Value("${HOSTNAME:unknown-pod}")
    private String selfPod;

    @Override
    public void onMessage(Message msg, byte[] pattern) {
        try {
            BroadcastEnvelope env = json.readValue(msg.getBody(), BroadcastEnvelope.class);
            // Echo guard
            if (selfPod.equals(env.srcPod())) return;

            // Lokal fan-out
            localBroker.convertAndSend(env.destination(), env.payload());
        } catch (Exception e) {
            log.warn("Backplane broadcast decode failed", e);
        }
    }

    public record BroadcastEnvelope(
        @JsonProperty("src_pod") String srcPod,
        String destination,
        Object payload,
        String ts) {}
}
```

### 6.5. User-specific handler

```java
@Component
@RequiredArgsConstructor
public class UserMessageHandler implements MessageListener {

    private final SimpMessagingTemplate localBroker;
    private final ObjectMapper json;

    @Override
    public void onMessage(Message msg, byte[] pattern) {
        UserEnvelope env = json.readValue(msg.getBody(), UserEnvelope.class);
        localBroker.convertAndSendToUser(env.userId().toString(),
                                           env.destination(),
                                           env.payload());
    }

    public record UserEnvelope(
        @JsonProperty("user_id") UUID userId,
        String destination,
        Object payload,
        @JsonProperty("src_pod") String srcPod) {}
}
```

### 6.6. application.yml

```yaml
spring:
  data:
    redis:
      sentinel:
        master: cache-master
        nodes:
          - cache-sentinel-0:26379
          - cache-sentinel-1:26379
          - cache-sentinel-2:26379

lumix:
  ws:
    backplane:
      enabled: true
      broadcast-channel: ws:bcast
      user-channel-prefix: ws:user:
```

### 6.7. Servis kullanımı (uygulama tarafı)

```java
@Service
@RequiredArgsConstructor
public class AttendanceRealtimeNotifier {

    private final RealtimePublisher realtime;

    public void notifyClass(UUID classId, AttendanceMarkedEvent event) {
        realtime.publish("/topic/attendance.class." + classId, event);
    }

    public void notifyParent(UUID parentUserId, AttendanceMarkedEvent event) {
        realtime.publishToUser(parentUserId, "/queue/notifications", event);
    }
}
```

## 7. Dikkat edilecek tuzaklar

- **Echo loop.** `src_pod` filter unutulursa Pod-1 kendi yayınladığı mesajı tekrar lokal'e atıyor → sonsuz döngü. **Kural:** her bridge mesajında `src_pod` zorunlu, listener filter zorunlu.
- **Lokal fan-out + Redis yayını sıralaması.** Önce lokal SimpleBroker'a gönder, sonra Redis publish — sıra kritik (önce localde gözüksün diye).
- **`convertAndSend` tüm pod'a yayınlanır sanmak.** Spring docs net değil; **Lumix kuralı:** her zaman `RealtimePublisher` arayüzünden geç, doğrudan `simpMessagingTemplate` çağrısı yasak.
- **Redis Pub/Sub at-most-once.** Subscriber bağlı değilse mesaj kayıp. **Çözüm:** kritik event'ler için missed events fetch endpoint + idempotent client.
- **Auth-redis cluster'ında Pub/Sub.** Token cluster persistence-bound; pub/sub yükü oraya konmamalı. **Kural:** cache-redis cluster (no-persist).
- **`HOSTNAME` env yoksa `unknown-pod` çakışması.** Tüm pod'lar `unknown-pod` olursa user routing bozulur. **Kural:** K8s manifest'inde `HOSTNAME` set zorunlu.
- **Çok büyük payload Redis'e yazmak.** Redis frame 512MB max ama pratikte 64KB üstü kötü. **Kural:** WS message size limit 64KB ile uyumlu.
- **Listener exception'ı sessizce yutmak.** Decode hatası tüm pub/sub flow'unu kilitler. **Kural:** try/catch + log + metric.
- **Tek node Redis için backplane.** Sentinel olmadan failover yok, gerçek prod'da uygunsuz.
- **Test'te Redis mock + integration testte real Redis kullanmamak.** Bridge davranışı sadece real Redis ile doğrulanabilir; Testcontainers ile gerçek Redis testi şart.

## 8. Diğer konularla ilişkisi

- [WebSocket Temelleri](./websocket-fundamentals) — alt katman
- [STOMP Protokolü](./stomp-protocol) — bridge'in temsil ettiği üst katman
- [User-Pod Mapping & Reconnect](./user-pod-mapping-and-reconnect) — `user:pod:{userId}` Hash'inin nasıl kurulduğu
- [Redis Sentinel Topology](../08-caching-redis/redis-sentinel-topology) — backplane'in altyapısı
- [Redis Fundamentals](../08-caching-redis/redis-fundamentals) — Pub/Sub data structure

## 9. Daha derine inmek için

- Redis: [Pub/Sub Documentation](https://redis.io/docs/interact/pubsub/)
- Spring: [Redis Messaging — Pub/Sub](https://docs.spring.io/spring-data/redis/reference/redis/pubsub.html)
- Spring: [WebSocket — Full Featured Broker](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/enable.html#websocket-stomp-handle-broker-relay)
- SignalR (concept analogy): "Backplane for ASP.NET SignalR"
- Search keywords:
  - `websocket scale out backplane`
  - `redis pubsub vs stream`
  - `spring stomp custom broker`
  - `signalr backplane redis`
  - `convertAndSendToUser cross-pod`

## 10. Sözlük

- **Backplane** — Cross-pod mesaj dağıtım altyapısı.
- **Redis Pub/Sub** — Redis'in publish/subscribe data structure'ı; at-most-once delivery.
- **SimpleBroker** — Spring'in in-memory STOMP broker'ı (pod-lokal).
- **MessageBrokerAdapter** — Spring'in external broker'ı saran adapter; Lumix'te custom Redis bridge.
- **`RealtimePublisher`** — Lumix'in implementation-agnostic publish API'si (interface).
- **Echo loop** — Pod kendi yayınladığı mesajı geri tüketmesi; `src_pod` filter ile engellenir.
- **At-most-once** — Mesajın 0 veya 1 kez teslim edilebileceği delivery garantisi (kayıp olabilir).
- **Channel topic** — Redis Pub/Sub kanal adı (`ws:bcast`, `ws:user:{pod}`).
- **Echo guard** — `src_pod == self` kontrolü ile kendi mesajını filtreleme.

