---
title: Redis Sentinel Topology (İki Ayrı Cluster)
description: Lumix'in iki ayrı Sentinel cluster'ı — auth-redis (AOF + noeviction) ve cache-redis (no persist + allkeys-lfu). Master + 2 replica + 3 sentinel. Failover akışı + Spring config.
sidebar_position: 2
---

## Bu sayfa ne anlatıyor?

Lumix Redis'i **iki ayrı Sentinel cluster** olarak çalıştırır: biri **auth-redis** (token, session, refresh — kayıp kabul edilemez), diğeri **cache-redis** (cache, scope cache, WebSocket pub/sub, distributed lock — kaybedilebilir, evict olabilir). Bu sayfa neden iki cluster kararı verildiğini, Sentinel topology'nin nasıl çalıştığını, failover akışını ve Spring konfigürasyonunu anlatır.

## 1. Bu nedir? (Sıfırdan)

**Redis Sentinel** = Redis'i **High Availability** (HA) ile çalıştırmanın standart yolu. Tek master, N replica ve M sentinel sürecinden oluşur. Sentinel'ler master'ı monitor eder; master düşerse otomatik **failover** yapıp bir replica'yı yeni master ilan eder.

**Sharding** olmayan, single-shard HA topology. Lumix bugün **Cluster mode'a (sharded)** gerek duymuyor — gerekirse ileride geçilir.

### Topology görsel

```
            ┌───────────────┐    ┌───────────────┐    ┌───────────────┐
            │  Sentinel 1   │    │  Sentinel 2   │    │  Sentinel 3   │
            │   (quorum)    │    │   (quorum)    │    │   (quorum)    │
            └───────┬───────┘    └───────┬───────┘    └───────┬───────┘
                    │                    │                    │
                    └────────────┬───────┴────────────────────┘
                                 │  (sentinel'ler birbirini görüyor)
                                 │
                ┌────────────────┼────────────────┐
                │                ▼                │
                │       ┌───────────────┐        │
                │       │    MASTER     │        │
                │       │  (read+write) │        │
                │       └───────┬───────┘        │
                │               │ (replication)  │
                │               │                │
                │  ┌────────────┴────────────┐   │
                │  ▼                         ▼   │
                │ ┌──────────────┐   ┌──────────────┐
                │ │  Replica 1   │   │  Replica 2   │
                │ │  (read-only) │   │  (read-only) │
                │ └──────────────┘   └──────────────┘
                │
                └─ Tek Sentinel cluster (örn. auth-redis)
```

### "İki ayrı cluster" ne demek?

Yukarıdaki topology **iki kez** kuruluyor:
- **auth-redis** cluster (token/session için)
- **cache-redis** cluster (cache/lock/pub-sub için)

Her birinin kendi master, replica, sentinel'ları var. Birbirleriyle paylaşımsız.

## 2. Hangi problemi çözüyor?

### 2.1. Auth ile cache'i karıştırma riski
Auth verisi (token, session) kaybolmamalı. Cache verisi (entity snapshot, scope cache) **kaybolabilir** (DB'den tekrar hesaplanır). İki farklı veri profili tek cluster'da karışırsa:
- Eviction policy çatışır (`noeviction` veya `allkeys-lfu`)
- Persistence çatışır (AOF gerekli mi değil mi?)
- OOM auth verisini de etkiler

### 2.2. Single Point of Failure
Tek Redis master düşerse tüm uygulama auth yapamaz hale gelir. Sentinel HA bunu çözer.

### 2.3. Memory pressure izolasyonu
Cache büyür ve OOM'a yaklaşırsa **sadece cache** etkilenmeli. Auth bağımsız çalışmaya devam etsin.

### 2.4. Independent scaling
Cache'i büyütmek için ayrı node lazımsa, auth'a dokunmadan ekleyebilmek.

### 2.5. Independent restart / maintenance
Cache cluster'ı bakım için restart edilebilir → auth çalışmaya devam eder.

## 3. Nasıl çözüyor? (Mekanizma + diyagram)

### 3.1. Sentinel görevleri

Sentinel **client değil, monitor**. Her sentinel:

1. **Monitor** — master ve replica'ları periyodik ping'ler.
2. **Notification** — admin'e webhook/log ile event bildirir.
3. **Failover** — master down algılanırsa quorum ile karar verip yeni master seçer.
4. **Configuration provider** — client'lar Sentinel'a sorar "kim master?".

### 3.2. Quorum mantığı

```
3 Sentinel → quorum = 2

Sentinel-1: "master ping atmadı, sanırım down" → S-down (subjective)
              │
              ▼
Sentinel-2 ve 3 ile doğrula
              │
              ▼
2/3 sentinel "down" diyorsa → O-down (objective)
              │
              ▼
Leader election (Raft-like)
              │
              ▼
Seçilen leader replica'ları analiz eder:
  - en güncel replication offset
  - priority
              │
              ▼
Yeni master ilan (SLAVEOF NO ONE)
Diğer replica'lar yeni master'a yönlendirilir (SLAVEOF newMaster)
              │
              ▼
Sentinel'lar config güncellenir
Client'lar reconnect ile yeni master'ı öğrenir
```

### 3.3. Failover timeline

```
T=0     master crash
T=10s   sentinel ping timeout (down-after-milliseconds=5000)
T=10s   S-down (subjective)
T=11s   sentinel'lar arası gossip
T=12s   O-down (objective) — quorum sağlandı
T=12s   leader election
T=13s   yeni master seçimi + promotion
T=14s   replica'lar yeni master'a redirect
T=14s+  client connect retry → Sentinel.getMaster() → yeni master
```

Pratikte **15-30sn** failover penceresi. Lumix bu pencerede:
- Auth: Spring filter Redis timeout (50ms) → 503 dönülebilir
- Cache: cache miss → DB'ye düşer (yavaşlama, ama kesinti yok)

### 3.4. Client-side davranış (Lettuce/Spring)

Lettuce client Sentinel-aware:
- Connect: `RedisURI.create("sentinel://...")` ile sentinel listesi verilir
- Master bilgisi sentinel'dan alınır
- Master değişince Lettuce reconnect eder, yeni master'a otomatik bağlanır

```java
// Spring Boot otomatik konfigüre eder
spring:
  data:
    redis:
      sentinel:
        master: auth-master
        nodes:
          - auth-sentinel-0:26379
          - auth-sentinel-1:26379
          - auth-sentinel-2:26379
      timeout: 100ms
```

### 3.5. Lumix'in iki cluster matrisi

| Özellik | auth-redis | cache-redis |
|---|---|---|
| Persistence | AOF everysec | yok |
| Eviction | `noeviction` | `allkeys-lfu` |
| Maxmemory | 4 GB | 8-16 GB |
| Replica sayısı | 2 | 2 |
| Sentinel sayısı | 3 | 3 |
| Veri tipi | Token, session, refresh | Cache, scope, lock, pub/sub |
| Veri kaybı toleransı | Sıfır | Yüksek |
| Failover öncelik | Çok yüksek | Yüksek |
| Backup | AOF arşivlenir | yok |

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. K8s deployment

Lumix Redis'i K8s'te **Bitnami Helm chart** veya **Redis Operator** ile çalıştırır.

- StatefulSet (Redis pod'ları)
- Persistent Volume (auth için, AOF dosyası)
- Headless Service (sentinel discovery)
- Affinity rules (master ile replica farklı node'larda)

### 4.2. Naming

| Component | Service DNS |
|---|---|
| auth-redis master | `auth-redis-master.redis.svc.cluster.local:6379` |
| auth-redis sentinel | `auth-sentinel-0.redis.svc.cluster.local:26379` |
| cache-redis master | `cache-redis-master.redis.svc.cluster.local:6379` |
| cache-redis sentinel | `cache-sentinel-0.redis.svc.cluster.local:26379` |

Application Sentinel'lara bağlanır, master'a değil — Sentinel doğru master'a yönlendirir.

### 4.3. ACL ve TLS

```
# Sentinel + master/replica TLS aktif
tls-port 6379
tls-cert-file /tls/redis.crt
tls-key-file /tls/redis.key
tls-ca-cert-file /tls/ca.crt

# ACL (user-level)
user default off
user identity-write on >****PASSWORD**** ~auth:* ~refresh:* ~session:* +@write +@read +@connection
user microservice-read on >****PASSWORD**** ~access:* ~session:* +@read +@connection
```

`identity-service` yazma, diğer servisler sadece read-only auth keyspace'i görür.

### 4.4. Spring konfigürasyonu (iki cluster)

```java
@Configuration
public class RedisConfig {

    @Bean(name = "authRedisConnectionFactory")
    public LettuceConnectionFactory authRedis(
            @Value("${lumix.redis.auth.master}") String master,
            @Value("${lumix.redis.auth.sentinels}") List<String> nodes) {
        RedisSentinelConfiguration cfg = new RedisSentinelConfiguration().master(master);
        nodes.forEach(n -> {
            String[] parts = n.split(":");
            cfg.sentinel(parts[0], Integer.parseInt(parts[1]));
        });
        cfg.setUsername("identity-write");
        cfg.setPassword(RedisPassword.of(System.getenv("AUTH_REDIS_PASSWORD")));
        return new LettuceConnectionFactory(cfg);
    }

    @Bean(name = "cacheRedisConnectionFactory")
    @Primary
    public LettuceConnectionFactory cacheRedis(
            @Value("${lumix.redis.cache.master}") String master,
            @Value("${lumix.redis.cache.sentinels}") List<String> nodes) {
        RedisSentinelConfiguration cfg = new RedisSentinelConfiguration().master(master);
        nodes.forEach(n -> {
            String[] parts = n.split(":");
            cfg.sentinel(parts[0], Integer.parseInt(parts[1]));
        });
        cfg.setUsername("cache-user");
        cfg.setPassword(RedisPassword.of(System.getenv("CACHE_REDIS_PASSWORD")));
        return new LettuceConnectionFactory(cfg);
    }

    @Bean
    public StringRedisTemplate authRedisTemplate(
            @Qualifier("authRedisConnectionFactory") LettuceConnectionFactory cf) {
        return new StringRedisTemplate(cf);
    }

    @Bean
    @Primary
    public StringRedisTemplate cacheRedisTemplate(
            @Qualifier("cacheRedisConnectionFactory") LettuceConnectionFactory cf) {
        return new StringRedisTemplate(cf);
    }
}
```

### 4.5. application.yml

```yaml
lumix:
  redis:
    auth:
      master: auth-master
      sentinels:
        - auth-sentinel-0.redis:26379
        - auth-sentinel-1.redis:26379
        - auth-sentinel-2.redis:26379
      timeout-ms: 100
    cache:
      master: cache-master
      sentinels:
        - cache-sentinel-0.redis:26379
        - cache-sentinel-1.redis:26379
        - cache-sentinel-2.redis:26379
      timeout-ms: 50
```

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

| Alternatif | Tartışma |
|---|---|
| **Tek Redis (no HA)** | Master down = total auth outage. **Elendi.** |
| **Tek Sentinel cluster, tek namespace** | Auth ve cache aynı eviction/persistence ile uyumsuz. **Elendi.** |
| **Tek Sentinel cluster, iki database (DB index 0 vs 1)** | Eviction policy global; veri profili ayrılmaz. **Elendi.** |
| **Redis Cluster (sharded)** | Bugün gerek yok, operational karmaşık. **İleride göz atılabilir.** |
| **AWS Elasticache / managed** | Self-host şartı. **Elendi.** |
| **İki ayrı Sentinel cluster (Lumix)** | ✓ Net izolasyon, profil-uygun konfig. |

### Trade-off'lar

- **2× resource:** İki cluster = 2× pod, 2× CPU/RAM. Kabul edilebilir maliyet.
- **Operational complexity:** İki cluster'ı izlemek, backup almak, upgrade etmek. Otomasyon ile yönetilir.
- **Network hop:** Aynı namespace'te, latency etkisiz.
- **Cross-cluster transaction yok:** İki cluster arası atomik işlem yapılamaz. **Bu zaten gereksiz** — auth ile cache verisi farklı concerns.

### Tekrar değerlendirme şartı

- Total RAM 64GB+ olduğunda, single-shard yetersiz olabilir → cache-redis Cluster mode'a geçer.
- Auth-redis için "zero downtime upgrade" gereksinimi sıkılaşırsa → Redis Enterprise düşünülebilir.

## 6. Pratik örnek

### 6.1. Servisin iki RedisTemplate kullanması

```java
@Service
@RequiredArgsConstructor
public class AuthService {

    @Qualifier("authRedisTemplate")
    private final StringRedisTemplate auth;

    @Qualifier("cacheRedisTemplate")
    private final StringRedisTemplate cache;

    public void saveSession(UUID sid, SessionRecord r) {
        auth.opsForHash().putAll("session:" + sid, r.asMap());
        auth.expire("session:" + sid, Duration.ofMinutes(30));
    }

    public void savePermissionCache(UUID uid, UUID tid, EffectivePermissionSet eps) {
        cache.opsForValue().set(
            "user:permissions:" + uid + ":" + tid,
            json.write(eps),
            Duration.ofMinutes(10));
    }
}
```

### 6.2. Health check ve readiness

```java
@Component
@RequiredArgsConstructor
public class RedisHealthIndicator implements HealthIndicator {

    @Qualifier("authRedisConnectionFactory")
    private final LettuceConnectionFactory authCf;
    @Qualifier("cacheRedisConnectionFactory")
    private final LettuceConnectionFactory cacheCf;

    @Override
    public Health health() {
        Health.Builder b = Health.up();
        try (var conn = authCf.getConnection()) {
            conn.ping();
            b.withDetail("auth-redis", "UP");
        } catch (Exception e) {
            return Health.down().withDetail("auth-redis", e.getMessage()).build();
        }
        try (var conn = cacheCf.getConnection()) {
            conn.ping();
            b.withDetail("cache-redis", "UP");
        } catch (Exception e) {
            b.status(Status.UNKNOWN).withDetail("cache-redis", e.getMessage());
        }
        return b.build();
    }
}
```

Not: Auth-redis DOWN = kritik (servis 503). Cache-redis DOWN = degraded ama servis ayakta (DB'den okur).

### 6.3. Sentinel config (sentinel.conf)

```ini
port 26379
sentinel monitor auth-master <master-ip> 6379 2
sentinel down-after-milliseconds auth-master 5000
sentinel failover-timeout auth-master 10000
sentinel parallel-syncs auth-master 1
sentinel auth-pass auth-master ****PASSWORD****
sentinel tls-port 26379
sentinel tls-cert-file /tls/sentinel.crt
sentinel tls-key-file /tls/sentinel.key
sentinel tls-ca-cert-file /tls/ca.crt
```

### 6.4. Promethus metric (önemli alarm'lar)

- `redis_up{role="master"}` == 1 (failover sonrası geçici 0 olur)
- `redis_memory_used_bytes / redis_maxmemory_bytes` > 0.85 → uyarı
- `redis_connected_clients` ani düşüş → reconnect storm sinyali
- `sentinel_master_status` != 1 → master sağlıksız

### 6.5. Backup (auth-redis için)

```
# Cronjob (örnek)
0 */6 * * * redis-cli BGREWRITEAOF
0 1 * * * tar czf /backup/auth-redis-$(date +%Y%m%d).tar.gz /data/appendonly.aof
0 2 * * * rclone copy /backup/ s3:lumix-backup/
```

## 7. Dikkat edilecek tuzaklar

- **Sentinel quorum 1 yapmak.** Tek sentinel = split-brain riski. **Kural:** 3+ sentinel.
- **Sentinel'ları aynı node'a koymak.** Node failure = tüm sentinel ölür. **Kural:** anti-affinity ile farklı node'lar.
- **Client'ı doğrudan master'a bağlamak.** Failover sonrası eski IP'ye gider, fail. **Kural:** her zaman Sentinel discovery.
- **Auth + cache tek cluster'da.** Eviction policy çatışması, OOM auth'u etkiler. **Kural:** iki ayrı cluster.
- **Replica read-only kapatmak.** Yanlış konfigle replica yazma kabul eder, master ile divergence. **Kural:** `replica-read-only yes`.
- **`save ""` yapmamak (cache-redis).** RDB devre dışı kalmazsa periyodik fork pauses. **Kural:** cache'te `save ""`.
- **AOF rewrite sırasında memory spike.** Fork ile copy-on-write, geçici 2× RAM. **Kural:** maxmemory < node RAM / 2.
- **Sentinel failover testini yapmamak.** Production'da ilk failover'da sürprizler. **Kural:** drill (chaos test) ile düzenli failover deneme.
- **TLS yok.** Internal trafik bile şifreli olmalı; secrets sniff'lenebilir. **Kural:** TLS + ACL zorunlu.
- **Persistent volume yedeği yok.** Disk fail = AOF kayıp. **Kural:** PV snapshot + offsite backup.
- **Cache cluster failover sırasında auth bağımlılığı kurmak.** Auth, cache-redis'in kalkmasını bekleyemez. **Kural:** auth servisi sadece auth-redis'e bağımlı.

## 8. Diğer konularla ilişkisi

- [Redis Temelleri](./01-redis-fundamentals.md) — Redis nedir
- [Cache-Aside Pattern](./03-cache-aside-pattern.md) — cache-redis kullanımı
- [Cache Invalidation](./04-cache-invalidation.md) — entity/view cache
- [TTL Strategy](./05-ttl-strategy.md) — TTL kararı
- [Distributed Lock — Redisson](./06-distributed-lock-redisson.md) — cache-redis lock
- [Fully Stateful Token Modeli](../04-authentication-authorization/01-stateful-token-model.md) — auth-redis kullanımı
- [Session & Device Lifecycle](../04-authentication-authorization/02-session-device-lifecycle.md) — auth-redis key matrix

## 9. Daha derine inmek için

- Redis: [Sentinel Documentation](https://redis.io/docs/management/sentinel/)
- Redis: [High Availability with Sentinel](https://redis.io/docs/management/sentinel/#example-sentinel-deployments)
- Bitnami Helm: [redis chart](https://github.com/bitnami/charts/tree/main/bitnami/redis)
- Spring Data: [Sentinel Configuration](https://docs.spring.io/spring-data/redis/reference/redis/connection/sentinel.html)
- Search keywords:
  - `redis sentinel failover quorum`
  - `redis high availability self hosted`
  - `auth cache separation redis`
  - `redis tls acl best practices`
  - `kubernetes redis statefulset bitnami`

## 10. Sözlük

- **Redis Sentinel** — Redis için monitoring + automatic failover sağlayan sistem.
- **Master** — Yazma kabul eden ana Redis node'u.
- **Replica** — Master'dan replicate eden read-only node.
- **Quorum** — Sentinel kararının kabul edilmesi için gereken minimum sayı.
- **S-down / O-down** — Subjective / Objective down (tek sentinel görüşü vs çoğunluk).
- **Failover** — Master down olunca replica'nın yeni master ilan edilmesi.
- **Split-brain** — İki master oluşma riski (yetersiz quorum'da).
- **AOF rewrite** — AOF dosyasının compact edilmesi.
- **Sharding / Cluster mode** — Verinin birden çok master arasında bölünmesi (Lumix kullanmıyor).
- **ACL** — Redis 6+ user-based authorization sistemi.
- **PV (Persistent Volume)** — K8s'te kalıcı disk; auth-redis için zorunlu.

