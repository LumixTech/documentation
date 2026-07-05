---
title: TTL Stratejisi
description: TTL nedir, eventual consistency kararı, granular TTL (her cache farklı süre), TTL vs manual eviction trade-off, payment/auth gibi alanlarda TTL neden tehlikeli.
sidebar_position: 5
---

## Bu sayfa ne anlatıyor?

Cache key'lerinin **kaç saniye yaşayacağı** önemsiz görünür ama yanlış TTL = sessiz bug kaynağı. Bu sayfa TTL'in ne işe yaradığını, **her cache için ayrı TTL** felsefesini, Lumix'in granular TTL matrisini, TTL'in **payment/auth gibi alanlarda neden tehlikeli** olduğunu ve manual eviction ile TTL'in nasıl birlikte çalıştığını anlatır.

## 1. Bu nedir? (Sıfırdan)

**TTL (Time To Live)** = bir cache key'inin **otomatik silinmeden önce yaşayacağı süre**.

```
SET key value EX 60     → 60 saniye sonra silinir
EXPIRE key 60           → mevcut key'e 60sn TTL
TTL key                 → kalan süre
```

TTL'in iki rolü:
1. **Memory yönetimi** — sonsuz büyümeyen cache.
2. **Eventual consistency garantisi** — invalidation bug'lansa bile en kötü N süre sonra düzelir.

### Günlük analoji

Buzdolabında etiket var: "Açıldı 14 Mayıs, 5 gün sonra at." 5 gün sonra otomatik olarak çöpe atılır (TTL). Buzdolabı dolmuyor, hep taze tutulur. Ama 5 günlük et = ortalama tazelik (yarısı 2 gün, yarısı 4-5 gün).

### Granular TTL ne demek?

"**Her cache aynı TTL'i kullanmasın**". Permission cache 10dk lazım, reference data 24h. Bu yüzden Lumix'te:

```yaml
caches:
  students:        5m
  user-perms:     10m
  tenant-config:  30m
  reference:       1d
  stats:           1m
```

## 2. Hangi problemi çözüyor?

### 2.1. Stale data window kontrolü
Veri ne kadar bayat olabilir? TTL bu pencere'yi belirler.

### 2.2. Memory leak engelleme
Cache key'ler manuel silinmezse memory dolar. TTL self-cleaning.

### 2.3. Failure isolation
Invalidation event'i kaybolsa bile TTL doldurur. **Defense-in-depth**.

### 2.4. Burst traffic adaptation
Reference data'yı saatlerce cache'le, ama dashboard count'ları dakikalık tut. Granular TTL bu fine-tune'u verir.

### 2.5. Cache stampede frekansı
Çok kısa TTL = sık miss = stampede riski. Çok uzun TTL = stale veri. Doğru süre + jitter = denge.

## 3. Nasıl çözüyor? (Mekanizma + diyagram)

### 3.1. TTL lifecycle

```
SET key val EX 60
       │
       ▼
   t=0   key var, TTL=60
   t=10  GET → değer + TTL=50
   t=30  GET → değer + TTL=30
   t=59  GET → değer + TTL=1
   t=60  GET → null (silinmiş)
```

### 3.2. Active vs lazy expiration

Redis TTL'i iki yolla uygular:
- **Lazy:** GET sırasında "TTL geçti mi?" kontrolü; geçtiyse sil.
- **Active:** Background job periyodik sample alarak süresi dolan'ları siler.

Sonuç: süresi dolmuş key bir süre RAM'de durabilir ama erişim anında silinir.

### 3.3. TTL + manual eviction (Lumix kombinasyonu)

```
                    Cache key yaratıldı (SETEX)
                              │
                              ▼
        ┌──────────────────────────────────────────────────┐
        │            Aktif yaşam süresi (TTL)              │
        │                                                  │
        │  Birinci geçerse:                                │
        │   - explicit invalidation: @CacheEvict          │
        │   - cross-service event consumer DEL            │
        │   - version bump (versioned key)                 │
        │                                                  │
        │  İkincisi geçerse:                               │
        │   - TTL dolar, otomatik silinir                 │
        │                                                  │
        └──────────────────────────────────────────────────┘
                              │
                              ▼
                       Key Redis'ten gitti
```

İlk hangisi olursa olsun cache temizlenir. TTL = **safety net**, manual = **anında tutarlılık**.

### 3.4. Granular TTL matrisi (Lumix)

```
┌──────────────────────────────────┬─────────┬────────────────────────────┐
│ Cache                            │ TTL     │ Karar gerekçesi            │
├──────────────────────────────────┼─────────┼────────────────────────────┤
│ user:permissions:{uid}:{tid}     │ 10 dk   │ Permission değişimi nadir, │
│                                  │         │ event-driven invalidation  │
│                                  │         │ var, 10dk safety net.      │
├──────────────────────────────────┼─────────┼────────────────────────────┤
│ scope:effective:{uid}:{tid}      │ 5 dk    │ Scope sık değişebilir,     │
│                                  │         │ kısa TTL.                  │
├──────────────────────────────────┼─────────┼────────────────────────────┤
│ tenant:config:{id}               │ 30 dk   │ Çok seyrek değişir.        │
├──────────────────────────────────┼─────────┼────────────────────────────┤
│ student:{id} (entity cache)      │ 5 dk    │ Read-heavy + invalidation  │
├──────────────────────────────────┼─────────┼────────────────────────────┤
│ students:list:tenant:{tid}:...   │ 5 dk    │ Versioned key + TTL        │
├──────────────────────────────────┼─────────┼────────────────────────────┤
│ stats:tenant:{id}:students       │ 1 dk    │ Dashboard, ufak gecikme OK │
├──────────────────────────────────┼─────────┼────────────────────────────┤
│ reference:timezones              │ 24 saat │ Değişmez                   │
├──────────────────────────────────┼─────────┼────────────────────────────┤
│ jwks                             │ 5-10 dk │ Key rotation safety        │
├──────────────────────────────────┼─────────┼────────────────────────────┤
│ rate:limit:{ip}:{ep}             │ 1 dk    │ Sliding window'a göre     │
├──────────────────────────────────┼─────────┼────────────────────────────┤
│ lock:{name}                      │ 10-30s  │ Distributed lock          │
└──────────────────────────────────┴─────────┴────────────────────────────┘
```

### 3.5. TTL nerede tehlikelidir?

| Domain | TTL tehlikesi |
|---|---|
| **Payment status** | Cache 5dk → çift charge riski |
| **Auth token revoke check** | Cache 10sn → token revoke'tan sonra hâlâ kabul |
| **Stock/inventory count** | Over-sell |
| **Real-time scoreboard** | Yanlış sıralama |
| **Permission deny** | Yetkisi çekilmiş kullanıcı erişmeye devam eder |

**Lumix kuralı:**
- Payment, transaction, auth-token-status → **TTL YOK, cache YOK**. Doğrudan DB/Redis state.
- Permission deny path → event-driven invalidation şart, TTL safety net olarak kısa (10dk max).

### 3.6. TTL jitter (stampede engelleme)

Aynı anda 1000 key TTL'i biterse → cache stampede. Çözüm: TTL'e küçük rastgele varyans ekle.

```
ttl = base + random(0, base * 0.1)

Örnek: base=300sn → ttl ∈ [300, 330]
```

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. TTL config (Spring)

```java
Map<String, RedisCacheConfiguration> perCache = Map.of(
    "students",        defaultCfg.entryTtl(Duration.ofMinutes(5)),
    "user-perms",      defaultCfg.entryTtl(Duration.ofMinutes(10)),
    "tenant-config",   defaultCfg.entryTtl(Duration.ofMinutes(30)),
    "scope-effective", defaultCfg.entryTtl(Duration.ofMinutes(5)),
    "reference",       defaultCfg.entryTtl(Duration.ofHours(24)),
    "stats",           defaultCfg.entryTtl(Duration.ofMinutes(1)),
    "jwks",            defaultCfg.entryTtl(Duration.ofMinutes(10))
);
```

### 4.2. Jitter helper

```java
public static Duration withJitter(Duration base, double percentage) {
    long baseMs = base.toMillis();
    long jitter = (long) (Math.random() * baseMs * percentage);
    return Duration.ofMillis(baseMs + jitter);
}

// Kullanım:
cache.put(key, value, withJitter(Duration.ofMinutes(5), 0.1));
```

### 4.3. TTL audit

Hangi cache'ler default 5dk'yı kullanıyor, hangileri override? Build-time check:

```java
// ArchUnit rule (CI gate)
classes().that().areAnnotatedWith(Cacheable.class)
    .should().beDeclaredIn(serviceLayer())
    .andShould(haveExplicitCacheNames());
```

Cache config dosyası audit edilir; her cache açıkça TTL'li olmalı.

### 4.4. Cache yok (TTL yok) — explicit list

Bu listede olanlar cache'lenmez:

```
- finance.payment.status
- finance.payment.refund
- identity.access.token.status      (auth-redis'te, TTL var ama "cache" değil — state)
- identity.session.status           (aynı)
- realtime.event.ack
- audit.log.entry
```

### 4.5. TTL monitoring

Prometheus metric'leri:
- `lumix_cache_ttl_seconds{cache="students"}` (cache yaratma anında)
- `lumix_cache_eviction_total{cache, reason="ttl"|"event"}` 
- Hit/miss ratio + TTL'in optimal olduğunu doğrular

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

| Alternatif | Tartışma |
|---|---|
| **Tek global TTL (örn. herşey 5dk)** | Reference data 5dk = boşa miss; permission 5dk = bağlam stale risk. **Elendi.** |
| **TTL yok (cache sonsuz)** | Memory leak + drift. **Elendi.** |
| **Granular TTL + jitter (Lumix)** | ✓ Profil-uygun. |
| **TTL yerine sadece manuel evict** | Bug'lı invalidation = sonsuz stale. Safety net şart. **Elendi.** |
| **TTL + manuel evict (Lumix)** | ✓ Defense-in-depth. |
| **Sliding TTL (her erişimde TTL reset)** | Pratik bazı senaryolarda iyi (session-like) ama cache için "popular = sonsuz" demek; tehlikeli. **Cache için kullanılmaz.** |

### Trade-off'lar

- **Kısa TTL → daha sık miss → daha çok DB yükü.** Denge: cache'in faydası kalmazsa boş kullanma.
- **Uzun TTL → bayat veri pencere büyük.** Event-driven invalidation safety olarak şart.
- **Jitter ek random:** Cache stampede engeli için cüzi maliyet, kabul.
- **TTL bilmemenin maliyeti:** Geliştirici "default 5dk" yazarsa kötü karar alabilir. **Çözüm:** her cache config dosyada explicit.

## 6. Pratik örnek

### 6.1. application.yml ile TTL config

```yaml
lumix:
  cache:
    default-ttl: PT5M
    per-cache:
      students:        PT5M
      user-perms:     PT10M
      tenant-config:  PT30M
      scope-effective: PT5M
      reference:       P1D
      stats:           PT1M
      jwks:           PT10M
    jitter-percentage: 0.10
```

### 6.2. TtlAwareCacheService

```java
@Service
@RequiredArgsConstructor
public class TtlAwareCacheService implements CacheService {

    private final StringRedisTemplate redis;
    private final CachePropertiesConfig cfg;
    private final ObjectMapper json;

    @Override
    public <T> void put(String cacheName, String key, T value) {
        Duration ttl = cfg.ttlFor(cacheName);
        Duration withJitter = applyJitter(ttl, cfg.jitterPercentage());
        redis.opsForValue().set(redisKey(cacheName, key),
                                  json.writeValueAsString(value),
                                  withJitter);
    }

    private Duration applyJitter(Duration base, double pct) {
        long ms = base.toMillis();
        long j = (long) (ThreadLocalRandom.current().nextDouble() * ms * pct);
        return Duration.ofMillis(ms + j);
    }

    private String redisKey(String cache, String key) {
        return "cache:" + cache + ":" + key;
    }
}
```

### 6.3. CachePropertiesConfig

```java
@Configuration
@ConfigurationProperties(prefix = "lumix.cache")
@Data
public class CachePropertiesConfig {

    private Duration defaultTtl = Duration.ofMinutes(5);
    private Map<String, Duration> perCache = new HashMap<>();
    private double jitterPercentage = 0.10;

    public Duration ttlFor(String cacheName) {
        return perCache.getOrDefault(cacheName, defaultTtl);
    }
}
```

### 6.4. Cache yok için kontrat

```java
/**
 * MARKER: Bu method asla cache'lenmemelidir.
 * Build-time check ile @Cacheable yasaklanır.
 */
@NotCacheable("Payment state must always read DB; cache risks double charge")
public PaymentStatus getStatus(UUID orderId) {
    return paymentRepo.findStatusForUpdate(orderId);
}
```

CI gate (ArchUnit):
```java
methods().that().areAnnotatedWith(NotCacheable.class)
    .should().notBeAnnotatedWith(Cacheable.class);
```

### 6.5. TTL audit metric

```java
@Component
@RequiredArgsConstructor
public class CacheMetricsExporter {

    private final CachePropertiesConfig cfg;
    private final MeterRegistry meter;

    @PostConstruct
    public void exportConfiguredTtls() {
        cfg.getPerCache().forEach((name, ttl) ->
            Gauge.builder("lumix_cache_configured_ttl_seconds",
                () -> (double) ttl.toSeconds())
                .tag("cache", name)
                .register(meter));
    }
}
```

### 6.6. Hit-ratio ile TTL feedback loop

Dashboard alarmı:
```
ALERT CacheLowHitRatio
  IF (sum(cache_hits) / sum(cache_hits + cache_misses)) < 0.7
  FOR 10m
```

Hit ratio düşükse:
- TTL çok kısa olabilir → uzat
- Cache key'i çok granular olabilir → grupla
- Cache namespace'i çok büyük olabilir → split

## 7. Dikkat edilecek tuzaklar

- **TTL'siz `SET` komutu.** Memory leak başlangıcı. **Kural:** her zaman SETEX veya EXPIRE.
- **Payment/auth/critical state'i cache + TTL ile çözmeye çalışmak.** TTL = tutarsızlık penceresi. **Kural:** kritik veri için cache YOK; auth-redis state'i için bile TTL'i "ekstra savunma" olarak gör, ana karar değil.
- **Sliding TTL cache'lerde.** Popular key sonsuz yaşar = stale ebediyen. **Kural:** cache'te sliding TTL yok (session'larda OK).
- **Jitter yok.** 1000 key aynı anda expire → DB stampede. **Çözüm:** %10 jitter.
- **TTL'i yüksek tutup invalidation event'e güvenmek.** Event kaybolursa stale günlerce. **Kural:** event + TTL safety net birlikte.
- **TTL'i metric ile izlemediğin.** Hit ratio düşer, kimse görmez. **Çözüm:** Prometheus dashboard.
- **TTL'i kod'da hardcode etmek.** Config dosyadan oku, deploy etmeden ayarlanabilsin.
- **Her cache aynı TTL'i kullanmak.** Granularity = doğru oran. **Kural:** explicit per-cache TTL.
- **TTL'i çok kısa (örn. 1sn).** Sürekli miss + DB stampede. Cache faydası yok. **Kural:** minimum 60sn (low-frequency veri için).
- **`PERSIST` ile TTL kaldırmak.** Yanlışlıkla key'i kalıcı yapar. **Kural:** PERSIST production'da yasak.

## 8. Diğer konularla ilişkisi

- [Cache-Aside Pattern](./03-cache-aside-pattern.md) — TTL bu pattern'in temel parçası
- [Cache Invalidation](./04-cache-invalidation.md) — TTL + manuel invalidation
- [Distributed Lock — Redisson](./06-distributed-lock-redisson.md) — lock TTL kritik (deadlock engeli)
- [Session & Device Lifecycle](../04-authentication-authorization/02-session-device-lifecycle.md) — sliding TTL session'larda OK
- [Redis Temelleri](./01-redis-fundamentals.md) — TTL komutları

## 9. Daha derine inmek için

- Redis: [Expire — Time to Live](https://redis.io/commands/expire/)
- Microsoft: "Cache TTL strategies"
- AWS Architecture: "Caching with Amazon ElastiCache best practices"
- Search keywords:
  - `cache ttl strategy`
  - `cache stampede jitter`
  - `redis expire active vs passive`
  - `eventual consistency cache window`
  - `sliding ttl vs fixed ttl`

## 10. Sözlük

- **TTL (Time To Live)** — Key'in otomatik silineceği süre.
- **Active expiration** — Redis'in background'da süresi dolan key'leri silmesi.
- **Lazy expiration** — Erişim sırasında "süresi geçmiş mi?" kontrolü.
- **Granular TTL** — Her cache namespace'i için ayrı TTL.
- **Jitter** — TTL'e eklenen küçük rastgele varyans (stampede engeli).
- **Sliding TTL** — Her erişimde TTL'in resetlenmesi (session pattern).
- **Stale window** — Veri değiştikten sonra cache'in bayat kaldığı süre.
- **Safety net** — Diğer mekanizma fail ederse devreye giren yedek (TTL bu rolü oynar).
- **Stampede** — TTL bitince paralel istemcilerin DB'ye saldırması.
- **Hit ratio** — Cache başarı oranı; TTL doğruluğunun göstergesi.

