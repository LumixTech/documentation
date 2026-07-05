---
title: Cache-Aside Pattern (Lazy Loading)
description: Cache-aside flow, Spring @Cacheable, hit/miss davranışı, ne cache'lenir ne cache'lenmez, Lumix'in pattern uygulaması.
sidebar_position: 3
---

## Bu sayfa ne anlatıyor?

Cache'in **en yaygın pattern'i**: önce cache'e bak, miss olursa DB'den oku ve cache'e yaz. Bu sayfa cache-aside (a.k.a. lazy loading) pattern'inin nasıl çalıştığını, Spring `@Cacheable` ile nasıl uygulandığını, **hangi verilerin cache'lendiğini ve hangilerinin asla cache'lenmediğini** Lumix kararları üzerinden anlatır.

## 1. Bu nedir? (Sıfırdan)

**Cache-aside (lazy loading) pattern:**

```
1. Uygulama veri istiyor
2. Cache'e bak → varsa (hit) → DÖN
                → yoksa (miss) ↓
3. DB'den oku
4. Cache'e yaz (TTL'li)
5. DÖN
```

Yani cache **kendi kendine veri yüklemez**, uygulama yükler ("aside" = "yanında"). Bunun karşıtı:
- **Read-through:** Cache library DB'ye gidip yükler (uygulama görmez).
- **Write-through:** Yazma da cache üzerinden geçer.
- **Write-behind:** Cache'e yaz, async DB'ye yaz.

Lumix **cache-aside** + **explicit invalidation** kombinasyonu kullanır. Detay: [Cache Invalidation](./cache-invalidation).

### Günlük analoji

Notların var:
- **DB** = uzun kütüphane dosyası, hep doğru, ama ulaşmak yavaş.
- **Cache** = ders öncesi defterindeki kısa notlar.
- **Cache-aside**: önce defterine bak; yoksa kütüphaneye git, oku, defterine bir kopya çıkar, sonra ne lazımsa defterden oku.

## 2. Hangi problemi çözüyor?

### 2.1. DB yükü azaltma
Aynı `student/{id}` 1000 kez sorgulanıyor olabilir. 999'unu cache'den karşıla, DB'ye 1 git.

### 2.2. Latency azaltma
DB sorgusu ~5-50ms; cache 0.3-1ms. 10x-50x hızlanma.

### 2.3. Tekrarlanan hesaplama
Permission resolution, scope resolution, complex JOIN sonuçları. Hesabı **bir kez** yap, cache'le.

### 2.4. External API maliyeti
Vergi/kart sorgulama API'leri pahalı; sonuçları cache'le (anlamlı olduğu kadarıyla, KVKK uygun).

### 2.5. Database failure'da degradation
Cache yaşıyorsa kısa süreli DB outage'larda read traffic'i karşılanır (eventual stale).

## 3. Nasıl çözüyor? (Mekanizma + diyagram)

### 3.1. Akış (sequence)

```
[Application]      [Cache-Redis]        [PostgreSQL]
     │                  │                    │
     │── GET key ──────►│                    │
     │                  │                    │
     │   Hit? ──────────┤                    │
     │                  │                    │
     │ ◄── value ───────│ (HIT — dur)        │
     │                  │                    │
─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─
     │── GET key ──────►│                    │
     │                  │                    │
     │ ◄── null ────────│ (MISS)             │
     │                                       │
     │── SELECT ... ────────────────────────►│
     │                                       │
     │ ◄── row ──────────────────────────────│
     │                  │                    │
     │── SETEX key val ►│                    │
     │                  │                    │
     │ ◄── value ────── (DB'den)             │
```

### 3.2. TTL ile self-healing

Cache verisi bayatlasa bile **TTL** ile sonu var. TTL dolduğunda ilk istek miss olur, fresh veri yüklenir. Bu **eventual consistency** kabul demek.

### 3.3. Hit/miss metric

Cache değerlendirmesinin kıstası:
- **Hit ratio** = hits / (hits + misses) — yüksek olmalı (%80+)
- **Miss penalty** — miss durumunda ek latency (DB sorgu süresi)
- **Stale ratio** — TTL bitmeden cache invalidate ile silinen oran

Lumix Prometheus ile:
- `lumix_cache_requests_total{cache="user-perms",result="hit"}`
- `lumix_cache_requests_total{cache="user-perms",result="miss"}`
- `lumix_cache_load_seconds` (miss durumunda DB sorgu süresi)

### 3.4. Spring `@Cacheable` (built-in)

```java
@Cacheable(cacheNames = "students", key = "#id")
public StudentDto getStudent(UUID id) {
    return studentRepo.findById(id).map(StudentDto::from).orElseThrow();
}
```

Behind the scenes:
1. Method call intercept (AOP)
2. CacheManager.get(key) → hit ise method'u atla, dön
3. Miss → method execute → return value cache'e yaz
4. Return.

### 3.5. Stampede (cache miss storm) sorunu

```
T=0      Çok popüler key'in TTL'i bitti
T=0+1ms  1000 paralel istek geldi, hepsi miss
T=0+1ms  1000 paralel DB sorgusu → DB diz çöker
```

**Çözüm:**
- **Probabilistic early expiration** (cache TTL'i bitmeden önce küçük bir yüzdeyle yenile)
- **Distributed lock** (ilk istek lock'lar, diğerleri bekler veya stale döner)
- **Request coalescing** (in-memory dedup)

Lumix yaklaşımı: hot key'ler için **distributed lock** (Redisson) + stampede protection. Detay [Distributed Lock](./distributed-lock-redisson).

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Ne cache'lenir?

| Tip | TTL | Cluster | Açıklama |
|---|---|---|---|
| Permission set (`user:permissions:{uid}:{tid}`) | 10dk | cache | Resolution sonucu |
| Scope (`scope:effective:{uid}:{tid}`) | 5dk | cache | ScopeResolver çıktısı |
| Tenant config (`tenant:config:{id}`) | 30dk | cache | DB'den nadiren değişir |
| Reference data (timezone, currency list) | 24h | cache | Değişmez |
| Entity snapshot (`student:{id}`) | 5dk | cache | Read-heavy lookups |
| Aggregated counts (`stats:tenant:{id}:students`) | 1dk | cache | Dashboard widgets |
| JWKS public keys | 5dk | cache | İmza doğrulama |

### 4.2. Ne ASLA cache'lenmez?

**Lumix kuralı: yanlış cache, doğru DB'den daha tehlikelidir.**

| Veri | Sebep |
|---|---|
| **Payment state** (`payment.status`) | Race condition → çift charge riski |
| **Auth tokens** | Stateful model: doğrudan auth-redis (cache değil) |
| **Audit log** | Immutable, sadece append; cache anlamsız |
| **Sensitive PII** (TC kimlik, sağlık verisi) | KVKK — encrypted DB only |
| **Real-time counters** (canlı oylama) | Stale veri yanlış sonuç |
| **In-progress transaction state** | Eventual consistency tehlikeli |
| **Permission revoke check** | Stateful, anlık doğrulama |

### 4.3. Cache key convention

```
<service>:<entity>:<id>[:<variant>]

Örnekler:
  academic:student:{uuid}
  academic:student:{uuid}:full
  identity:user:{uuid}:permissions:{tenantId}
  finance:payment:status:{orderId}     ← YASAK (cache'lenmez)
```

### 4.4. Caching layer abstraction

Servisler doğrudan Redis'e gitmez; `CacheService` adapter'ı kullanır:

```java
public interface CacheService {
    <T> Optional<T> get(String key, Class<T> type);
    <T> void put(String key, T value, Duration ttl);
    void evict(String key);
    void evictPattern(String pattern);
}
```

Bu sayede:
- Redis client değişimi tek noktada
- Serialization stratejisi merkezi (JSON, kompresyon)
- Metric/log otomatik

### 4.5. Spring Cache konfig

```java
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager(
            @Qualifier("cacheRedisConnectionFactory") RedisConnectionFactory cf) {
        RedisCacheConfiguration defaultCfg = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(5))
            .computePrefixWith(name -> "cache:" + name + ":")
            .serializeValuesWith(SerializationPair.fromSerializer(
                new GenericJackson2JsonRedisSerializer()));

        Map<String, RedisCacheConfiguration> perCache = Map.of(
            "students",        defaultCfg.entryTtl(Duration.ofMinutes(5)),
            "user-perms",      defaultCfg.entryTtl(Duration.ofMinutes(10)),
            "tenant-config",   defaultCfg.entryTtl(Duration.ofMinutes(30)),
            "scope-effective", defaultCfg.entryTtl(Duration.ofMinutes(5)),
            "reference",       defaultCfg.entryTtl(Duration.ofHours(24))
        );

        return RedisCacheManager.builder(cf)
            .cacheDefaults(defaultCfg)
            .withInitialCacheConfigurations(perCache)
            .transactionAware()
            .build();
    }
}
```

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

| Alternatif | Tartışma |
|---|---|
| **Cache yok (DB'den her zaman)** | Yavaş, DB yüklü. **Elendi.** |
| **In-memory only (Caffeine)** | Cross-pod paylaşımsız. Hot path için kullanılır ama tek başına yetmez. Lumix Caffeine'i L1, Redis'i L2 olarak kullanabilir (gelecekte). |
| **Read-through (cache loads DB)** | Spring entegrasyonu sınırlı; lazy loading tercih. **Elendi.** |
| **Write-through** | Her write 2× operasyon. Çoğu Lumix yazımı için gereksiz. **Elendi.** |
| **Write-behind** | Veri kaybı riski (cache crash + async kayıp). **Elendi.** |
| **Cache-aside (Lumix)** | ✓ Standart, esnek, explicit. |

### Trade-off'lar

- **Cache miss penalty:** Cache miss olursa istek normal DB latency'sini görür. **Çözüm:** hot key'ler için stampede protection.
- **Stale data:** TTL bitene veya invalidation gelene kadar bayat veri görülebilir. Eventual consistency kabul.
- **Cache ve DB drift:** Yanlış invalidation = drift. **Çözüm:** event-driven invalidation + TTL safety net.
- **Memory cost:** Çok şey cache'lenirse cache-redis büyür. **Çözüm:** TTL + LFU eviction + sadece sık erişilen veriyi cache'le.

## 6. Pratik örnek

### 6.1. Spring `@Cacheable` ile

```java
@Service
@RequiredArgsConstructor
public class StudentService {

    private final StudentRepository repo;

    @Cacheable(cacheNames = "students", key = "#id")
    public StudentDto get(UUID id) {
        return repo.findById(id)
            .map(StudentDto::from)
            .orElseThrow(() -> new NotFoundException("student", id));
    }

    @CachePut(cacheNames = "students", key = "#dto.id")
    public StudentDto update(UpdateStudentCommand cmd) {
        StudentEntity e = repo.findById(cmd.id()).orElseThrow();
        e.apply(cmd);
        return StudentDto.from(repo.save(e));
    }

    @CacheEvict(cacheNames = "students", key = "#id")
    public void delete(UUID id) {
        repo.deleteById(id);
    }
}
```

### 6.2. Manuel CacheService

```java
@Service
@RequiredArgsConstructor
public class CacheServiceImpl implements CacheService {

    @Qualifier("cacheRedisTemplate")
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        String raw = redis.opsForValue().get(key);
        if (raw == null) {
            Metrics.counter("cache.miss", "key", redactedPrefix(key)).increment();
            return Optional.empty();
        }
        Metrics.counter("cache.hit", "key", redactedPrefix(key)).increment();
        try {
            return Optional.of(json.readValue(raw, type));
        } catch (Exception e) {
            redis.delete(key);   // bozuk veri
            return Optional.empty();
        }
    }

    @Override
    public <T> void put(String key, T value, Duration ttl) {
        try {
            redis.opsForValue().set(key, json.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.warn("Cache put failed for key={}", key, e);
        }
    }

    @Override
    public void evict(String key) {
        redis.delete(key);
    }

    @Override
    public void evictPattern(String pattern) {
        ScanOptions opts = ScanOptions.scanOptions().match(pattern).count(100).build();
        try (Cursor<byte[]> cursor = redis.executeWithStickyConnection(c ->
                c.keyCommands().scan(opts))) {
            while (cursor.hasNext()) {
                redis.delete(new String(cursor.next()));
            }
        }
    }

    private String redactedPrefix(String key) {
        int idx = key.indexOf(':');
        return idx > 0 ? key.substring(0, idx) : "unknown";
    }
}
```

### 6.3. Cache + load fallback

```java
@Service
@RequiredArgsConstructor
public class TenantConfigService {

    private final CacheService cache;
    private final TenantConfigRepository repo;

    public TenantConfig get(UUID tenantId) {
        String key = "tenant:config:" + tenantId;
        return cache.get(key, TenantConfig.class)
            .orElseGet(() -> {
                TenantConfig fresh = repo.findById(tenantId).orElseThrow();
                cache.put(key, fresh, Duration.ofMinutes(30));
                return fresh;
            });
    }
}
```

### 6.4. Stampede protection (basit pattern)

```java
public TenantConfig getProtected(UUID tenantId) {
    String key = "tenant:config:" + tenantId;
    Optional<TenantConfig> hit = cache.get(key, TenantConfig.class);
    if (hit.isPresent()) return hit.get();

    // Lock al
    RLock lock = redisson.getLock("lock:" + key);
    try {
        if (lock.tryLock(2, 5, TimeUnit.SECONDS)) {
            try {
                // Double-check (başka pod cache'lemiş olabilir)
                Optional<TenantConfig> secondCheck = cache.get(key, TenantConfig.class);
                if (secondCheck.isPresent()) return secondCheck.get();

                TenantConfig fresh = repo.findById(tenantId).orElseThrow();
                cache.put(key, fresh, Duration.ofMinutes(30));
                return fresh;
            } finally { lock.unlock(); }
        } else {
            // Lock alamadık → kısa bekle ve cache tekrar dene
            Thread.sleep(50);
            return cache.get(key, TenantConfig.class)
                .orElseGet(() -> repo.findById(tenantId).orElseThrow());
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return repo.findById(tenantId).orElseThrow();
    }
}
```

### 6.5. Metric örnekleri

```
lumix_cache_requests_total{cache="students", result="hit"}    52341
lumix_cache_requests_total{cache="students", result="miss"}    3287
lumix_cache_load_seconds_bucket{cache="students", le="0.01"}  3287
lumix_cache_eviction_total{cache="students", reason="ttl"}    1500
lumix_cache_eviction_total{cache="students", reason="event"}   200
```

Hit ratio formülü: `hits / (hits + misses) = 52341 / 55628 ≈ 0.94`. Yüksek.

## 7. Dikkat edilecek tuzaklar

- **Sensitive veriyi cache'lemek.** KVKK kapsamı. **Kural:** PII/PDR cache'lenmez.
- **Negative caching unutmak.** Hiç var olmayan id için DB'ye her seferinde sorulur. **Çözüm:** boş sonuç da kısa TTL ile cache'le (`Optional.empty()` cache'e konabilir veya null marker).
- **Çok büyük value cache'lemek.** Büyük JSON Redis bandwidth ve memory yer. **Kural:** maksimum 64KB; daha büyükleri böl veya cache'leme.
- **TTL'siz cache.** Memory leak. **Kural:** her cache'in TTL'i config'te tanımlı.
- **Cache stampede ignore.** Hot key TTL'i bitince DB diz çöker. **Çözüm:** distributed lock veya jitter.
- **Cache yarı-yazma (write fail).** DB yazıldı, cache yazılamadı → silmek istediğin key hâlâ orada. **Kural:** put yerine invalidate (sil), bir sonraki miss DB'den yükler.
- **`@CacheEvict` unutmak.** Update'te cache evict olmazsa stale veri kalır. **Kural:** her mutating method'da evict (veya event-driven).
- **`SimpleKey` çakışmaları.** Birden çok parametreli method'da `key` SpEL belirtmemek → çakışmalar. **Kural:** explicit `key = "..."`.
- **Cache'i source of truth sanmak.** Cache miss = DB'den oku, eğer DB'de de yoksa yokmuş demektir. Cache'in varlığı/yokluğu hakikati değiştirmez.
- **Auth verisi cache'lemek.** Auth `auth-redis`'tedir; "auth cache" karışıklığı yapma.

## 8. Diğer konularla ilişkisi

- [Redis Temelleri](./redis-fundamentals) — Redis temelleri
- [Redis Sentinel Topology](./redis-sentinel-topology) — cache-redis cluster
- [Cache Invalidation](./cache-invalidation) — event-driven invalidation
- [TTL Strategy](./ttl-strategy) — TTL kararı
- [Distributed Lock — Redisson](./distributed-lock-redisson) — stampede protection
- [Permission Change & Revoke Flow](../04-authentication-authorization/permission-change-revoke-flow) — permission cache invalidation

## 9. Daha derine inmek için

- Microsoft: [Cache-Aside Pattern](https://learn.microsoft.com/en-us/azure/architecture/patterns/cache-aside)
- AWS: "Caching Best Practices"
- Spring: [Cache Abstraction](https://docs.spring.io/spring-framework/reference/integration/cache.html)
- Martin Fowler: "Patterns of Enterprise Application Architecture — Cache"
- Search keywords:
  - `cache aside vs read through write through`
  - `cache stampede thundering herd`
  - `spring cacheable redis configuration`
  - `cache hit ratio metric`
  - `negative caching pattern`

## 10. Sözlük

- **Cache-aside / Lazy loading** — Uygulamanın cache'i kendi yönettiği pattern.
- **Read-through** — Cache library'nin DB'den otomatik yüklediği pattern.
- **Write-through** — Yazımın cache üzerinden DB'ye gittiği pattern.
- **Write-behind / Write-back** — Cache'e yazıp DB'ye async yazma.
- **Hit / Miss** — İstek cache'de bulundu / bulunamadı.
- **Hit ratio** — Cache başarı oranı (hits / total).
- **Stampede / Thundering herd** — TTL bitince paralel istekler DB'ye saldırması.
- **Negative caching** — "Yok" sonucunu da cache'leme.
- **Stale data** — Bayat (güncelliği geçmiş) cache verisi.
- **`@Cacheable`** — Spring'in cache-aside annotation'ı.
- **`@CacheEvict`** — Spring'in cache silme annotation'ı.
- **CacheManager** — Spring'in cache backend abstraction'ı.

