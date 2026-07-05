---
title: Organizational Scope Resolver
description: School → Class → Student short-circuit scope resolver, ScopedFilter interceptor pagination uyumluluğu, bölge müdürü (multi-tenant) handling, RLS ile entegrasyon.
sidebar_position: 5
---

## Bu sayfa ne anlatıyor?

Hüseyin TEACHER rolüyle `attendance:write` izinli. **Ama hangi sınıfa?** Bu sayfa Lumix'in **organizational scope** modelini, **short-circuit resolution algoritmasını**, ScopedFilter interceptor'ının JPA query'lerine nasıl predikat eklediğini, multi-tenant kullanıcılarda (bölge müdürü) nasıl çalıştığını ve PostgreSQL RLS ile nasıl beraber yürüdüğünü anlatır.

## 1. Bu nedir? (Sıfırdan)

**Organizational scope** = bir kullanıcının tenant içinde **hangi alt seviyedeki** veriye erişebileceğinin tanımı. Lumix'in hiyerarşisi:

```
Tenant (Kadıköy Şubesi)
  └─ School (Lise)
       └─ Class (11-A)
            └─ Student (Ahmet)
```

Bir kullanıcının scope'u bu hiyerarşinin **herhangi bir seviyesinde** olabilir:

| Seviye | Anlam | Tipik rol |
|---|---|---|
| School | Tüm okulun verileri | Müdür yardımcısı |
| Class | Belirli sınıflar | Sınıf öğretmeni |
| Student | Belirli öğrenciler | Veli |

### Permission ≠ Scope (tekrar)

Bu ayrımı bir kez daha vurgulayalım:
- **Permission:** "Hüseyin yoklamayı yazabilir" → ne yapabilir?
- **Scope:** "Hüseyin sadece 11-A ve 12-B sınıflarında" → hangi veri üzerinde?

İkisi birlikte: 12-A için yoklama yazmak → permission var, scope yok → DENY.

## 2. Hangi problemi çözüyor?

### 2.1. Liste endpoint'lerinde filtreleme
`GET /api/v1/students` çağrıldığında ne döner? Tüm tenant öğrencileri değil — kullanıcının **scope'undakiler**. Bu filtre her endpoint'te elle yazılamaz; merkezi bir mekanizma şart.

### 2.2. Tek kaynak erişimi
`GET /api/v1/students/{id}` çağrıldığında 404 mü 403 mü dönecek? Doğrusu: scope dışındaysa **404** (kaynağın varlığını bile sızdırma).

### 2.3. Pagination uyumu
Scope filter sorguya predikat eklerken pagination COUNT'unu da bozmamalı. "Filter sonrası 23 satır" tutarlı olmalı.

### 2.4. Bölge müdürü (multi-tenant)
Veli, 5 şubenin müdürü. Her şubenin verisini görmeli ama o şubenin tüm scope'unu (school-level). Scope resolver bu çoklu tenant durumunu da çözmeli.

### 2.5. Performance
Her request başında scope tablosunu okumak yavaş. **Cache** zorunlu.

## 3. Nasıl çözüyor? (Mekanizma + diyagram)

### 3.1. Scope assignment veri modeli

```sql
CREATE TABLE user_scope_assignments (
    user_id        UUID NOT NULL,
    tenant_id      UUID NOT NULL,
    scope_type     TEXT NOT NULL CHECK (scope_type IN ('school', 'class', 'student')),
    scope_target_id UUID NOT NULL,
    granted_at     TIMESTAMPTZ NOT NULL,
    granted_by     UUID NOT NULL,
    expires_at     TIMESTAMPTZ,
    PRIMARY KEY (user_id, tenant_id, scope_type, scope_target_id)
);

CREATE INDEX idx_scope_user_tenant
  ON user_scope_assignments (user_id, tenant_id, scope_type);
```

Bir kullanıcının birden fazla satırı olabilir:
- Hüseyin TEACHER:
  - (uid, tenant_kadikoy, 'class', 11-A)
  - (uid, tenant_kadikoy, 'class', 12-B)

### 3.2. Short-circuit resolution algoritması

```
ScopeResolver.resolveScope(userId, tenantId):

  1. Cache lookup: scope:effective:{uid}:{tenantId}
     → hit ise dön.

  2. SELECT scope_type, scope_target_id
     FROM user_scope_assignments
     WHERE user_id = ? AND tenant_id = ?
       AND (expires_at IS NULL OR expires_at > now())

  3. Short-circuit:
     a. Eğer school-level satır varsa → ScopeResult(SCHOOL, school_ids)
        (class/student satırlarını GÖRMEZDEN GEL — school zaten geniş)

     b. school yoksa, class-level varsa → ScopeResult(CLASS, class_ids)
        (student satırlarını GÖRMEZDEN GEL — class zaten geniş)

     c. ne school ne class yoksa, student-level varsa
        → ScopeResult(STUDENT, student_ids)

     d. hiçbiri yoksa → ScopeResult(NONE, [])

  4. Cache yaz: TTL 5dk (cache-redis)
     Key: scope:effective:{uid}:{tenantId}
     Value: { type: CLASS, ids: [11-A, 12-B] }

  5. Dön.
```

Görsel:

```
        scope satırları
                │
                ▼
       ┌──────────────────┐
       │ school var mı?   │── EVET ──► SCHOOL(ids)
       └────────┬─────────┘
            HAYIR
                ▼
       ┌──────────────────┐
       │ class var mı?    │── EVET ──► CLASS(ids)
       └────────┬─────────┘
            HAYIR
                ▼
       ┌──────────────────┐
       │ student var mı?  │── EVET ──► STUDENT(ids)
       └────────┬─────────┘
            HAYIR
                ▼
          NO_ACCESS
```

**Neden short-circuit?** Çünkü school-level scope, class ve student'i implicitly içerir. Ayrıca tutmaya gerek yok; sorgu basitleşir.

### 3.3. Multi-tenant kullanıcı (bölge müdürü)

```
ScopeResolver.resolveScope(userId, tenantIds=[t1, t2, t3]):

  Her tenant_id için ayrı resolve çalışır:
    scope_for_t1 = resolve(userId, t1)   → SCHOOL(...)
    scope_for_t2 = resolve(userId, t2)   → SCHOOL(...)
    scope_for_t3 = resolve(userId, t3)   → CLASS(...)

  ScopeContext = {
    t1: SchoolScope(...),
    t2: SchoolScope(...),
    t3: ClassScope(...)
  }

  Request'in aktif tenant'ı header'dan alınır (X-Active-Tenant)
  ve sadece o tenant'ın scope'u apply edilir.
```

DB session'da `app.tenant_ids` array set edilir, RLS policy union ile filter eder. Detay: [Installation/Tenant/Scope](../01-tenancy-and-domain-model/installation-tenant-scope).

### 3.4. ScopedFilter interceptor (JPA entegrasyonu)

```
Repository.findAll(Pageable) çağrısı
              │
              ▼
   Hibernate session
              │
              ▼
  ScopeFilterInterceptor (Hibernate @FilterDef)
              │
              ▼
  Query rewrite:
    SELECT ... FROM students s
    WHERE s.tenant_id = :tenant_id            ← RLS bunu zaten yapar
      AND s.class_id IN (:scope_class_ids)    ← interceptor ekliyor
              │
              ▼
  PostgreSQL execute
              │
              ▼
  RLS policy double-check (savunma katmanı)
```

Pagination COUNT sorgusu da aynı filter'dan geçer, sayılar tutarlı.

### 3.5. Cache invalidation

Scope değişimi (yeni atama, expire, revoke) → event yayını → cache silme:

```
scope_assignment INSERT/UPDATE/DELETE
            │
            ▼
   Outbox → Kafka topic: identity.scope.changed.v1
            │
            ▼
   identity-service consumer + diğer servisler:
   DEL scope:effective:{uid}:{tenantId}
```

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Scope storage

- **PostgreSQL** `user_scope_assignments` tablosunda canonical.
- **cache-redis**'te effective scope (5dk TTL).
- JWT'de **scope YOK** (token şişmesin); her servis kendi cache'inden okur.

### 4.2. ScopedFilter convention

Tenant-scoped tabloların tümü scope-aware kolonu taşır:
- `students.class_id`, `students.school_id`, `students.id`
- `attendances.class_id`
- `messages.class_id` veya `messages.recipient_user_id`

Her domain entity için `@ScopeAware` annotation:

```java
@Entity
@Table(name = "students")
@ScopeAware(
    scopeColumn = @ScopeColumn(type = SCHOOL, column = "school_id"),
    scopeColumn = @ScopeColumn(type = CLASS, column = "class_id"),
    scopeColumn = @ScopeColumn(type = STUDENT, column = "id")
)
public class StudentEntity { ... }
```

### 4.3. Request context

```
HTTP Request
   │
   ▼
SecurityFilterChain → ScopeContextFilter
   │
   ▼
ScopeResolver.resolveScope(uid, tenant_id) → cache lookup
   │
   ▼
ThreadLocal'e koy: ScopeContext
   │
   ▼
@PreAuthorize, Repository, Service hepsi okuyabilir
```

### 4.4. Endpoint guard

```java
@PreAuthorize("@authz.can('student:read', #id, 'student')")
@GetMapping("/{id}")
public StudentResponse get(@PathVariable UUID id) { ... }
```

`@authz.can(...)` içinde scope check yapılır. Scope dışındaysa **404** dönülür (existence sızdırmama).

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

| Alternatif | Tartışma |
|---|---|
| **Scope'u JWT'ye koy (binary blob)** | Token şişer, scope değişiminde refresh gerekir. **Elendi.** |
| **Her endpoint'te elle filter** | DRY ihlali, unutulan endpoint = veri sızıntısı. **Elendi.** |
| **Sadece RLS (PostgreSQL)** | RLS string-tabanlı (`app.scope_class_ids`); flexible ama Hibernate query plan'ı bozulabilir. |
| **Hibernate `@Filter` + RLS** | ✓ İki katman: app filter + RLS. Defense-in-depth. |
| **Custom JPA interceptor (Lumix)** | ✓ Annotation-driven, test edilebilir, pagination uyumlu. |

### Trade-off'lar

- **Query plan etkisi:** `class_id IN (...)` predikatı büyük listede yavaşlatabilir. **Çözüm:** scope'u `bigint[]` parameter olarak gönderip GIN index'le çalış.
- **Cache freshness:** Scope cache 5dk TTL → en kötü senaryoda yeni atama 5dk sonra aktif. Event-driven invalidation ile bu aslında saniyeler içinde.
- **Multi-tenant complexity:** Bölge müdürü için aynı kullanıcının her tenant'ta farklı scope'u olabilir. Cache key tasarımında `{uid}:{tenant_id}` zorunlu.

## 6. Pratik örnek

### 6.1. ScopeResolver implementation

```java
@Service
@RequiredArgsConstructor
public class ScopeResolver {

    private final UserScopeRepository repo;
    private final StringRedisTemplate cache;
    private final ObjectMapper json;

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    public ScopeResult resolveScope(UUID userId, UUID tenantId) {
        String key = "scope:effective:" + userId + ":" + tenantId;
        String cached = cache.opsForValue().get(key);
        if (cached != null) return deserialize(cached);

        List<UserScopeAssignment> rows = repo.findActive(userId, tenantId, Instant.now());
        ScopeResult result = computeShortCircuit(rows);

        cache.opsForValue().set(key, json.writeValueAsString(result), CACHE_TTL);
        return result;
    }

    private ScopeResult computeShortCircuit(List<UserScopeAssignment> rows) {
        Set<UUID> schoolIds = new HashSet<>();
        Set<UUID> classIds = new HashSet<>();
        Set<UUID> studentIds = new HashSet<>();

        for (UserScopeAssignment row : rows) {
            switch (row.scopeType()) {
                case SCHOOL  -> schoolIds.add(row.scopeTargetId());
                case CLASS   -> classIds.add(row.scopeTargetId());
                case STUDENT -> studentIds.add(row.scopeTargetId());
            }
        }

        if (!schoolIds.isEmpty()) return ScopeResult.school(schoolIds);
        if (!classIds.isEmpty())  return ScopeResult.classes(classIds);
        if (!studentIds.isEmpty()) return ScopeResult.students(studentIds);
        return ScopeResult.none();
    }

    public boolean isInScope(UUID userId, UUID tenantId,
                              String resourceType, UUID resourceId) {
        ScopeResult scope = resolveScope(userId, tenantId);
        // resourceType ve resourceId'i scope ile karşılaştır
        return ScopeMatcher.matches(scope, resourceType, resourceId);
    }
}
```

### 6.2. JPA Hibernate `@Filter` ile entegrasyon

```java
@Entity
@Table(name = "students")
@FilterDef(name = "scope_filter",
    parameters = {
        @ParamDef(name = "scopeType", type = String.class),
        @ParamDef(name = "scopeIds",  type = UUID[].class)
    })
@Filter(name = "scope_filter", condition = """
        CASE
          WHEN :scopeType = 'SCHOOL'  THEN school_id  = ANY(:scopeIds)
          WHEN :scopeType = 'CLASS'   THEN class_id   = ANY(:scopeIds)
          WHEN :scopeType = 'STUDENT' THEN id         = ANY(:scopeIds)
          WHEN :scopeType = 'NONE'    THEN FALSE
        END
""")
public class StudentEntity { ... }
```

```java
@Component
@RequiredArgsConstructor
public class ScopeFilterInterceptor implements HandlerInterceptor {

    private final ScopeResolver scopeResolver;
    private final EntityManager em;
    private final RequestContext ctx;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        ScopeResult scope = scopeResolver.resolveScope(ctx.userId(), ctx.tenantId());

        Session session = em.unwrap(Session.class);
        Filter filter = session.enableFilter("scope_filter");
        filter.setParameter("scopeType", scope.type().name());
        filter.setParameterList("scopeIds", scope.ids().toArray(UUID[]::new));
        return true;
    }
}
```

### 6.3. ScopeResult tipi

```java
public sealed interface ScopeResult permits
    ScopeResult.School, ScopeResult.Classes, ScopeResult.Students, ScopeResult.None {

    ScopeType type();
    Set<UUID> ids();

    static ScopeResult school(Set<UUID> ids)   { return new School(ids); }
    static ScopeResult classes(Set<UUID> ids)  { return new Classes(ids); }
    static ScopeResult students(Set<UUID> ids) { return new Students(ids); }
    static ScopeResult none()                  { return new None(); }

    record School(Set<UUID> ids) implements ScopeResult {
        public ScopeType type() { return ScopeType.SCHOOL; }
    }
    record Classes(Set<UUID> ids) implements ScopeResult {
        public ScopeType type() { return ScopeType.CLASS; }
    }
    record Students(Set<UUID> ids) implements ScopeResult {
        public ScopeType type() { return ScopeType.STUDENT; }
    }
    record None() implements ScopeResult {
        public Set<UUID> ids() { return Set.of(); }
        public ScopeType type() { return ScopeType.NONE; }
    }
}
```

### 6.4. Multi-tenant ScopeContext

```java
public record ScopeContext(
    UUID userId,
    UUID activeTenantId,
    Map<UUID, ScopeResult> perTenant
) {
    public ScopeResult active() {
        return perTenant.getOrDefault(activeTenantId, ScopeResult.none());
    }
}

@Service
public class MultiTenantScopeResolver {

    public ScopeContext build(UUID userId, List<UUID> tenantIds, UUID activeTenantId) {
        Map<UUID, ScopeResult> map = tenantIds.stream()
            .collect(toMap(t -> t, t -> scopeResolver.resolveScope(userId, t)));
        return new ScopeContext(userId, activeTenantId, map);
    }
}
```

### 6.5. Endpoint örnek

```java
@RestController
@RequestMapping("/api/v1/students")
public class StudentController {

    @GetMapping
    @PreAuthorize("@authz.can('student:read')")
    public Page<StudentResponse> list(Pageable pageable) {
        // Repository sorguya scope filter Hibernate interceptor ile eklenir
        return studentService.list(pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('student:read', #id, 'student')")
    public StudentResponse get(@PathVariable UUID id) {
        return studentService.get(id);   // canAccess başarısız ise 404 dönecek
    }
}
```

## 7. Dikkat edilecek tuzaklar

- **JWT'ye scope koymak.** Token şişer ve scope değişimi anında yansımaz. **Kural:** scope JWT'de YOK.
- **Short-circuit'i unutmak.** School + class + student satırlarını **AND** ile birleştirirsen, school-level kullanıcının verisi class filter'ı tarafından kesilir. **Kural:** OR mantığı + short-circuit.
- **Scope dışı kaynakta 403 dönmek.** Existence sızdırırsın (saldırgan `GET /students/{uuid}` ile UUID'leri test edebilir). **Kural:** scope dışı = 404.
- **Pagination COUNT'unu filter'sız çalıştırmak.** "10/100" göstermeli ama filtersiz COUNT 1000 döner → UI yanlış. **Kural:** COUNT da scope filter'dan geçer.
- **Liste endpoint'inde scope filter unutmak.** "Tüm öğrenciler" dönülür → veri sızıntısı. **Kural:** repository default `@Filter("scope_filter")` enable.
- **Cache TTL'i çok uzun.** Scope çekildiğinde 30dk sonra etkili olur. **Çözüm:** event-driven invalidation 5dk TTL'i destekler.
- **Multi-tenant'ta yanlış tenant context'i.** `X-Active-Tenant` header'ı validate edilmezse kullanıcı kendi tenant'larından birini seçer ama saldırgan başkasını gönderir. **Kural:** active tenant her zaman `subject.tenant_ids`'in subset'i.
- **Scope assignment'a expires_at koymamak.** Geçici atamalar süresiz aktif kalır. **Çözüm:** her atama bir expires_at önerisi alır.
- **RLS olmadan sadece app filter.** App filter bypass'lanabilirse (örn. native query) veri sızar. **Kural:** RLS + app filter, iki katman.
- **NoScope kullanıcıları çalıştırmak.** Hiçbir scope satırı yoksa default deny olmalı; default allow tehlikeli.

## 8. Diğer konularla ilişkisi

- [Installation/Tenant/Scope](../01-tenancy-and-domain-model/installation-tenant-scope) — scope modelinin tanımı
- [Hibrit RBAC + ABAC](./rbac-abac-hybrid) — scope ABAC tarafının bir parçası
- [Permission Change & Revoke Flow](./permission-change-revoke-flow) — scope değişiminde cache invalidation
- [Fully Stateful Token Modeli](./stateful-token-model) — scope JWT'de değil, request başında resolve edilir
- [Tenant-based RLS](../database-architecture/tenant-based-rls-policy-design) — RLS detayı

## 9. Daha derine inmek için

- PostgreSQL: [Row Security Policies](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)
- Hibernate: [@Filter and @FilterDef](https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#pc-filter)
- Microsoft: "Multi-tenant SaaS authorization patterns"
- Search keywords:
  - `hierarchical authorization scope`
  - `row level security multi tenant postgres`
  - `hibernate filter pagination`
  - `attribute scope resolver pattern`
  - `school class student permission model`

## 10. Sözlük

- **Scope** — Kullanıcının tenant içinde görebileceği veri kapsamı (school/class/student).
- **ScopeResolver** — Bir kullanıcının effective scope'unu hesaplayan komponent.
- **ScopeResult** — School/Class/Student/None tipinden biri, ids içerir.
- **Short-circuit resolution** — Daha geniş seviye varsa daha dar seviyeleri görmezden gelme.
- **ScopedFilter** — Repository sorgularına scope predikatı ekleyen interceptor.
- **Effective scope** — Resolution sonrası net scope kümesi (cache'lenebilir).
- **Multi-tenant kullanıcı** — Birden çok tenant'ta aktif olabilen kullanıcı (bölge müdürü).
- **Existence leak** — 403/404 ayrımıyla bir kaynağın var olup olmadığını sızdırma.
- **Defense in depth** — App filter + DB RLS iki katmanlı koruma.

