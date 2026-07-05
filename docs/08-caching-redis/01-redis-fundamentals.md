---
title: Redis Temelleri
description: Redis nedir, in-memory key-value store + data structures (String, Hash, List, Set, ZSet, Stream), persistence (RDB vs AOF), eviction policies.
sidebar_position: 1
---

## Bu sayfa ne anlatıyor?

Lumix'in **iki ayrı Redis cluster'ı** çalıştırıyor: biri auth (token + session), diğeri cache. Bu sayfa Redis'in **kendisini** sıfırdan açıklar: ne işe yarar, hangi data structure'ları sunar, persistence (RDB vs AOF) farkı nedir, eviction policy seçimleri nelerdir. Sonraki sayfalarda Lumix'in spesifik topology kararları ve cache pattern'ları gelir.

## 1. Bu nedir? (Sıfırdan)

**Redis** = **REmote DIctionary Server**. Açık kaynak, **in-memory** (RAM'de yaşar) bir veri deposu. Key-value gibi başlar ama çok daha fazlasıdır: List, Hash, Set, Sorted Set, Stream, Pub/Sub, Geo, Bitfield gibi zengin veri yapıları sunar.

### Günlük analoji

**PostgreSQL** = arşiv dolabın. Her şey saklanır, dayanıklı, ama erişmek için "açıp aramak" gerekir (disk I/O). **Redis** = mutfak tezgâhı. Sık kullandığın baharatları/aletleri orada tutarsın; anında ulaşılır ama az yer var ve elektrik gidince bazıları kaybolur.

### "In-memory" ne demek?

Redis tüm veriyi **RAM'de** tutar. Bu nedenle:
- ✅ Çok hızlı (mikrosaniye seviyesinde)
- ❌ Pahalı (RAM bittiğinde verinin bir kısmını silmen lazım — eviction)
- ❌ Sunucu yeniden başlarsa veri uçar — bu yüzden **persistence** mekanizmaları var

### Redis vs Memcached

| Özellik | Redis | Memcached |
|---|---|---|
| Data structures | Zengin (Hash, List, Set, ZSet, Stream) | Sadece string |
| Persistence | RDB + AOF | Yok |
| Pub/Sub | Var | Yok |
| Scripting | Lua | Yok |
| Cluster | Built-in | Client-side |

Lumix Redis'i seçti — sadece cache değil, **state engine** olarak kullanıyor (session, lock, pub/sub).

## 2. Hangi problemi çözüyor?

### 2.1. Yavaş ve pahalı DB sorgularını azaltmak
Bir kullanıcının permission listesi 5 tablonun JOIN'i ile çıkıyor. Her istekte hesaplamak yerine sonucu Redis'te tut → mikrosaniyede oku.

### 2.2. Cross-pod paylaşımlı state
Her pod kendi memory'sini görür. Pod'lar arası ortak state gerekiyorsa (session, lock, pub/sub) shared store şart. Redis bu rolü oynar.

### 2.3. Hızlı counter / rate limit
"Bu IP'den son 1dk'da kaç istek?" — DB'ye yazıp okumak yavaş; Redis `INCR` + `EXPIRE` atomic ve mikrosaniye.

### 2.4. Leaderboard / sıralı yapılar
Sorted Set (ZSet) ile "skor sıralı" işlemler O(log N).

### 2.5. Pub/Sub mesajlaşma
Hafif, real-time mesaj fan-out (Lumix WebSocket backplane).

### 2.6. Distributed lock
"Aynı anda iki pod aynı işi yapmasın" — SETNX + TTL ile lock.

## 3. Nasıl çözüyor? (Mekanizma + diyagram)

### 3.1. Data structures

| Tip | Komutlar | Lumix kullanımı |
|---|---|---|
| **String** | `SET`, `GET`, `INCR`, `EXPIRE` | Counter, simple cache, distributed lock |
| **Hash** | `HSET`, `HGET`, `HGETALL` | Object cache (session metadata, token state) |
| **List** | `LPUSH`, `RPOP`, `BLPOP` | Queue (lightweight), recent items |
| **Set** | `SADD`, `SMEMBERS`, `SISMEMBER` | Unique collections (user's sessions, permission set) |
| **Sorted Set (ZSet)** | `ZADD`, `ZRANGE`, `ZRANGEBYSCORE` | Leaderboard, time-ordered queue, rate limit |
| **Stream** | `XADD`, `XREAD`, `XGROUP` | Persistent message log (Lumix Kafka kullandığı için minimal) |
| **Bitmap / HyperLogLog** | `SETBIT`, `PFADD` | Cardinality, presence (rarely used) |
| **Pub/Sub** | `PUBLISH`, `SUBSCRIBE` | WebSocket backplane (cache-redis) |

### 3.2. Memory model + persistence

```
                ┌─────────────────────────┐
                │      RAM (in-memory)    │  ← Tüm okuma/yazma burada
                │                         │
                │    keyspace: {          │
                │      "k1" → "v1"        │
                │      "k2" → Hash{...}   │
                │      ...                │
                │    }                    │
                │                         │
                └────────────┬────────────┘
                             │
                  ┌──────────┴───────────┐
                  ▼                      ▼
              ┌────────┐            ┌──────────┐
              │  RDB   │            │   AOF    │
              │ snap   │            │ append   │
              │ (peri- │            │ log (her │
              │ odik   │            │ write)   │
              │ snap)  │            │          │
              └────────┘            └──────────┘
              Disk                    Disk
```

**RDB (Redis Database):**
- Periyodik snapshot (örn. 5dk'da bir veya N write'tan sonra)
- Compact, fast restore
- ❌ Snapshot arasında kalan write'lar kaybolur

**AOF (Append-Only File):**
- Her write operation log'lanır
- En kötü 1sn veri kaybı (fsync = everysec)
- ❌ AOF dosyası RDB'den büyük

**Lumix kararı:**
- **auth-redis** → AOF only (everysec). Token kaybı kabul edilemez.
- **cache-redis** → persistence kapalı. Soyulabilir cache.

### 3.3. Eviction policies

RAM bittiğinde Redis ne yapar?

| Policy | Davranış | Lumix |
|---|---|---|
| `noeviction` | Yeni write reddedilir (`OOM error`) | **auth-redis** |
| `allkeys-lru` | En az kullanılan key'i sil | — |
| `allkeys-lfu` | En az SIK kullanılan (frequency) key'i sil | **cache-redis** |
| `volatile-lru` | TTL'i olan key'lerden LRU | — |
| `volatile-ttl` | TTL'i en yakın olan key'i sil | — |
| `allkeys-random` | Random sil | — |

**LRU vs LFU:**
- **LRU** = "en son ne zaman kullanıldı?" — recent burst sonrası eski popüler key'ler atılır.
- **LFU** = "ne kadar sık kullanılıyor?" — uzun vadeli sıklığı dikkate alır.

Lumix'te cache verileri için LFU daha mantıklı (yıllarca sık kullanılan key'ler önemli, geçici burst yanıltıcı).

### 3.4. TTL (Time To Live)

```
SET mykey "value" EX 60    → 60 saniye sonra otomatik silinir
EXPIRE mykey 60            → mevcut key'e TTL set et
TTL mykey                  → kalan süre (saniye)
PERSIST mykey              → TTL'i kaldır
```

Lumix tüm cache key'lerine TTL koyar. Sebepler:
- Memory baskısı; sınırsız büyüme yok
- Eventually consistent ile uyum
- "Bayat" veri'nin ömrü sınırlı

### 3.5. Komut performansı

| Komut sınıfı | Big-O | Pratik |
|---|---|---|
| Single key (GET/SET/INCR) | O(1) | Mikrosaniye |
| HGET/HSET | O(1) | Mikrosaniye |
| LPUSH/RPOP | O(1) | Mikrosaniye |
| ZADD/ZRANGE | O(log N) | Mikro-mili |
| SMEMBERS (büyük set) | O(N) | DİKKAT |
| KEYS pattern | O(N) | YASAK production'da |
| SCAN | O(1) cursor-based | KEYS yerine kullan |

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Redis versiyon ve mode

- **Redis 7.x**
- **Sentinel** mode (HA — master + 2 replica + 3 sentinel)
- **Single shard** (cluster mode değil; sharding henüz gerekmiyor)
- İki ayrı Sentinel cluster (auth + cache)

### 4.2. Veri tipi kullanımı (Lumix'te)

| Veri | Tip | Cluster | TTL |
|---|---|---|---|
| `access:{jti}` | Hash | auth | 15dk |
| `refresh:{hash}` | Hash | auth | 7-30gün |
| `session:{sid}` | Hash | auth | sliding+absolute |
| `user:sessions:{uid}` | Set | auth | yok |
| `user:permissions:{uid}:{tid}` | Hash (JSON) | cache | 10dk |
| `scope:effective:{uid}:{tid}` | String (JSON) | cache | 5dk |
| `user:pod:{userId}` | Hash | cache | 60sn |
| `rate:limit:{ip}:{ep}` | String INCR | cache | 60sn |
| `lock:payment:{order_id}` | String | cache (via Redisson) | 30sn |
| `cache:entity:students:{id}` | Hash | cache | 5dk |

### 4.3. Client library

**Lettuce** (Spring Boot 3 default). Asenkron, reactive support, Sentinel uyumlu.

```yaml
spring:
  data:
    redis:
      sentinel:
        master: auth-master
        nodes:
          - auth-sentinel-0:26379
          - auth-sentinel-1:26379
          - auth-sentinel-2:26379
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
          min-idle: 2
```

### 4.4. Komut limitleri / yasak komutlar

Lumix policy:
- ❌ `KEYS pattern` (production'da yasak)
- ❌ `FLUSHALL`, `FLUSHDB` (sadece dev)
- ❌ `DEBUG SLEEP`
- ✅ `SCAN` (KEYS yerine)
- ✅ Pipeline (bulk operasyon)

### 4.5. Encoding

- Key'ler her zaman ASCII, namespace: `<service>:<entity>:<id>`
- Değerler: JSON (UTF-8) veya binary (Hash field native)
- Tüm key'lerde **tenant_id namespace yok** (key zaten user/entity id ile unique). Tenant context her zaman key parametresinde mevcut.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

| Alternatif | Tartışma |
|---|---|
| **Memcached** | Sadece string + cache; lock, pub/sub yok. **Elendi.** |
| **Hazelcast** | JVM-native, daha pahalı operasyon. **Elendi.** |
| **etcd** | Strong consistency ama düşük throughput. Auth cache için overkill. **Elendi.** |
| **Apache Ignite** | Compute + cache; aşırı geniş özellik. **Elendi.** |
| **DragonflyDB / KeyDB** | Redis-uyumlu yeni nesil. Lumix gelecekte göz atabilir; bugün Redis stabil. |
| **Redis** | ✓ Olgun, geniş community, zengin data structures. |

### Trade-off'lar

- **RAM-bound:** Cache büyüklüğü RAM'e bağlı. Sharding gerekirse Cluster mode'a geçilir.
- **Persistence trade-off:** AOF veri güvenliği + disk yazımı vs RDB hız + veri kaybı potansiyeli.
- **Single-threaded core:** Redis kommand işleme tek thread (I/O multiplexing var). Long-running komutlar (büyük SMEMBERS) tüm cluster'ı bloke eder.
- **Sentinel quorum:** Network partition'da split-brain riskine karşı 3+ sentinel zorunlu.

## 6. Pratik örnek

### 6.1. String + counter (rate limit)

```java
@Service
@RequiredArgsConstructor
public class RateLimiter {

    private final StringRedisTemplate redis;

    /**
     * Token bucket benzeri basit count-based limit.
     * Saniyede max N istek.
     */
    public boolean allow(String ipKey, int limit, Duration window) {
        String key = "rate:limit:" + ipKey;
        Long count = redis.opsForValue().increment(key);
        if (count == 1) {
            redis.expire(key, window);
        }
        return count <= limit;
    }
}
```

### 6.2. Hash (session)

```java
public void saveSession(UUID sid, SessionRecord r) {
    String key = "session:" + sid;
    Map<String, String> hash = Map.of(
        "uid", r.userId().toString(),
        "status", r.status().name(),
        "created_at", r.createdAt().toString());
    redis.opsForHash().putAll(key, hash);
    redis.expire(key, Duration.ofMinutes(30));
}

public Optional<SessionRecord> readSession(UUID sid) {
    Map<Object, Object> entries = redis.opsForHash().entries("session:" + sid);
    if (entries.isEmpty()) return Optional.empty();
    return Optional.of(SessionRecord.fromMap(entries));
}
```

### 6.3. Set (user's active sessions)

```java
public Set<UUID> listUserSessions(UUID userId) {
    Set<String> sids = redis.opsForSet().members("user:sessions:" + userId);
    return sids == null ? Set.of()
        : sids.stream().map(UUID::fromString).collect(Collectors.toSet());
}

public void addUserSession(UUID userId, UUID sid) {
    redis.opsForSet().add("user:sessions:" + userId, sid.toString());
}
```

### 6.4. Sorted Set (rate limit precise — sliding window)

```java
public boolean allowSlidingWindow(String key, int limit, Duration window) {
    long now = Instant.now().toEpochMilli();
    long windowStart = now - window.toMillis();

    // Pipeline: temizle + sayı al + ekle
    var result = redis.executePipelined((RedisCallback<Object>) conn -> {
        ZSetOperations<String, String> z = ((StringRedisConnection)conn).zSetCommands().asGeneric();
        conn.zSetCommands().zRemRangeByScore(key.getBytes(), 0, windowStart);
        conn.zSetCommands().zCard(key.getBytes());
        conn.zSetCommands().zAdd(key.getBytes(), now, String.valueOf(now).getBytes());
        conn.keyCommands().expire(key.getBytes(), window.toSeconds() + 10);
        return null;
    });
    long count = (Long) result.get(1);
    return count < limit;
}
```

### 6.5. SCAN (KEYS yerine)

```java
public void deleteByPattern(String pattern) {
    ScanOptions opts = ScanOptions.scanOptions().match(pattern).count(100).build();
    Cursor<byte[]> cursor = redis.executeWithStickyConnection(conn ->
        conn.keyCommands().scan(opts));
    cursor.forEachRemaining(key -> redis.delete(new String(key)));
    cursor.close();
}
```

### 6.6. Persistence config (redis.conf)

```ini
# auth-redis cluster
appendonly yes
appendfsync everysec
auto-aof-rewrite-percentage 100
auto-aof-rewrite-min-size 64mb
save ""                              # RDB devre dışı
maxmemory-policy noeviction
maxmemory 4gb

# cache-redis cluster
appendonly no
save ""
maxmemory-policy allkeys-lfu
maxmemory 8gb
```

## 7. Dikkat edilecek tuzaklar

- **`KEYS *` production'da.** O(N), tüm Redis bloklanır. **Çözüm:** `SCAN`.
- **`SMEMBERS` büyük set.** O(N) → tüm cluster yavaşlar. **Çözüm:** `SSCAN`, veya bucket'a böl.
- **TTL'siz key set etmek.** Memory sonsuz büyür. **Kural:** her cache key TTL'li olmalı.
- **Auth cluster'da eviction.** Token random kaybolur, kullanıcılar çıkış yer. **Kural:** auth = `noeviction`.
- **Cache cluster'da `noeviction`.** Memory dolunca tüm write fail. **Kural:** cache = `allkeys-lfu`.
- **Single-threaded'ı unutmak.** Long Lua script tüm Redis'i kilitler. **Kural:** Lua kısa, atomic.
- **TLS / auth yok.** Network'te sniff edilebilir. **Kural:** Sentinel TLS + ACL.
- **Pipeline yerine N×network round-trip.** 100 GET = 100 ms latency. **Çözüm:** pipeline ile tek round-trip.
- **`MULTI/EXEC` transaction sandığını sanmak.** Redis transaction rollback yok; sadece sıralı execution. Hata olursa diğerleri çalışır.
- **Persistence kapalı master + replica restart.** Replica boş başlar, yanlışlıkla replication overwrites master. **Kural:** `replica-read-only yes` + dikkatli restart sırası.
- **Memory metric'i izlememek.** OOM = anlık production kazası. **Kural:** Prometheus `redis_memory_used_bytes` alert.
- **Tek Sentinel.** Failover quorum gerek (3 sentinel min). **Kural:** odd sayı sentinel.

## 8. Diğer konularla ilişkisi

- [Redis Sentinel Topology](./redis-sentinel-topology) — Lumix'in iki cluster tasarımı
- [Cache-Aside Pattern](./cache-aside-pattern) — `@Cacheable` ile basit cache
- [Cache Invalidation](./cache-invalidation) — entity/view cache silme
- [TTL Strategy](./ttl-strategy) — TTL kararı
- [Distributed Lock — Redisson](./distributed-lock-redisson) — RedLock
- [Fully Stateful Token Modeli](../04-authentication-authorization/stateful-token-model) — auth-redis kullanımı
- [Redis Pub/Sub Backplane](../07-realtime-websocket/redis-pubsub-backplane) — cache-redis pub/sub

## 9. Daha derine inmek için

- Redis: [Official Documentation](https://redis.io/docs/)
- Redis: [Data types](https://redis.io/docs/data-types/)
- Redis: [Persistence (RDB vs AOF)](https://redis.io/docs/management/persistence/)
- Redis: [Eviction Policies](https://redis.io/docs/reference/eviction/)
- Spring: [Spring Data Redis Reference](https://docs.spring.io/spring-data/redis/reference/)
- Search keywords:
  - `redis data structures use cases`
  - `redis rdb vs aof tradeoffs`
  - `redis eviction policy lfu vs lru`
  - `redis pipeline vs multi exec`
  - `redis sentinel vs cluster`

## 10. Sözlük

- **Redis** — In-memory key-value + zengin data structure store.
- **In-memory** — Veri RAM'de tutulur; disk sadece persistence için.
- **RDB** — Periyodik snapshot persistence.
- **AOF (Append-Only File)** — Her write'ı log'layan persistence; everysec fsync default.
- **Eviction** — Memory dolunca key silme politikası.
- **LRU / LFU** — Least Recently / Frequently Used eviction algoritmaları.
- **TTL (Time To Live)** — Key'in otomatik silineceği süre.
- **Pipeline** — Birden çok komutu tek round-trip'te gönderme.
- **MULTI/EXEC** — Sıralı komut grubu (rollback yok).
- **SCAN** — Cursor-based, non-blocking key iteration.
- **Sentinel** — Redis HA topology (master/replica + monitoring + failover).
- **Cluster mode** — Sharded Redis topology (Lumix kullanmıyor).

