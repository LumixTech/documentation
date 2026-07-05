---
title: Hibrit Authorization (RBAC + ABAC)
description: Lumix'in role_permission + user_permission + common_permission tabloları, allow/deny precedence, ABAC attribute'ları ve policy resolution sırası.
sidebar_position: 4
---

## Bu sayfa ne anlatıyor?

Lumix'te "Hüseyin bu işlemi yapabilir mi?" sorusu **iki katmanda** cevaplanır: **RBAC** (rolü ne?) ve **ABAC** (hangi şartlar altında?). Bu sayfa bu hibrit modelin tablo tasarımını, allow/deny override sırasını, ABAC attribute'larının (tenant, ownership, environment, scope) nasıl resolve edildiğini ve Spring `@PreAuthorize` ile nasıl ifade edildiğini anlatır.

Hedef okuyucu: backend mühendisi (controller/security yazan), audit incelemecisi, "neden bu kullanıcı şunu görüyor?" sorusunu çözen DevOps.

## 1. Bu nedir? (Sıfırdan)

İki temel authorization modeli var:

| Model | Soru | Örnek |
|---|---|---|
| **RBAC (Role-Based Access Control)** | "Kullanıcının rolü ne, o role ne izinli?" | "Hüseyin TEACHER → `attendance:write` var" |
| **ABAC (Attribute-Based Access Control)** | "Kullanıcının/kaynağın özellikleri ne, ona göre izin var mı?" | "Hüseyin sadece KENDİ sınıfının yoklamasını yazabilir" |

**Hibrit** = ikisini birlikte. **Önce RBAC** ile "kapı açık mı?" kontrolü, **sonra ABAC** ile "doğru kaynağa mı erişiyor?" kontrolü.

### Günlük analoji

Şirketi düşün:
- **RBAC** = kartın yetkisi: "Müdür kartın → tüm katlara girebilirsin"
- **ABAC** = bağlamsal sınırlama: "Ama saat 22:00'dan sonra sadece kendi katın"

Lumix RBAC'a ABAC bindirir.

### Permission, role, scope farkı (kritik karışıklık)

- **Permission** = atomic eylem ("ne yapabilir?") — `attendance:write`, `payment:refund`
- **Role** = permission'ların paketi ("hangi şapka?") — TEACHER, ADMIN, PARENT
- **Scope** = kaynak kapsamı ("hangi veri üzerinde?") — class_ids = [11-A, 12-B]

Bu sayfa **permission + role**'a (RBAC) ve **ABAC attribute**'larına odaklanır. **Scope** ayrı bir konu: [Organizational Scope Resolver](./05-organizational-scope-resolver.md).

## 2. Hangi problemi çözüyor?

### 2.1. Saf RBAC'ın role explosion'ı
Lumix'te 1000 öğretmen var. Her birinin yetkisi biraz farklı: birinin extra refund yetkisi var, diğerine geçici olarak admin verildi, üçüncüsü read-only mode'a alındı. Bunların hepsini ayrı rol yapmak → 1000 rol → yönetim cehennemi.

### 2.2. Saf ABAC'ın anlaşılmazlığı
Tüm kararlar attribute'lara bağlı olursa, "Hüseyin neyi yapabilir?" sorusu kolay cevaplanmaz. UI'da menüleri çizmek için "rol = TEACHER → şu menüler" diye basit bir cevap yetmiyor.

### 2.3. İstisna yetkilendirme ihtiyacı
"Hüseyin TEACHER ama özel olarak `payment:read` da verildi (komite üyesi olarak)." Sadece RBAC ile bu için yeni rol açmak gerekir. **User-permission** override gerekli.

### 2.4. Geçici kısıtlama ihtiyacı
"Bu öğretmen şüpheli durumda, geçici olarak `payment:*` engellendi." Allow/deny precedence ile **explicit deny** olmalı.

### 2.5. Bağlamsal politikalar
"Production environment'ta refund yapılamaz, sadece staging." Bu attribute (environment) RBAC'a girmez.

## 3. Nasıl çözüyor? (Mekanizma + diyagram)

### 3.1. Tablo modeli

```sql
-- Permission catalog (her permission tek satır)
CREATE TABLE permissions (
    id TEXT PRIMARY KEY,             -- 'attendance:write'
    description TEXT,
    resource TEXT NOT NULL,          -- 'attendance'
    action TEXT NOT NULL,            -- 'write'
    risk_level TEXT NOT NULL         -- 'low' | 'medium' | 'high'
);

-- Roller
CREATE TABLE roles (
    id UUID PRIMARY KEY,
    tenant_id UUID,                  -- NULL = global rol
    code TEXT NOT NULL,              -- 'TEACHER'
    name TEXT NOT NULL,
    UNIQUE (tenant_id, code)
);

-- Role → permission (RBAC çekirdeği)
CREATE TABLE role_permission (
    role_id UUID REFERENCES roles(id),
    permission_id TEXT REFERENCES permissions(id),
    effect TEXT NOT NULL DEFAULT 'allow' CHECK (effect IN ('allow', 'deny')),
    PRIMARY KEY (role_id, permission_id)
);

-- User → role
CREATE TABLE user_role (
    user_id UUID,
    role_id UUID REFERENCES roles(id),
    tenant_id UUID NOT NULL,
    PRIMARY KEY (user_id, role_id, tenant_id)
);

-- User-level override (RBAC üzerine)
CREATE TABLE user_permission (
    user_id UUID,
    tenant_id UUID NOT NULL,
    permission_id TEXT REFERENCES permissions(id),
    effect TEXT NOT NULL CHECK (effect IN ('allow', 'deny')),
    valid_from TIMESTAMPTZ,
    valid_until TIMESTAMPTZ,            -- geçici verme/çekme
    granted_by UUID NOT NULL,
    PRIMARY KEY (user_id, tenant_id, permission_id)
);

-- Tüm kullanıcılarda baseline (login eden herkes)
CREATE TABLE common_permission (
    permission_id TEXT PRIMARY KEY REFERENCES permissions(id),
    effect TEXT NOT NULL DEFAULT 'allow'
);
```

### 3.2. Allow/Deny precedence (resolution order)

Lumix'in cevabı şu sırayla resolve edilir:

```
İstek: User X, Tenant T, Permission P, Resource R

1. ABAC environment check:
   - global feature flag açık mı? (örn. payment_module = enabled?)
   - değilse → DENY (kapalı modül)

2. user_permission tablosu:
   - (X, T, P) için varsa → effect kazanır (allow / DENY)
   - Açıklayıcı: explicit user-level deny her şeyi keser

3. role_permission union (X'in T içindeki tüm rolleri):
   - Herhangi birinde DENY varsa → DENY (deny wins)
   - Yoksa, herhangi birinde ALLOW varsa → ALLOW
   - Yoksa → 4'e geç

4. common_permission:
   - P için satır var ve effect=allow → ALLOW
   - Yoksa → DENY (default deny)

5. ABAC ownership/scope check:
   - ALLOW geldiyse bile R kaynağı için tenant_id eşleşiyor mu?
   - Scope (school/class/student) izin veriyor mu?
   - Vermezse → DENY

SONUÇ: 5. adımdan geçerse ALLOW.
```

Görsel olarak:

```
                      ┌─────────────────────────┐
                      │  permission check         │
                      └────────────┬────────────┘
                                   │
                                   ▼
                  ┌─────────── ABAC env  ─────────┐
                  │ flag/env/time uygun mu?       │
                  └───────────────┬───────────────┘
                                  │
                                  ▼
                  ┌─── user_permission var mı? ───┐
                  │   ALLOW ◄────│    │────► DENY │
                  └──────────┬───┘    └──┬────────┘
                             │           │
                             ▼           ▼
                       (kararı al — durur)
                             │
                             ▼ (yoksa)
                  ┌──── role_permission ───────────┐
                  │ herhangi DENY var → DENY        │
                  │ değilse ALLOW varsa → ALLOW     │
                  └──────────┬─────────────────────┘
                             │
                             ▼ (kararsızsa)
                  ┌──── common_permission ─────────┐
                  │ ALLOW varsa → ALLOW             │
                  │ yoksa → DENY (default deny)     │
                  └──────────┬─────────────────────┘
                             │
                             ▼ (ALLOW ise)
                  ┌──── ABAC ownership/scope ──────┐
                  │ resource bu tenant'a mı?        │
                  │ scope izinli mi?                │
                  └──────────┬─────────────────────┘
                             │
                             ▼
                       FINAL DECISION
```

**Sıkı kural: Deny her zaman kazanır.** Bir kullanıcının iki rolü varsa, birinde `allow` diğerinde `deny`, sonuç deny.

### 3.3. ABAC attribute'ları (Lumix'in kullandığı)

| Attribute | Kaynak | Örnek policy |
|---|---|---|
| `subject.tenant_id` | JWT | "Sadece kendi tenant'ının kaynaklarına erişebilir" |
| `subject.tenant_ids[]` | JWT (multi-tenant) | Bölge müdürü tüm atanmış tenant'ları görür |
| `subject.user_id` | JWT | Ownership check ("kendi yarattığı kayıt") |
| `subject.scope` | ScopeResolver | School/class/student kapsamı |
| `resource.tenant_id` | Entity | "Aynı tenant mı?" |
| `resource.owner_id` | Entity | "Bu benim mi?" |
| `environment.feature_flag` | Config | "Modül açık mı?" |
| `environment.time` | Clock | "İş saati içinde mi?" (opsiyonel) |
| `environment.installation_type` | Config | "Demo installation'da refund yasak" |

### 3.4. Resolution caching

Her API çağrısında bu hesaplama yapılmaz. **Login sonrası** kullanıcının "effective permission set"i hesaplanır ve cache'lenir:

```
Key: user:permissions:{uid}:{tenant_id}
TTL: 10 dakika (cache-redis)
Value: { allowed: [...], denied: [...], computed_at: ... }
```

Permission değişince (`role_permission` update, `user_permission` insert vs.) ilgili event ile cache invalidate. Detay: [Permission Change & Revoke Flow](./06-permission-change-revoke-flow.md).

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Permission naming convention

Format: `<resource>:<action>`
Örnekler:
- `student:read`, `student:write`, `student:delete`
- `attendance:write`, `attendance:approve`
- `payment:refund`, `payment:read`
- `report:export`
- `tenant:configure`

Risk level (tabloda) high olanlar (`payment:refund`, `tenant:configure`) **MFA step-up** ister. Detay [MFA step-up](#) (gelecek doc).

### 4.2. Common permissions (baseline)

```sql
INSERT INTO common_permission (permission_id, effect) VALUES
  ('me:read', 'allow'),
  ('me:update-password', 'allow'),
  ('me:sessions:list', 'allow'),
  ('me:sessions:revoke', 'allow'),
  ('me:permissions:read', 'allow');
```

Yani herkes (login eden) kendi profilini görür, kendi session'larını yönetir, kendi permission'larını sorgular.

### 4.3. Spring entegrasyonu

```java
@RestController
@RequestMapping("/api/v1/attendance")
public class AttendanceController {

    @PostMapping
    @PreAuthorize("@authz.can('attendance:write', #req.classId, 'class')")
    public AttendanceResponse mark(@RequestBody @Valid MarkAttendanceRequest req) {
        // ...
    }

    @GetMapping("/{id}")
    @PreAuthorize("@authz.canAccess('attendance:read', #id)")
    public AttendanceResponse get(@PathVariable UUID id) {
        // ...
    }
}
```

`@authz` bean'i RBAC + ABAC karar mekanizmasını barındırır.

### 4.4. Default deny

Lumix kuralı: **bir endpoint @PreAuthorize'sız bırakılamaz.** Build-time check (ArchUnit) + CI gate.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

| Alternatif | Tartışma |
|---|---|
| **Saf RBAC** | Role explosion + bağlamsal kural yok. **Elendi.** |
| **Saf ABAC** | Anlaşılmaz, UI menu çizimi zor. **Elendi.** |
| **OPA (Open Policy Agent) + Rego** | Çok güçlü ama operational karmaşa (ekstra sidecar, learning curve). İleride re-evaluation. **Şimdilik elendi.** |
| **Casbin** | Hafif ABAC lib; ama Spring entegrasyon olgunluğu sınırlı. **Elendi.** |
| **Spring Security + custom PermissionEvaluator (Lumix)** | ✓ Mevcut framework'le entegre, debug edilebilir, DB-driven. |

### Trade-off'lar

- **DB-driven policy:** Policy değişimleri için DB migration veya admin UI gerek. Avantaj: explicit, audit edilebilir.
- **Cache invalidation karmaşası:** Permission değişince event ile invalidate gerek. Detay: ayrı doc.
- **Karmaşık precedence:** Allow/deny override sırası iyi belgelendirilmezse "neden bu deny geldi?" sorusu zor cevaplanır. Çözüm: detaylı audit log + decision trace mode (debug).

## 6. Pratik örnek

### 6.1. PermissionResolver

```java
@Service
@RequiredArgsConstructor
public class PermissionResolver {

    private final UserPermissionRepository userPermRepo;
    private final RolePermissionRepository rolePermRepo;
    private final CommonPermissionRepository commonRepo;
    private final UserRoleRepository userRoleRepo;
    private final FeatureFlagService featureFlags;

    public Decision resolve(UUID userId, UUID tenantId, String permission) {
        // 1. ABAC env
        if (!featureFlags.isEnabledForTenant(permission, tenantId)) {
            return Decision.deny("feature_disabled");
        }

        // 2. user_permission
        Optional<UserPermission> userOverride = userPermRepo
            .findActive(userId, tenantId, permission, Instant.now());
        if (userOverride.isPresent()) {
            return userOverride.get().effect() == Effect.ALLOW
                ? Decision.allow("user_explicit_allow")
                : Decision.deny("user_explicit_deny");
        }

        // 3. role_permission union
        List<UUID> roleIds = userRoleRepo.findRoleIds(userId, tenantId);
        List<RolePermission> rolePerms = rolePermRepo.findByRoles(roleIds, permission);

        if (rolePerms.stream().anyMatch(rp -> rp.effect() == Effect.DENY)) {
            return Decision.deny("role_deny_wins");
        }
        if (rolePerms.stream().anyMatch(rp -> rp.effect() == Effect.ALLOW)) {
            return Decision.allow("role_allow");
        }

        // 4. common
        Optional<CommonPermission> common = commonRepo.find(permission);
        if (common.isPresent() && common.get().effect() == Effect.ALLOW) {
            return Decision.allow("common_baseline");
        }

        // 5. default deny
        return Decision.deny("default_deny");
    }
}
```

### 6.2. PermissionEvaluator (Spring Security entegrasyonu)

```java
@Component("authz")
@RequiredArgsConstructor
public class LumixAuthz {

    private final PermissionResolver resolver;
    private final ScopeResolver scopeResolver;
    private final OwnershipChecker ownership;
    private final RequestContext context;

    public boolean can(String permission, Object resourceId, String resourceType) {
        UUID userId = context.userId();
        UUID tenantId = context.tenantId();

        Decision decision = resolver.resolve(userId, tenantId, permission);
        if (!decision.allowed()) {
            audit(decision, permission, resourceId);
            return false;
        }

        // ABAC ownership/scope check
        if (resourceId != null) {
            boolean inScope = scopeResolver.isInScope(userId, tenantId, resourceType, resourceId);
            if (!inScope) {
                audit(Decision.deny("out_of_scope"), permission, resourceId);
                return false;
            }
        }

        audit(decision, permission, resourceId);
        return true;
    }

    public boolean canAccess(String permission, UUID resourceId) {
        return can(permission, resourceId, inferType(permission));
    }

    private void audit(Decision d, String perm, Object rid) {
        // Async: AuthzAuditPublisher (Kafka topic: audit.authz.v1)
    }
}
```

### 6.3. Cache'lenmiş hızlı path

```java
@Service
@RequiredArgsConstructor
public class CachedPermissionResolver {

    private final PermissionResolver delegate;
    private final RedisTemplate<String, EffectivePermissionSet> cache;

    public EffectivePermissionSet getEffective(UUID userId, UUID tenantId) {
        String key = "user:permissions:" + userId + ":" + tenantId;
        EffectivePermissionSet cached = cache.opsForValue().get(key);
        if (cached != null) return cached;

        EffectivePermissionSet fresh = compute(userId, tenantId);
        cache.opsForValue().set(key, fresh, Duration.ofMinutes(10));
        return fresh;
    }

    private EffectivePermissionSet compute(UUID uid, UUID tid) {
        Set<String> allPermissionIds = delegate.allKnownPermissions();
        Set<String> allowed = new HashSet<>();
        Set<String> denied = new HashSet<>();
        for (String p : allPermissionIds) {
            Decision d = delegate.resolve(uid, tid, p);
            if (d.allowed()) allowed.add(p); else denied.add(p);
        }
        return new EffectivePermissionSet(uid, tid, allowed, denied, Instant.now());
    }
}
```

### 6.4. Geçici izin verme (user_permission)

```java
// Komite üyesine 2 hafta payment:read ver
userPermRepo.save(new UserPermission(
    userId, tenantId, "payment:read",
    Effect.ALLOW,
    Instant.now(),
    Instant.now().plus(Duration.ofDays(14)),
    grantedBy));

// Permission cache invalidate
eventPublisher.publish(new UserPermissionChangedEvent(userId, tenantId));
```

## 7. Dikkat edilecek tuzaklar

- **`@PreAuthorize`'yi unutmak.** Endpoint açık kalır. **CI gate:** ArchUnit ile "tüm controller method'larında @PreAuthorize var" kontrolü.
- **Allow override deny.** Yanlış precedence implementasyonu güvenlik açığı. **Test:** her precedence senaryosu için unit test.
- **Permission ID'lerini string olarak elden almak (typo).** `attendance:write` vs `attendence:write` kontrolsüz geçer. **Çözüm:** code-gen ile enum üret, `PermissionIds.ATTENDANCE_WRITE` gibi.
- **`@PreAuthorize` içine business logic koymak.** SpEL içinde kompleks DB sorgusu = bakım kabusu. **Kural:** SpEL sadece `@authz.can(...)` veya `hasRole(...)` çağırsın.
- **Scope check'i atlayan endpoint.** Permission var ama scope yok; kullanıcı başka tenant'ın verisini görüyor. **Kural:** `can(permission, resourceId, resourceType)` her zaman scope check'i tetikler.
- **Permission cache invalidate'i unutmak.** UI hâlâ menü gösteriyor, backend deny dönüyor → "neden çalışmıyor?". **Çözüm:** event-driven invalidation. Detay [Permission Change Flow](./06-permission-change-revoke-flow.md).
- **Multi-tenant kullanıcıda yanlış tenant context.** Bölge müdürü tek tenant'lı role check ile yanlış sonuç. **Kural:** tenant_id explicit, request başında set edilir.
- **`common_permission` yetkisi yüksek riskli action içeriyor.** `payment:refund` common'da olamaz. **Kural:** common sadece `me:*` ve read-only baseline.
- **Decision trace yok.** "Neden deny?" sorusuna cevap zor. **Çözüm:** debug mode'da decision reason audit log'a yazılır.
- **Deny effect kullanmamak.** Tüm istisnaları "rol'den çıkar" diye çözmek zor; sometimes explicit deny gerek.

## 8. Diğer konularla ilişkisi

- [Organizational Scope Resolver](./05-organizational-scope-resolver.md) — bu sayfada ABAC scope kısmının detayı
- [Permission Change & Revoke Flow](./06-permission-change-revoke-flow.md) — cache invalidation
- [Installation/Tenant/Scope](../01-tenancy-and-domain-model/01-installation-tenant-scope.md) — tenant_id ve scope nereden geliyor
- [Fully Stateful Token Modeli](./01-stateful-token-model.md) — permission JWT'de yok, /me/permissions ile çekilir

## 9. Daha derine inmek için

- NIST: [SP 800-162 — Attribute-Based Access Control](https://csrc.nist.gov/publications/detail/sp/800-162/final)
- NIST: [RBAC Standard (INCITS 359-2012)](https://csrc.nist.gov/projects/role-based-access-control)
- OWASP: [Authorization Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html)
- AWS: "IAM policy evaluation logic" (concept benzer, deny override mantığı)
- Search keywords:
  - `rbac abac hybrid model`
  - `deny override permission resolution`
  - `attribute based access control implementation`
  - `spring security custom permission evaluator`
  - `policy decision point pdp pep`

## 10. Sözlük

- **RBAC (Role-Based Access Control)** — Yetkinin rol üzerinden atandığı model.
- **ABAC (Attribute-Based Access Control)** — Yetkinin attribute'lara (kullanıcı/kaynak/çevre) göre değerlendirildiği model.
- **Permission** — Atomic yetki tanımı (`<resource>:<action>`).
- **Role** — Permission'ların paketi.
- **Allow / Deny effect** — Bir permission satırının izin verici mi engelleyici mi olduğu.
- **Deny override / Deny wins** — Çakışmada deny'in galip gelmesi kuralı.
- **Default deny** — Hiçbir kural eşleşmezse erişim reddedilir.
- **Common permission** — Tüm kullanıcılar için baseline permission seti.
- **User permission override** — RBAC üzerinde kişiye özel ek/eksiltme.
- **Effective permission set** — Bir kullanıcının resolution sonrası net olarak erişebildiği permission listesi.
- **Decision trace** — Bir kararın hangi kuralla verildiğini saklayan audit kaydı.
- **PDP / PEP** — Policy Decision Point (karar veren), Policy Enforcement Point (uygulayan).

