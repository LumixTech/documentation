---
title: Permission Change & Revoke Flow
description: Permission değişince tüm token revoke + force re-login akışı, identity-service event yayını, servisler arası cache invalidation, RTK Query frontend invalidation.
sidebar_position: 6
---

## Bu sayfa ne anlatıyor?

Lumix'te bir kullanıcının yetkisi değişti — yeni rol verildi, eski rol çekildi, ya da `user_permission` override eklendi. Bu değişikliğin **anında ve tutarlı** her yere yansıması gerekir: backend Redis cache, frontend UI menü, varsa sürmekte olan WebSocket subscription. Bu sayfa bu **end-to-end invalidation akışını** anlatır.

## 1. Bu nedir? (Sıfırdan)

Permission caching iki yerde yaşar:
- **Backend (Redis):** `user:permissions:{uid}:{tenant_id}` 10dk TTL.
- **Frontend (RTK Query):** `/me/permissions` endpoint response'u.

Yetki değişti, **bu iki cache de "kirli"**. Cache invalidation sistemi bunları temizler.

Ek soru: kullanıcının elinde **şu an aktif** access token var. O token Redis'te `ACTIVE`. Yetki değişiminde **token'ı ne yapacağız?** İki yaklaşım:

1. **Soft:** Token'ı bozma, sadece cache'i invalide et. Yeni permission set otomatik /me/permissions ile öğrenilir.
2. **Hard:** Token'ı revoke et, force re-login. Yeni session, yeni claims.

Lumix **karma yaklaşım** kullanır:
- Düşük riskli değişimde (yetki **eklenmesi**): soft, cache invalidation yeterli.
- Yüksek riskli değişimde (yetki **çekilmesi** veya rol değişimi): **hard**, token revoke + force refresh.

### Günlük analoji

Şirket kartına yetki ekledin (parking access). Karta yeni yetki yazılmadı bile (cache invalidation = işyeri bilgisayar listesi update); kapı zaten "evet bu kart yetkili" diyor sistemini kontrol ediyor. Ama bir çalışana yetkisini **çektiysen** kart geri alınır (token revoke) — eski karttaki yetki bilgisi tehlike olabilir.

## 2. Hangi problemi çözüyor?

### 2.1. "UI menü gösteriyor, backend deny dönüyor" hayalet hatası
Permission çekildi ama frontend cache 5 dakika daha eskiyi tutuyor. Kullanıcı menüden tıklıyor, "Forbidden" diyor. Kötü UX.

### 2.2. Stale permission ile yetkisiz işlem
Backend `user:permissions:{uid}` cache'i 10dk TTL. Saldırgan bu pencerede yetkilendirilmemiş eylemi yapabilir.

### 2.3. JWT'de eski tenant
Multi-tenant kullanıcının `tenant_ids` listesi değişti (bir tenant'tan çıkarıldı). JWT'de hâlâ eski liste. **Hard revoke** zorunlu.

### 2.4. Force re-login senaryosu
"Admin tarafından kullanıcı suspend edildi." Yeni isteklerde 401 dönmek + frontend'i login'e atmak gerek.

### 2.5. Audit ihtiyacı
"Yetki ne zaman, neden, kim tarafından değişti?" Audit log + event akışı zorunlu.

## 3. Nasıl çözüyor? (Mekanizma + diyagram)

### 3.1. Event taxonomy

```
identity-service Kafka topic'leri:

  identity.permission.changed.v1   → role_permission tablosu değişti
  identity.user.permission.v1      → user_permission değişti (single user)
  identity.user.role.changed.v1    → user_role değişti
  identity.user.suspended.v1       → kullanıcı askıya alındı
  identity.scope.changed.v1        → user_scope_assignments değişti
```

Her event'in payload'unda en az:
```
{
  "event_id": "uuid-v7",
  "occurred_at": "...",
  "tenant_id": "...",
  "user_id": "...",
  "change_type": "ROLE_ASSIGNED | ROLE_REVOKED | PERMISSION_GRANTED | PERMISSION_REVOKED",
  "details": { ... },
  "actor": "admin-user-id",
  "should_revoke_tokens": true|false
}
```

### 3.2. End-to-end akış (yüksek riskli, hard revoke)

```
1. Admin → Customer Admin Panel → "Hüseyin'in payment:refund yetkisini çek"
                         │
                         ▼
2. identity-service:
   BEGIN TX
     UPDATE/DELETE user_permission ...
     INSERT outbox_events (event=identity.user.permission.v1,
                            should_revoke_tokens=true)
   COMMIT
                         │
                         ▼
3. Outbox relay → Kafka publish

4. Multiple consumers:

   a) identity-service self-consumer:
      - user:permissions:{uid}:{tid} cache DEL
      - SMEMBERS user:tokens:{uid} → tüm jti'ler
      - Her access:{jti}.status = REVOKED
      - SMEMBERS user:sessions:{uid} → her session.status = REVOKED
      - DEL user:tokens:{uid}, user:sessions:{uid}
      - Refresh token family'lerini DEL

   b) Diğer microservice'ler:
      - user:permissions:{uid}:{tid} cache DEL (local cache-redis)
      - Aktif Spring cache (@Cacheable) varsa @CacheEvict

   c) WebSocket realtime adapter (notification-service):
      - convertAndSendToUser(uid, "/queue/auth.revoked", payload)
      - Frontend bu mesajla "force re-login" yapar

   d) audit-service:
      - audit_log append: "permission revoked, by=actor, at=..."

5. Frontend (kullanıcı tarafı):

   a) WebSocket /queue/auth.revoked mesajı geldi:
      - Tüm RTK Query cache invalidate
      - Redux auth slice → logout
      - Router → /login (toast: "Yetkileriniz güncellendi, lütfen tekrar giriş yapın")

   b) Eğer WS bağlı değilse (sayfa kapalıydı):
      - Bir sonraki API çağrısı 401 döner
      - Frontend axios interceptor → refresh dener
      - Refresh de 401 → /login redirect
```

Diyagram:

```
┌────────────────┐        ┌──────────────┐        ┌──────────────┐
│   ADMIN UI     │        │  identity    │        │   KAFKA      │
│  (Customer     │ POST   │  service     │  pub   │              │
│   Admin Panel) │───────►│              │───────►│ identity.    │
│                │        │  DB write +  │        │ user.        │
└────────────────┘        │  outbox      │        │ permission.v1│
                          └──────────────┘        └──────┬───────┘
                                                          │
                ┌─────────────────────────────────────────┼─────────┐
                │                                         │         │
                ▼                                         ▼         ▼
        ┌──────────────┐                        ┌──────────────┐  ┌────────────┐
        │ identity     │                        │  diğer       │  │ audit      │
        │ self-consumer│                        │  servisler   │  │ service    │
        │              │                        │              │  │            │
        │ - perm cache │                        │ - perm cache │  │ audit_logs │
        │   DEL        │                        │   DEL        │  │ append     │
        │ - token      │                        │ - @CacheEvict│  └────────────┘
        │   revoke     │                        └──────────────┘
        │ - session    │
        │   revoke     │
        │ - WS notify  │
        └──────┬───────┘
               │
               ▼ WebSocket
        ┌──────────────┐
        │  FRONTEND    │
        │              │
        │ - RTK cache  │
        │   reset      │
        │ - Redux      │
        │   logout     │
        │ - /login     │
        └──────────────┘
```

### 3.3. Soft invalidation (sadece yetki ekleme)

`should_revoke_tokens=false` durumunda:

```
identity.user.permission.v1 → permission cache invalidate (Redis DEL)
                              + WS "/queue/auth.permission.updated"
                              → frontend RTK Query cache invalidate
                              → menüler refresh, token korunur
```

Kullanıcı logout olmaz, sadece izin verici cache güncellenir.

### 3.4. Decision matrix: ne zaman hard, ne zaman soft?

| Change | Yaklaşım |
|---|---|
| Yeni rol/permission verildi | Soft (cache invalidate) |
| Rol/permission çekildi | **Hard** (token revoke) |
| Tenant assignment değişti | **Hard** |
| Scope değişti (eklendi) | Soft |
| Scope çekildi | **Hard** (yeni session daha az kaynak görür) |
| User suspended | **Hard** (logout-all + tüm token DEL) |
| User password değişti | **Hard** (tüm session revoke, mevcut session hariç opsiyonel) |
| Common permission değişti | **Tüm tenant'ta hard** (geniş etki) |

### 3.5. /me/permissions endpoint

```
GET /api/v1/me/permissions
  Response:
  {
    "computed_at": "2026-05-27T10:00:00Z",
    "tenant_id": "...",
    "permissions": ["student:read", "attendance:write", ...],
    "scope": { "type": "CLASS", "ids": [...] }
  }
```

Cache-Control: `max-age=60` (browser cache 1dk) + frontend RTK Query 5dk.
ETag desteklenir (304 Not Modified hız).

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Kafka topic yapılandırması

- Topic: `identity.user.permission.v1`
- Partition key: `user_id` (sıralı işleme aynı user için)
- Retention: 7 gün (audit kuyruğu zaten ayrı)
- Consumer group: per-service (`academic-perm-invalidator`, `finance-perm-invalidator`, …)

### 4.2. Servis-içi cache invalidation

Her servisin **lokal** permission cache'i var (cache-redis cluster):
```
Key: user:permissions:{uid}:{tenantId}
TTL: 10dk
```

Consumer event'i alınca:
```java
@KafkaListener(topics = "identity.user.permission.v1")
public void on(PermissionChangedEvent e) {
    String key = "user:permissions:" + e.userId() + ":" + e.tenantId();
    cache.delete(key);
    // Lokal in-memory cache (Caffeine) varsa da invalidate
    localCache.invalidate(e.userId());
}
```

### 4.3. WebSocket "force re-login" sinyali

`/queue/auth.revoked` user-specific kanal:
```
{
  "type": "FORCE_LOGOUT",
  "reason": "permission_revoked",
  "at": "2026-05-27T10:01:23Z"
}
```

Frontend handler:
```ts
ws.subscribe('/user/queue/auth.revoked', (msg) => {
  store.dispatch(authActions.forceLogout(msg.reason));
});
```

### 4.4. Idempotency

Aynı event birden çok kez gelirse (Kafka at-least-once) cache silme zaten idempotent. Token revoke (`HSET status REVOKED`) de idempotent.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

| Alternatif | Tartışma |
|---|---|
| **Sadece TTL bekle (event yok)** | 10dk pencere; saldırı için yeter. **Elendi.** |
| **Redis pub/sub ile invalidation** | Persistence yok; pod restart sırasında event kaybolur. **Elendi.** |
| **Kafka event (durable, retry)** | ✓ Lumix seçimi. |
| **Force re-login için polling** | Frontend her N saniye `/me` check. Wasteful. **Elendi.** |
| **Force re-login için WebSocket push** | ✓ Anlık, ek round-trip yok. |
| **Permission'ı JWT'ye koy** | Stateless çekiciliği var ama JWT'yi her değişiklikte yenilemek gerek. **Lumix zaten /me/permissions ile çekiyor**, JWT küçük kalsın. |

### Trade-off'lar

- **Eventually consistent backend cache:** Event ile invalidation milisaniyeler içinde olur ama teorik olarak race window var. Token revoke ile birleşince güvenli.
- **WebSocket offline kullanıcı:** Sayfa kapalıysa WS push gitmez. **Çözüm:** kullanıcı geri döndüğünde ilk istek 401 alır, refresh attempt 401 alır, login'e gider.
- **Idempotent invalidation:** Aynı event 5 kez gelirse 5 kez DEL → no-op. Sorun yok.

## 6. Pratik örnek

### 6.1. PermissionChangePublisher

```java
@Service
@RequiredArgsConstructor
public class PermissionChangePublisher {

    private final OutboxRepository outbox;

    @Transactional
    public void publishUserPermissionChanged(UUID userId, UUID tenantId,
                                              ChangeType type,
                                              boolean shouldRevokeTokens,
                                              UUID actor) {
        outbox.save(OutboxEvent.builder()
            .eventId(UuidV7.generate())
            .topic("identity.user.permission.v1")
            .partitionKey(userId.toString())
            .occurredAt(Instant.now())
            .payload(json.write(Map.of(
                "user_id", userId,
                "tenant_id", tenantId,
                "change_type", type.name(),
                "should_revoke_tokens", shouldRevokeTokens,
                "actor", actor
            )))
            .build());
    }
}
```

### 6.2. PermissionChangedConsumer (identity-service)

```java
@Component
@RequiredArgsConstructor
public class PermissionChangedConsumer {

    private final StringRedisTemplate authRedis;
    private final StringRedisTemplate cacheRedis;
    private final TokenStore tokenStore;
    private final SessionStore sessionStore;
    private final WebSocketPushService wsPush;

    @KafkaListener(topics = "identity.user.permission.v1", groupId = "identity-self")
    public void onChange(PermissionChangedEvent e) {
        UUID userId = e.userId();
        UUID tenantId = e.tenantId();

        // 1. Permission cache invalidate
        cacheRedis.delete("user:permissions:" + userId + ":" + tenantId);

        // 2. Hard revoke?
        if (e.shouldRevokeTokens()) {
            // Tüm aktif token'ları revoke
            Set<String> jtis = authRedis.opsForSet().members("user:tokens:" + userId);
            for (String jti : jtis) {
                tokenStore.revokeAccess(UUID.fromString(jti));
            }
            // Tüm session'ları kapat
            sessionStore.logoutAll(userId);

            // WebSocket force-logout
            wsPush.sendToUser(userId, "/queue/auth.revoked",
                new ForceLogoutEvent("permission_revoked", Instant.now()));
        } else {
            // Soft: sadece bildirim
            wsPush.sendToUser(userId, "/queue/auth.permission.updated",
                new PermissionUpdatedEvent(tenantId, Instant.now()));
        }
    }
}
```

### 6.3. Diğer servislerin lokal cache invalidator'ı

```java
@Component
@RequiredArgsConstructor
public class LocalPermissionInvalidator {

    private final StringRedisTemplate cacheRedis;
    private final CacheManager springCache;

    @KafkaListener(topics = "identity.user.permission.v1", groupId = "academic-perm-inval")
    public void on(PermissionChangedEvent e) {
        cacheRedis.delete("user:permissions:" + e.userId() + ":" + e.tenantId());

        // Spring @Cacheable("user-perms") invalidate
        Cache cache = springCache.getCache("user-perms");
        if (cache != null) cache.evict(e.userId());
    }
}
```

### 6.4. /me/permissions endpoint

```java
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeController {

    private final CachedPermissionResolver resolver;
    private final ScopeResolver scopeResolver;
    private final RequestContext ctx;

    @GetMapping("/permissions")
    public ResponseEntity<EffectivePermissionsResponse> myPermissions() {
        EffectivePermissionSet eps = resolver.getEffective(ctx.userId(), ctx.tenantId());
        ScopeResult scope = scopeResolver.resolveScope(ctx.userId(), ctx.tenantId());

        String etag = sha256(eps.computedAt() + ":" + eps.allowed().hashCode());

        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePrivate())
            .eTag(etag)
            .body(new EffectivePermissionsResponse(eps, scope));
    }
}
```

### 6.5. Frontend RTK Query

```ts
// rtkQueryApi.ts
export const meApi = createApi({
  reducerPath: 'meApi',
  baseQuery: fetchBaseQuery({ baseUrl: '/api/v1/me' }),
  tagTypes: ['Permissions'],
  endpoints: (b) => ({
    getPermissions: b.query<EffectivePermissions, void>({
      query: () => 'permissions',
      providesTags: ['Permissions'],
      keepUnusedDataFor: 300, // 5dk
    }),
  }),
});

// WebSocket handler
ws.subscribe('/user/queue/auth.permission.updated', () => {
  store.dispatch(meApi.util.invalidateTags(['Permissions']));
});

ws.subscribe('/user/queue/auth.revoked', (msg) => {
  store.dispatch(authActions.forceLogout(msg.reason));
  router.replace('/login?reason=permission_revoked');
});
```

## 7. Dikkat edilecek tuzaklar

- **Sadece backend cache invalidate, frontend unutmak.** UI eski menüleri gösterir; kullanıcı şaşırır. **Çözüm:** WS push + RTK Query tag invalidate.
- **Hard/soft kararını manuel yapmak.** İnsan unutur. **Çözüm:** `ChangeType` enum'una bağlı `shouldRevokeTokens` default'ları.
- **Outbox kullanmayıp doğrudan Kafka publish.** DB commit OK, Kafka publish fail → silent inconsistency. **Kural:** outbox pattern zorunlu.
- **Partition key tutarsız.** Aynı user'a ait iki event farklı partition'a düşerse out-of-order işlenebilir. **Kural:** `partitionKey = user_id`.
- **Soft akışta token TTL ile çelişmek.** Token cache 10dk, refresh 7gün → soft akışta yetki ekleyince 10dk gecikme kabul edilebilir.
- **WebSocket bağlantısı kopukken aksiyon almak.** Frontend WS reconnect mantığı + son aksiyon'u "missed events" endpoint'inden tekrar çekmeli.
- **Force logout sırasında refresh attempt'in success'i.** Refresh endpoint hard-revoke sonrası 401 dönmek zorunda. **Kural:** refresh request'inde de Redis token status check.
- **Audit log'da actor (kim yaptı) eksik.** Compliance kabul etmez. **Kural:** payload'da `actor` zorunlu.
- **Idempotency unutmak.** Event 3 kez gelirse 3 kez WS push → kullanıcı 3 kez logout toast'u görür. **Çözüm:** event_id ile idempotency key.
- **Common permission değişiminde tüm kullanıcılar suspend olur.** Yıkıcı. **Kural:** common permission değişimi sadece system admin yetkisi ister + onaylı pipeline.

## 8. Diğer konularla ilişkisi

- [Fully Stateful Token Modeli](./stateful-token-model) — token revoke mekanizması
- [Session & Device Lifecycle](./session-device-lifecycle) — `user:tokens`, `user:sessions` set'leri
- [Hibrit RBAC + ABAC](./rbac-abac-hybrid) — permission resolution mantığı
- [Organizational Scope Resolver](./organizational-scope-resolver) — scope cache invalidation
- [Cache Invalidation](../08-caching-redis/cache-invalidation) — event-driven invalidation pattern'ı
- [Outbox Pattern (Event-Driven Architecture)](../event-driven-architecture) — DB write + Kafka publish atomicity

## 9. Daha derine inmek için

- Confluent: "Outbox Pattern for Reliable Event Publishing"
- Martin Fowler: "Cache Invalidation" essay
- Spring: [@CacheEvict reference](https://docs.spring.io/spring-framework/reference/integration/cache/annotations.html#cache-annotations-evict)
- Search keywords:
  - `event driven cache invalidation`
  - `force logout pattern saas`
  - `token revocation propagation microservices`
  - `permission cache fan out`
  - `rtk query invalidate tags`

## 10. Sözlük

- **Cache invalidation** — Cache'lenmiş verinin geçersiz kılınması.
- **Hard revoke** — Aktif token + session'ların force kapatılması; kullanıcının yeniden login olması gerekir.
- **Soft invalidation** — Sadece cache'in temizlenmesi; mevcut session korunur.
- **Force re-login** — Frontend'in zorunlu olarak login sayfasına yönlendirildiği akış.
- **Outbox pattern** — DB write ile Kafka publish'i atomic kılmak için ara tablo.
- **Partition key** — Kafka'da mesajın gideceği partition'ı belirleyen alan (sıralama garantisi).
- **Idempotency key** — Aynı işlemin tekrar gelse de tek seferlik etki yaratmasını sağlayan tanımlayıcı.
- **Decision matrix** — Olay tipine göre hangi davranışın seçileceğini tablolaştırma.
- **Self-consumer** — Bir servisin kendi yayınladığı event'i kendi tüketmesi (genelde housekeeping için).

