---
title: Distributed Lock — Redisson + RedLock
description: Distributed lock neden gerekli (multi-pod concurrent), Redisson kütüphanesi, RedLock algoritması, Spring entegrasyonu, ödeme attempt'i double-execution engelleme örneği.
sidebar_position: 6
---

## Bu sayfa ne anlatıyor?

Lumix çoklu pod'da çalışıyor; aynı anda **iki farklı pod** aynı işlemi tetikleyebilir (örn. aynı kullanıcının iki tab'ı, aynı anda payment submit). Bu durumlarda **distributed lock** kritik. Bu sayfa lock'un neden gerektiğini, **Redisson** kütüphanesinin nasıl kullanıldığını, **RedLock algoritması**'nın temel mantığını, fence token'ı ve Lumix'in tasarımını anlatır.

## 1. Bu nedir? (Sıfırdan)

**Distributed Lock** = farklı süreçler/pod'lar arasında **mutex** sağlayan mekanizma. "Bir anda sadece bir taraf bu işi yapsın" garantisi.

Tek JVM içinde Java'nın `synchronized` veya `ReentrantLock`'u yeterli. Ama Lumix gibi multi-pod sistemde:
- Pod-1 ve Pod-2 aynı anda aynı işlemi yapabilir
- JVM-içi lock pod'lar arası görünmez
- **Shared state üzerinden lock** lazım — Redis (veya ZooKeeper/etcd)

### Günlük analoji

İki bankada hesabın var. Aynı saniyede iki şubeden 1000 TL çekmeye çalışıyorsun. Banka sistemi merkezi bir "lock"a sahip olmalı — yoksa hesabını 2× çekebilirsin (race condition). Distributed lock = merkezi muhafız.

### Redis ile temel lock (SETNX pattern)

```
SET lockkey value NX EX 30
```

- `NX` = sadece key yoksa set (set if not exists)
- `EX 30` = TTL 30sn
- Başarılı set = lock alındı
- Başarısız set = lock başkasında

Release:
```
DEL lockkey
```

Ama bu **naive** versiyon. Tehlikeler:
- Lock'un sahibi olmayan biri `DEL`leyebilir
- TTL bitti ama iş hâlâ sürüyor → başkası lock alır, çakışma
- Master failover'da lock dublike olabilir

Redisson bu sorunları çözer.

## 2. Hangi problemi çözüyor?

### 2.1. Çift ödeme (double-charge)
Kullanıcı submit butonuna iki kez bastı → iki request paralel → iki kez payment provider'a gitti. Aynı işlem için **idempotency** + **lock**.

### 2.2. Inventory over-sell
"Bu sınıfa son 1 kontenjan kaldı" gösteriliyor; iki veli aynı anda kayıt yaptı → iki "son 1" satıldı.

### 2.3. Scheduled job çoklu pod
Cronjob "her gece 02:00'da rapor üret" → 3 replica = 3× rapor. **Leader election** = lock.

### 2.4. Cache rebuild stampede
"Hot cache miss" durumunda 100 pod aynı anda DB'ye sorgu atar. Lock ile sadece bir tane sorgu atar, diğerleri bekler veya stale döner. Detay: [Cache-Aside Pattern](./cache-aside-pattern).

### 2.5. Distributed initialization
Pod restart sonrası "bootstrap data import" gibi tek seferlik işler. Birden çok pod aynı anda yapmamalı.

## 3. Nasıl çözüyor? (Mekanizma + diyagram)

### 3.1. Naive vs Redisson

```
NAIVE:
  client A: SET lockkey "A" NX EX 30 → OK
            ... (iş yapıyor)
            DEL lockkey
            (ama "A"'nın değerini kontrol etmiyor;
             başkası DEL'leyebilir!)


REDISSON:
  client A: SET lockkey {A-uuid, count=1} NX EX 30 → OK (atomik Lua)
            ... (background "watchdog" her 10sn TTL yeniler)
            unlock: Lua atomic check-and-delete (sadece A-uuid match)
```

Redisson'ın eklediği:
1. **Owner identification:** Lock değeri unique (UUID). Sadece owner unlock edebilir.
2. **Watchdog (auto-renewal):** Lock TTL'i bitmesin diye background thread sürekli EXPIRE.
3. **Reentrant lock:** Aynı thread lock'u tekrar alabilir (counter).
4. **Lua-based atomic operations:** Race-free check-and-delete.
5. **Pub/Sub wait notification:** Lock için bekleyenler busy-poll yerine pub/sub ile uyanır.

### 3.2. RedLock algoritması (multi-master)

Tek master Redis düşerse lock kaybolabilir → güvensiz. RedLock = **çoklu bağımsız Redis instance**'a aynı anda lock atma:

```
- N (örn. 5) bağımsız Redis master'ı var
- Quorum = N/2 + 1 (yani 3)
- Lock al: tüm 5 master'a SETNX dene
  - 3+ instance'ta başarılı + toplam süre TTL'den küçük → ✓ lock
  - Aksi halde geri al ve fail
- Unlock: tüm instance'larda DEL
```

**Lumix bağlamında:** Lumix tek master + replica kullanıyor (Sentinel). RedLock için 5 ayrı master gerekirdi → operasyonel maliyeti yüksek. **Karar:** Redisson'ın default lock'u (tek master, Sentinel-uyumlu) kullanılır. Kritik finans işlemleri ek olarak **DB-level idempotency** (idempotency key + unique constraint) ile garanti altına alınır.

### 3.3. Lock akışı (Redisson)

```
[Pod-1]                    [cache-redis]                  [Pod-2]
   │                             │                            │
   │ tryLock("payment:order-X")  │                            │
   │ ──── Lua atomic SETNX ────► │                            │
   │ ◄──── OK ──────────────────│                            │
   │                             │                            │
   │ (iş yapıyor)                │  tryLock(...)              │
   │                             │ ◄──────────────────────── │
   │                             │  ──── lockkey exists ────► │
   │                             │                            │ (bekle/fail)
   │ Watchdog tick (her 10sn):   │                            │
   │ HEXPIRE TTL yenile          │                            │
   │ ─────────────────────────►  │                            │
   │                             │                            │
   │ unlock                       │                            │
   │ ──── Lua check+DEL ──────►  │                            │
   │ ◄─── OK ───────────────────│                            │
   │                             │  tryLock retry             │
   │                             │ ◄──────────────────────── │
   │                             │ ──── SETNX OK ───────────► │
```

### 3.4. Fence token (versiyon)

Lock'tan sonra çalışan iş **uzun sürerse** ve TTL doldursa (örn. GC pause), başka pod lock alıp aynı işi yapabilir. Korumak için **fence token** (monotonic increasing) ile downstream resource'a sırayla yazılır:

```
1. lock alındı → fence_token = INCR fence:payment:order-X
2. iş yap → DB write: ... WHERE last_fence < fence_token UPDATE ... SET last_fence = fence_token
3. Eski lock holder geç gelirse last_fence > eski token → write reject
```

Lumix idempotency anahtarı + DB constraint zaten bu rolü oynar; ayrıca fence token saklamaya gerek yok.

### 3.5. Lock granularity

Çok geniş lock = throughput kaybı. Çok dar = lock'un anlamı kalmaz. Lumix:
- **Resource-level lock:** `lock:payment:order:{order_id}`
- **User-level lock:** `lock:user:action:{user_id}:{action}` (idempotent submit için)
- **Job-level lock:** `lock:scheduled:report:{job_name}` (leader election)

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Redisson kütüphanesi

- Versiyon: Redisson 3.x
- Backend: **cache-redis** Sentinel cluster
- Default watchdog: 30sn lock TTL, 10sn renewal interval

### 4.2. Spring entegrasyon

```yaml
lumix:
  lock:
    redisson:
      sentinel-master: cache-master
      sentinel-nodes:
        - cache-sentinel-0.redis:26379
        - cache-sentinel-1.redis:26379
        - cache-sentinel-2.redis:26379
      lock-watchdog-timeout-ms: 30000
```

### 4.3. Nerede kullanılır?

| Senaryo | Lock key |
|---|---|
| Payment submit (idempotency üstü extra) | `lock:payment:order:{id}` |
| Enrollment (kontenjan) | `lock:enrollment:class:{id}` |
| Scheduled report job | `lock:scheduled:{job}:{date}` |
| Cache rebuild (stampede protect) | `lock:cache-rebuild:{cache}:{key}` |
| Concurrent session limit enforcement | `lock:user:session-limit:{uid}` |
| Outbox relay (tek pod claim) | `lock:outbox:relay:{topic}` (opsiyonel — election için) |

### 4.4. Default lock policy

- **TTL:** 30sn (watchdog ile yenilenir)
- **Wait time:** 5sn (lock alma denemesi süresi)
- **Lease time:** explicit veya watchdog
- **Fair vs unfair:** Lumix'in çoğu use case'i için **unfair (default)** yeterli; fair lock daha yavaş.

### 4.5. Idempotency key + lock kombinasyonu

```
Client:
  POST /payments
  Header: Idempotency-Key: uuid-v7

Server:
  1. Idempotency-Key DB'de mı? (paymentidempotency tablosu, unique constraint)
     - Var → existing response dön (sonuç hangiyse)
     - Yok → kayıt yarat (INSERT, unique constraint ile race korunmuş)
  2. Lock al: lock:payment:order:{id}
  3. İş yap
  4. Result idempotency satırına yaz
  5. Lock release
  6. Response dön
```

İki katmanlı koruma: **DB idempotency** (kalıcı) + **Lock** (in-progress).

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

| Alternatif | Tartışma |
|---|---|
| **Naive SETNX** | Owner check yok, watchdog yok. **Elendi.** |
| **ZooKeeper / etcd** | Daha güçlü garanti ama ek cluster. Lumix zaten Redis'i var. **Elendi.** |
| **PostgreSQL advisory lock** | DB üzerinden lock; basit ama DB bandwidth eats. Bazı use case'ler için OK ama default Redisson. |
| **Redisson (Lumix)** | ✓ Spring-friendly, watchdog, reentrant, sentinel-aware. |
| **RedLock (5 master)** | Operasyonel karmaşa, Lumix tek Sentinel cluster ile yetiyor + DB idempotency. **Elendi (şimdilik).** |

### Trade-off'lar

- **Single Redis cluster failover:** Sentinel 15-30sn failover. Bu pencerede lock alınamayabilir. **Kabul:** auth flow zaten degrade.
- **Clock drift / GC pause:** TTL'den uzun GC pause = lock loss. **Çözüm:** business invariant + DB unique constraint.
- **Reentrant lock state:** Redis'te counter tutulur. Pod crash sonrası state TTL ile bayatlar.
- **Wait + busy-poll:** Redisson pub/sub ile wait optimize; busy-poll değil. CPU friendly.
- **Lock kullanmamak (lock-free):** Çoğu read path lock'suz. Sadece concurrent state-change path'leri için.

## 6. Pratik örnek

### 6.1. Redisson Spring config

```java
@Configuration
public class RedissonConfig {

    @Value("${lumix.lock.redisson.sentinel-master}")
    private String master;

    @Value("${lumix.lock.redisson.sentinel-nodes}")
    private List<String> sentinels;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redisson() {
        Config cfg = new Config();
        SentinelServersConfig s = cfg.useSentinelServers()
            .setMasterName(master)
            .setPassword(System.getenv("CACHE_REDIS_PASSWORD"));
        sentinels.forEach(s::addSentinelAddress);
        return Redisson.create(cfg);
    }
}
```

### 6.2. Payment service ile lock

```java
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final RedissonClient redisson;
    private final PaymentRepository repo;
    private final IdempotencyRepository idempotencyRepo;
    private final PaymentProviderAdapter provider;

    public PaymentResult submit(PaymentSubmitCommand cmd, UUID idempotencyKey) {
        // 1. Idempotency check (DB-backed)
        Optional<IdempotencyRecord> existing = idempotencyRepo.find(idempotencyKey);
        if (existing.isPresent() && existing.get().completed()) {
            return existing.get().result();
        }

        // 2. Distributed lock
        RLock lock = redisson.getLock("lock:payment:order:" + cmd.orderId());
        try {
            boolean acquired = lock.tryLock(5, 30, TimeUnit.SECONDS);
            if (!acquired) {
                throw new ConflictException("payment_in_progress");
            }
            try {
                return processWithLock(cmd, idempotencyKey);
            } finally {
                if (lock.isHeldByCurrentThread()) lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("interrupted");
        }
    }

    @Transactional
    private PaymentResult processWithLock(PaymentSubmitCommand cmd, UUID idemKey) {
        // Race-safe insert (unique constraint on idempotency_key)
        idempotencyRepo.insertIfAbsent(idemKey, cmd);

        PaymentEntity p = repo.findByOrderId(cmd.orderId())
            .orElseGet(() -> repo.save(PaymentEntity.create(cmd)));

        if (p.status() == AUTHORIZED || p.status() == CAPTURED) {
            return PaymentResult.of(p);   // zaten yapılmış
        }

        // Provider call (timeout, retry adapter'da)
        ProviderResponse resp = provider.authorize(p);
        p.applyProviderResponse(resp);
        repo.save(p);

        idempotencyRepo.markCompleted(idemKey, PaymentResult.of(p));
        return PaymentResult.of(p);
    }
}
```

### 6.3. Scheduled job leader election

```java
@Component
@RequiredArgsConstructor
public class DailyReportJob {

    private final RedissonClient redisson;
    private final ReportService reports;

    @Scheduled(cron = "0 0 2 * * *")
    public void runDaily() {
        String date = LocalDate.now().toString();
        RLock lock = redisson.getLock("lock:scheduled:daily-report:" + date);
        try {
            if (lock.tryLock(0, 10, TimeUnit.MINUTES)) {
                try {
                    reports.generateDailyReport(LocalDate.now());
                } finally {
                    if (lock.isHeldByCurrentThread()) lock.unlock();
                }
            } else {
                log.info("Daily report already running on another pod, skip");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### 6.4. Cache rebuild stampede protection

```java
public TenantConfig getProtected(UUID tenantId) {
    String key = "tenant:config:" + tenantId;
    Optional<TenantConfig> hit = cache.get(key, TenantConfig.class);
    if (hit.isPresent()) return hit.get();

    RLock lock = redisson.getLock("lock:cache-rebuild:tenant-config:" + tenantId);
    boolean acquired = false;
    try {
        acquired = lock.tryLock(2, 5, TimeUnit.SECONDS);
        if (acquired) {
            // Double-check after lock
            Optional<TenantConfig> recheck = cache.get(key, TenantConfig.class);
            if (recheck.isPresent()) return recheck.get();

            TenantConfig fresh = repo.findById(tenantId).orElseThrow();
            cache.put(key, fresh, Duration.ofMinutes(30));
            return fresh;
        } else {
            // Başka pod yenileyene kadar bekle, cache'i tekrar dene
            Thread.sleep(100);
            return cache.get(key, TenantConfig.class)
                .orElseGet(() -> repo.findById(tenantId).orElseThrow());
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return repo.findById(tenantId).orElseThrow();
    } finally {
        if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
    }
}
```

### 6.5. Reentrant lock örneği

```java
RLock lock = redisson.getLock("lock:user:bulk-update:" + userId);
lock.lock();
try {
    inner();          // inner() içinde aynı lock'u tekrar alır
} finally {
    lock.unlock();
}

private void inner() {
    RLock lock = redisson.getLock("lock:user:bulk-update:" + userId);
    lock.lock();      // reentrant — counter artar
    try {
        // ...
    } finally {
        lock.unlock();  // counter düşer; sıfırlanırsa Redis'ten DEL
    }
}
```

### 6.6. Multilock (birden çok kaynağı atomik lock)

```java
RLock l1 = redisson.getLock("lock:user:" + userId);
RLock l2 = redisson.getLock("lock:order:" + orderId);
RLock multi = redisson.getMultiLock(l1, l2);
try {
    if (multi.tryLock(5, 30, TimeUnit.SECONDS)) {
        // user + order birlikte locked
    }
} finally {
    multi.unlock();
}
```

## 7. Dikkat edilecek tuzaklar

- **Naive SETNX kullanmak.** Owner check yok, başkası DEL'ler. **Kural:** Redisson kullan.
- **Lock'u unlock etmemek (try-finally yok).** Exception olursa lock TTL'e kadar yaşar; throughput düşer. **Kural:** her zaman finally.
- **Lock acquired olmasa da unlock çağırmak.** Lock release fail eder veya başkasının lock'unu siler. **Kural:** `isHeldByCurrentThread()` kontrolü.
- **Çok uzun lease.** İş 1 saat sürerse lock 1 saat var olur; pod crash'lerinde lock orphan kalır. **Çözüm:** watchdog default + makul lease.
- **Çok kısa lease.** İş bitmeden TTL doluyor; başka pod lock alıyor. **Çözüm:** watchdog (auto-renewal).
- **Lock granularity çok geniş.** `lock:global` herşeyi serileştirir. **Kural:** resource-level lock.
- **Lock + DB transaction birleşik race.** Lock release DB commit'ten önce → lock'un dışında race. **Kural:** unlock commit sonrası.
- **`lock.lock()` (block) production'da.** Pod hang olursa thread sonsuza bloklanır. **Kural:** `tryLock(waitTime, leaseTime, unit)`.
- **RedLock'u zorlamak (5 master).** Lumix'te tek Sentinel cluster yeterli, RedLock'un operasyonel yükü gereksiz; DB idempotency safety zaten var.
- **Lock'u idempotency yerine geçirmek.** Lock TTL bitince corruption mümkün. **Kural:** Lock + DB unique constraint (defense-in-depth).
- **GC pause + uzun iş.** Lock loss riski. **Çözüm:** business invariant kontrol (DB constraint).
- **Pub/Sub wait kullanmamak (busy-poll lock).** Redisson zaten pub/sub kullanır; `tryLock` ile direkt fail-fast da seçenek.

## 8. Diğer konularla ilişkisi

- [Redis Temelleri](./redis-fundamentals) — Redis lock komutları
- [Redis Sentinel Topology](./redis-sentinel-topology) — cache-redis cluster
- [Cache-Aside Pattern](./cache-aside-pattern) — stampede protection
- [TTL Strategy](./ttl-strategy) — lock TTL kritik
- [Session & Device Lifecycle](../04-authentication-authorization/session-device-lifecycle) — concurrent session limit lock
- [Outbox Pattern](../event-driven-architecture) — outbox relay leader election
- Idempotency (Payment doc) — DB-level unique constraint

## 9. Daha derine inmek için

- Redis: [Distributed locks with Redis](https://redis.io/docs/manual/patterns/distributed-locks/)
- Redisson: [Distributed Locks and Synchronizers Wiki](https://github.com/redisson/redisson/wiki/8.-Distributed-locks-and-synchronizers)
- Martin Kleppmann: ["How to do distributed locking"](https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html) (RedLock eleştirisi)
- Antirez (Redis creator): "Is Redlock safe?"
- Spring + Redisson: [Spring Boot Starter](https://github.com/redisson/redisson/tree/master/redisson-spring-boot-starter)
- Search keywords:
  - `redisson distributed lock pattern`
  - `redlock vs single redis lock`
  - `fence token distributed locking`
  - `idempotency key vs lock`
  - `cache stampede prevention`

## 10. Sözlük

- **Distributed Lock** — Birden çok süreç/pod arasında mutex sağlayan mekanizma.
- **Mutex** — Mutual Exclusion; aynı anda tek erişim.
- **Owner identification** — Lock'un kim tarafından alındığını saklayan unique değer.
- **Watchdog** — Lock TTL'ini iş bitene kadar yenileyen background thread.
- **Reentrant lock** — Aynı thread'in lock'u tekrar alabilmesi (counter ile).
- **RedLock** — Birden çok bağımsız Redis master üzerinden quorum-based lock.
- **Fence token** — Monotonic increasing değer; eski lock holder'ı tespit etme.
- **Idempotency key** — Aynı işlemi tek seferlik kılan client-supplied tanımlayıcı.
- **Lease time** — Lock'un kendiliğinden serbest kalacağı süre.
- **Wait time** — Lock için bekleyecek maksimum süre.
- **Leader election** — Çoklu pod arasında "tek lider" seçimi (lock = leader).
- **Stampede** — Aynı anda çoklu istek aynı kaynağı bekliyor; lock ile serileştirilir.

