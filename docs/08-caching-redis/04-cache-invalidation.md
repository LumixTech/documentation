---
title: Cache Invalidation
description: Entity cache vs view/list cache, hangi event'te ne invalidate, Spring @CacheEvict, Kafka-driven invalidation, view-level invalidation tasarımı.
sidebar_position: 4
---

## Bu sayfa ne anlatıyor?

"Cache invalidation" diye bir cümle ünlüdür: *"Bilgisayar biliminde sadece iki zor şey vardır: cache invalidation, naming things, ve off-by-one hataları."* — Phil Karlton.

Lumix bu zoru nasıl yönetiyor? **Entity cache** (`student:{id}`) ve **view/list cache** (`students:list:tenant:{tid}`) farklı politikalar gerektirir. Hangi event'te neyi nasıl silmeliyiz? Spring `@CacheEvict` + Kafka-driven invalidation kombinasyonu nasıl çalışır? Bu sayfa Lumix'in invalidation tasarımını tek bütün halinde anlatır.

## 1. Bu nedir? (Sıfırdan)

Cache invalidation = **"cache'deki bir veriyi geçersiz kılma"**. İki sebep var:
1. **Veri değişti.** DB güncellendi; cache'deki kopya artık yanlış.
2. **Veri eskidi.** TTL doldu (otomatik).

Lumix invalidation'ı **iki yolla** yapar:
- **Explicit:** Yazma operasyonu sırasında cache key'i sil (`@CacheEvict`).
- **Event-driven:** Kafka event consume edilince cache key'i sil.

Ek olarak **TTL** safety net: invalidation bug'lansa bile TTL süresi sonunda veri bayatlıktan kurtulur.

### Entity cache vs view cache

| Tip | Örnek | Invalidation zorluğu |
|---|---|---|
| **Entity cache** | `student:{id}` | Kolay — id biliniyor |
| **View/list cache** | `students:list:tenant:{tid}:page:1` | Zor — bir öğrenci eklenince hangi liste etkilendi? |

Lumix kuralı: **list cache** koymak zorundaysan **toplu invalidate** (pattern delete) hazır olsun veya hiç koyma.

### Günlük analoji

Buzdolabında "10 yumurta var" notu (cache). Sen 3 yumurta kullandın. İki seçenek:
- **Notu silmek (invalidation):** "Tekrar saydığımda göreceğim."
- **Notu güncellemek (cache update):** "7 yumurta var" yaz.

Lumix invalidation tercih eder: cache update yanılma kaynağı (yanlış değer yazılırsa kalır).

## 2. Hangi problemi çözüyor?

### 2.1. Stale data (bayat veri)
DB güncellendi ama cache silinmedi → kullanıcı eski veriyi görüyor. Görünür bug.

### 2.2. Cross-service eventual consistency
academic-service öğrenci güncelledi; finance-service'in elindeki cache de bayat. Cross-service invalidation lazım.

### 2.3. List cache karmaşıklığı
"Tüm 11-A öğrencileri" cache'i var. Yeni öğrenci eklendi → bu list de bayat. Sadece entity invalidation yetmiyor.

### 2.4. Drift
Cache ve DB arasında uzun süreli tutarsızlık. TTL ile en kötü dakikalar içinde düzelse de explicit invalidation daha hızlı.

### 2.5. Permission/scope güvenlik kritik invalidation
Permission değişimi cache'de 10dk daha eskiyi yansıtırsa **güvenlik açığı**. Anlık invalidation şart. Detay: [Permission Change & Revoke Flow](../04-authentication-authorization/06-permission-change-revoke-flow.md).

## 3. Nasıl çözüyor? (Mekanizma + diyagram)

### 3.1. Üç katmanlı invalidation

```
┌───────────────────────────────────────────────────────────────┐
│                  LUMIX INVALIDATION KATMANLARI                │
└───────────────────────────────────────────────────────────────┘

  Katman 1: TTL (safety net)
  ────────────────────────────
  Her cache key'in TTL'i var. En kötü ihtimalde N dakikada self-heal.

  Katman 2: Explicit (same-service)
  ────────────────────────────
  Yazma operasyonu cache evict tetikler.
  Spring @CacheEvict veya manual cache.evict().

  Katman 3: Event-driven (cross-service)
  ────────────────────────────
  Outbox → Kafka → consumer → cache evict.
  Her servis kendi local cache'ini invalidate eder.
```

### 3.2. Explicit invalidation (same-service)

```
PUT /students/{id} →
   Controller →
     Service.update() {
       BEGIN TX
         StudentEntity e = repo.findById(id);
         e.apply(cmd);
         repo.save(e);
         outbox.publish(StudentUpdated event);
       COMMIT
       // Spring AOP @CacheEvict çalışır:
       cache.evict("students", id);
     } @CacheEvict(cacheNames="students", key="#id")
```

### 3.3. Event-driven invalidation (cross-service)

```
academic-service:           Kafka topic                  finance-service:
[STUDENT_UPDATED]    →    academic.student.v1   →     consumer:
                                                       cache.evict("student-summary:" + id)
                                                       cache.evict("student-payments:" + id)
                          ↓
                          notification-service:
                          consumer:
                          cache.evict("user-notification-prefs:" + id)
```

Her servis kendi local cache anahtarlarını bilir; event'ten gerekli key'i hesaplar.

### 3.4. View/list invalidation

İki yaklaşım:

**a) Pattern delete (basit ama dikkatli)**

```
students:list:tenant:{tid}:*  → SCAN + DEL
```

Tehlikeli: çok key olursa yavaş. Lumix bu yüzden **list cache'lerini sınırlı tutar**.

**b) Versioning (versioned cache key)**

```
Key: students:list:tenant:{tid}:v{version}:page:1
Version: "version:students:tenant:{tid}" key'inde tutulur (INCR ile bump)

Invalidation = sadece INCR.
Eski version key'ler doğal olarak TTL ile ölür.
```

Lumix view/list cache'lerinde **versioning** kullanır.

### 3.5. Permission/auth invalidation flow

```
Permission değişti →
   Outbox event →
     Kafka topic: identity.user.permission.v1 →
        Tüm servisler:
        cache.evict("user:permissions:{uid}:{tid}")
        cache.evict("scope:effective:{uid}:{tid}")  (varsa)

        identity-service ek:
        - Token revoke (auth-redis)
        - WebSocket force-logout
```

Detay: [Permission Change & Revoke Flow](../04-authentication-authorization/06-permission-change-revoke-flow.md).

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Invalidation policy matrix

| Cache | Invalidation |
|---|---|
| `students:{id}` | Same-service `@CacheEvict` on update/delete; event ile cross-service |
| `tenant:config:{id}` | Event-driven (`tenant.config.changed.v1`) |
| `user:permissions:{uid}:{tid}` | Event-driven (`identity.user.permission.v1`) |
| `scope:effective:{uid}:{tid}` | Event-driven (`identity.scope.changed.v1`) |
| `students:list:tenant:{tid}:v{N}:...` | Version bump (INCR) on any student CRUD |
| `stats:tenant:{id}:students` | TTL only (1dk) — eventual consistency |
| `reference:timezones` | TTL only (24h) — değişmez |
| `jwks` | Event-driven (`identity.key.rotated.v1`) + TTL safety net |

### 4.2. Kafka topic naming

```
{service}.{aggregate}.{action}.v{version}

Örnek:
  academic.student.created.v1
  academic.student.updated.v1
  academic.student.deleted.v1
  identity.user.permission.changed.v1
```

### 4.3. Consumer group per service

Her servis kendi invalidator consumer group'una sahip:
- `academic-cache-invalidator`
- `finance-cache-invalidator`
- `notification-cache-invalidator`

Kafka at-least-once → invalidation idempotent (DEL bir kere veya bir kere daha = no-op).

### 4.4. CacheInvalidator service

```java
public interface CacheInvalidator {
    void evictEntity(String type, UUID id);
    void evictList(String type, UUID tenantId);   // version bump
    void evictPattern(String pattern);             // dikkat
}
```

Servisler doğrudan `cache.delete` çağırmaz; bu abstraction ile metric/log otomatik olur.

### 4.5. Versioned key support

```java
@Service
@RequiredArgsConstructor
public class VersionedKeyService {

    @Qualifier("cacheRedisTemplate")
    private final StringRedisTemplate redis;

    public long currentVersion(String namespace) {
        String v = redis.opsForValue().get("version:" + namespace);
        return v == null ? 1L : Long.parseLong(v);
    }

    public long bump(String namespace) {
        return redis.opsForValue().increment("version:" + namespace);
    }

    public String key(String namespace, String suffix) {
        long v = currentVersion(namespace);
        return namespace + ":v" + v + ":" + suffix;
    }
}
```

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

| Alternatif | Tartışma |
|---|---|
| **Sadece TTL** | Stale window kötü (auth için kabul edilemez). **Elendi.** |
| **Sadece explicit @CacheEvict** | Cross-service görünmez. **Elendi.** |
| **Sadece event-driven** | Same-service yazımda 1-2sn gecikme; explicit hızlı. Birlikte daha iyi. |
| **TTL + explicit + event-driven (Lumix)** | ✓ Defense-in-depth. |
| **Pattern delete (SCAN+DEL)** | Büyük key set'lerde yavaş; race condition var. Versioning daha iyi. |
| **Versioning** | ✓ Lumix list/view cache'lerinde standart. |

### Trade-off'lar

- **Eventual consistency:** Cross-service event 100-500ms gecikebilir. Auth gibi kritik için ekstra adım (token revoke + WS push).
- **Idempotency cost:** Aynı event 3 kez gelirse 3 kez DEL → no-op. Önemsiz maliyet.
- **Schema dependency:** İnvalidator consumer event schema'sına bağımlı. Backward-compatible schema evolution şart (Apicurio + Protobuf).
- **Version key sonsuz büyüyebilir:** INCR sürekli artar; counter pratikte sorun değil (long).
- **Eski versioned key'ler TTL ile ölür ama bir süre extra memory yer.** Kabul.

## 6. Pratik örnek

### 6.1. Spring `@CacheEvict`

```java
@Service
@RequiredArgsConstructor
public class StudentService {

    private final StudentRepository repo;
    private final OutboxPublisher outbox;
    private final VersionedKeyService versions;

    @Transactional
    @CacheEvict(cacheNames = "students", key = "#cmd.id")
    public StudentDto update(UpdateStudentCommand cmd) {
        StudentEntity e = repo.findById(cmd.id()).orElseThrow();
        e.apply(cmd);
        repo.save(e);

        outbox.publish("academic.student.updated.v1",
            Map.of("student_id", e.getId(), "tenant_id", e.getTenantId()));

        // List version bump (aynı tenant)
        versions.bump("students:list:tenant:" + e.getTenantId());

        return StudentDto.from(e);
    }

    @Transactional
    @CacheEvict(cacheNames = "students", key = "#id")
    public void delete(UUID id) {
        StudentEntity e = repo.findById(id).orElseThrow();
        repo.delete(e);
        outbox.publish("academic.student.deleted.v1",
            Map.of("student_id", id, "tenant_id", e.getTenantId()));
        versions.bump("students:list:tenant:" + e.getTenantId());
    }
}
```

### 6.2. Cross-service Kafka invalidator

```java
@Component
@RequiredArgsConstructor
public class StudentCacheInvalidator {

    private final CacheInvalidator invalidator;

    @KafkaListener(
        topics = {
            "academic.student.updated.v1",
            "academic.student.deleted.v1"
        },
        groupId = "finance-cache-invalidator"
    )
    public void on(StudentChangedEvent ev) {
        UUID studentId = ev.studentId();
        UUID tenantId = ev.tenantId();

        // Finance-specific cache key'ler
        invalidator.evictEntity("student-summary", studentId);
        invalidator.evictEntity("student-payments-meta", studentId);
        invalidator.evictList("payments:by-student", studentId);
    }
}
```

### 6.3. List cache with versioning

```java
@Service
@RequiredArgsConstructor
public class StudentListService {

    private final CacheService cache;
    private final StudentRepository repo;
    private final VersionedKeyService versions;
    private final ObjectMapper json;

    public Page<StudentDto> list(UUID tenantId, Pageable pageable) {
        String namespace = "students:list:tenant:" + tenantId;
        String key = versions.key(namespace,
            "page:" + pageable.getPageNumber() + ":size:" + pageable.getPageSize());

        Optional<Page<StudentDto>> hit = cache.get(key, new TypeReference<>() {});
        if (hit.isPresent()) return hit.get();

        Page<StudentDto> fresh = repo.findAllByTenantId(tenantId, pageable)
            .map(StudentDto::from);
        cache.put(key, fresh, Duration.ofMinutes(5));
        return fresh;
    }
}
```

### 6.4. CacheInvalidator implementation

```java
@Service
@RequiredArgsConstructor
public class CacheInvalidatorImpl implements CacheInvalidator {

    private final CacheService cache;
    private final VersionedKeyService versions;

    @Override
    public void evictEntity(String type, UUID id) {
        cache.evict("cache:" + type + ":" + id);
        Metrics.counter("cache.invalidation",
            "type", type, "scope", "entity").increment();
    }

    @Override
    public void evictList(String type, UUID tenantId) {
        versions.bump(type + ":tenant:" + tenantId);
        Metrics.counter("cache.invalidation",
            "type", type, "scope", "list").increment();
    }

    @Override
    public void evictPattern(String pattern) {
        cache.evictPattern(pattern);
        Metrics.counter("cache.invalidation",
            "scope", "pattern").increment();
    }
}
```

### 6.5. Permission cache invalidator (cross-service)

```java
@Component
@RequiredArgsConstructor
public class PermissionCacheInvalidator {

    private final CacheService cache;

    @KafkaListener(
        topics = "identity.user.permission.v1",
        groupId = "${spring.application.name}-perm-inval")
    public void on(PermissionChangedEvent ev) {
        cache.evict("cache:user-perms:" + ev.userId() + ":" + ev.tenantId());
        cache.evict("cache:scope-effective:" + ev.userId() + ":" + ev.tenantId());
    }
}
```

### 6.6. Idempotency log (opsiyonel)

```java
@Component
public class IdempotentInvalidator {

    private final StringRedisTemplate redis;

    public boolean shouldProcess(UUID eventId) {
        String key = "processed:event:" + eventId;
        Boolean wasSet = redis.opsForValue().setIfAbsent(key, "1", Duration.ofHours(1));
        return Boolean.TRUE.equals(wasSet);
    }
}
```

## 7. Dikkat edilecek tuzaklar

- **`@CacheEvict` `@Transactional` ile etkileşim.** Default'ta evict commit'ten önce çalışabilir. **Çözüm:** `@CacheEvict(beforeInvocation = false)` + `@Transactional` ile çağrı sonrası evict.
- **Cache update (put) vs evict.** Cache'e yeni değeri yazmak hata kaynağı (stale yazılabilir). **Tercih:** evict (sil), bir sonraki read DB'den yükler.
- **Pattern delete `KEYS *`.** Production'da O(N), tüm Redis bloklu. **Kural:** `SCAN`, kullanılırsa.
- **Cross-service event consumer'da exception.** Retry edilmezse cache stale kalır. **Çözüm:** Spring Kafka retry + DLQ.
- **Event idempotency unutmak.** Aynı event 2 kez gelirse cache 2 kez silinir (zararsız) ama metric'ler şişer; gerçek sorun: business event 2 kez işlenmemeli (zaten outbox'ta event_id ile dedup).
- **Versioned key sonsuz büyür sanmak.** INCR Long max'a kadar gider; Lumix bir tenant için yılda ~milyar bump yapmaz, sorun değil.
- **TTL'i çok uzun + invalidation bug'lı.** Cache bayat kalır günlerce. **Kural:** her cache TTL'i max 1 saat, kritik için max 10dk.
- **Aggregator cache + entity cache senkron sorunu.** "Dashboard count" cache'i entity invalidation'a bağlanmalı. **Çözüm:** dashboard cache TTL kısa (1dk).
- **List cache pattern delete yapmadan invalidation atlamak.** "Yeni student eklenince hangi page silineceğini bilmiyorum" → versioning ile çözüm.
- **`@CacheEvict(allEntries = true)`** tüm namespace siler. Çoğu zaman aşırı geniş. **Kural:** key explicit ver.
- **Outbox'tan publish başarısız.** Kafka down, event yayınlanmadı, cross-service stale. **Çözüm:** outbox relay retry.
- **Cache event'i sadece success path'te.** Compensation/rollback senaryolarında invalidation gerek. **Kural:** event publish ve cache evict mantığı her path'te tutarlı.

## 8. Diğer konularla ilişkisi

- [Cache-Aside Pattern](./03-cache-aside-pattern.md) — invalidation'ın diğer yarısı
- [TTL Strategy](./05-ttl-strategy.md) — TTL safety net
- [Distributed Lock — Redisson](./06-distributed-lock-redisson.md) — stampede protection
- [Permission Change & Revoke Flow](../04-authentication-authorization/06-permission-change-revoke-flow.md) — kritik invalidation örneği
- [Outbox Pattern](../event-driven-architecture) — DB write + Kafka publish atomicity
- [Schema Registry (Apicurio)](../event-driven-architecture) — event schema versioning

## 9. Daha derine inmek için

- Microsoft: [Cache-Aside Pattern — Invalidation section](https://learn.microsoft.com/en-us/azure/architecture/patterns/cache-aside)
- Martin Fowler: "Cache invalidation strategies"
- Spring: [`@CacheEvict` documentation](https://docs.spring.io/spring-framework/reference/integration/cache/annotations.html#cache-annotations-evict)
- Confluent: "Cache Invalidation with Kafka"
- Search keywords:
  - `cache invalidation patterns`
  - `event driven cache invalidation kafka`
  - `versioned cache key strategy`
  - `cache stale data prevention`
  - `cacheevict transactional spring`

## 10. Sözlük

- **Invalidation** — Cache'deki veriyi geçersiz kılma (silme veya bayatlatma).
- **Explicit invalidation** — Yazma operasyonunun cache'i hemen sildiği yöntem.
- **Event-driven invalidation** — Kafka event'i ile cross-service cache silme.
- **Entity cache** — Tek bir entity'yi key olarak tutan cache.
- **View / List cache** — Aggregate sonucu/listeyi tutan cache.
- **Versioned key** — Versiyon counter'ı bump ederek tüm eski key'leri stale yapma tekniği.
- **Pattern delete** — Wildcard ile birden çok key silme (SCAN + DEL).
- **TTL safety net** — Invalidation bug'ı için son savunma; süre bitince otomatik silinir.
- **At-least-once** — Mesajın en az bir kez işleneceği delivery garantisi.
- **Idempotency** — Aynı işlemin tekrar gelmesinin sonucu değiştirmemesi.
- **DLQ (Dead Letter Queue)** — İşlenemeyen mesajların toplandığı yedek topic.

