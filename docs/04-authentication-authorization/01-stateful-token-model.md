---
title: Fully Stateful Token Modeli
description: Lumix'in JWT (RS256) imzalı ama Redis'te durumu tutulan tam stateful authentication modelinin tasarımı, akışları ve gerekçesi.
sidebar_position: 1
---

## Bu sayfa ne anlatıyor?

Lumix'in **kimlik doğrulama modeli** baştan sona burada. Niye "JWT stateless" değil, niye **fully stateful**? Access token Redis'te ne yapıyor? Login, refresh ve logout endpoint'leri tam olarak nasıl çalışıyor? Spring Security filter chain'inde her API çağrısında neden Redis'e bakıyoruz? Bu sayfa **auth doc'larının kapı eşiği**; diğer auth sayfalarının (session lifecycle, RBAC/ABAC, scope, cookie storage) çıkış noktasıdır.

Hedef okuyucu: Lumix backend geliştiricisi (auth'a dokunan), güvenlik incelemecisi, yeni gelen mimari okuyan kişi.

## 1. Bu nedir? (Sıfırdan)

**Authentication (kimlik doğrulama)** = "sen kimsin?" sorusunun cevabı. Kullanıcı şifreyi girer, sistem doğrularsa **"evet, sen Hüseyin'sin"** der ve sonraki isteklerde bunu tanımak için bir **token** verir.

İki klasik token yaklaşımı vardır:

| Yaklaşım | Nasıl çalışır | Avantaj | Dezavantaj |
|---|---|---|---|
| **Stateless (saf JWT)** | Token imzalıdır, sunucu doğrular ve içindeki bilgilere güvenir. Sunucu hiçbir şey saklamaz. | Hızlı, ölçeklenebilir, DB'ye uğramaz | Token çalınırsa süresi dolana kadar **revoke edilemez** |
| **Stateful (session)** | Sunucuda her aktif token için kayıt vardır. Her istekte sunucu bakıp doğrular. | İstediğin an revoke, fine-grained kontrol | Her istekte storage'a bakmak gerekir |

Lumix **stateful** tarafı seçti; ama "session id string"i değil, **imzalı JWT** kullanır. Yani:

- Token **JWT (RS256 imzalı)** → frontend bunu okuyabilir, gateway local olarak imza doğrulayabilir
- Aynı token'ın **Redis'te de bir kaydı vardır** → her API çağrısında "bu token hâlâ aktif mi?" diye Redis'e bakılır

İki dünyanın iyi taraflarını birleştiren bir model. Buna "**hybrid stateful**" veya **fully stateful JWT** diyoruz.

### Günlük analoji

Otel anahtarını düşün:
- **Stateless JWT** = oda anahtarı yazılı bir kart. Resepsiyon "kart geçerli mi" diye kontrol etmez, sadece kapı kart'taki bilgiye bakar.
- **Stateful JWT (Lumix)** = oda anahtarı **+** resepsiyondaki bir liste. Otele her girişte resepsiyon "bu kart hâlâ aktif mi?" diye listeyi kontrol eder. Müşteri checkout yaparsa kart hâlâ cebinde durur ama resepsiyon listesinden silindiği için artık çalışmaz.

## 2. Hangi problemi çözüyor?

Saf stateless JWT bu acıları yaşatır:

### 2.1. Çalınan token'ı durduramıyorsun
Bir kullanıcı laptop'unu kaybetti. Token süresi 30 dakika. Bu süre boyunca saldırgan tüm API'leri çağırabilir. Sunucu "bu token revoke edildi" diyemez çünkü kayıt tutmuyor.

### 2.2. Permission değişince ne olacak?
Hüseyin'in `payment:refund` yetkisini çektin. Ama elinde bir saat geçerli token var; o yetki token'ın içinde yazıyor. Stateless modelde **token bitene kadar bekleyeceksin**. Lumix gibi finans modülü olan bir sistemde bu kabul edilemez.

### 2.3. "Tüm cihazlardan çıkış yap" (logout-all)
Kullanıcı "şüpheli aktivite" gördü, tüm session'larını sonlandırmak istiyor. Stateless modelde server-side bir liste yok ki "bu kullanıcının token'larını invalidate et" diyesin.

### 2.4. Session limit
Bir kullanıcının aynı anda en fazla 3 cihazda açık olmasını istiyorsun. Stateless'de sayamazsın.

### 2.5. Audit ve forensic
"O olay anında hangi cihazdan, hangi IP'den, kim login'di?" Bu soruya stateless modelde cevap yok.

Lumix'in **finans + eğitim (öğrenci verisi, sınav notu)** kombinasyonu, bu acıların hepsini "kabul edilemez" yapıyor. O yüzden stateful seçildi.

## 3. Nasıl çözüyor? (Mekanizma + diyagram)

### 3.1. Üç token, üç katman

```
┌─────────────────────────────────────────────────────────────────┐
│                      LUMIX TOKEN MODELİ                         │
└─────────────────────────────────────────────────────────────────┘

      ┌────────────┐     ┌────────────┐      ┌────────────┐
      │  ACCESS    │     │  REFRESH   │      │  SESSION   │
      │  TOKEN     │     │  TOKEN     │      │  RECORD    │
      │  (JWT)     │     │  (opaque)  │      │  (Redis)   │
      ├────────────┤     ├────────────┤      ├────────────┤
      │ 15 dk      │     │ 7-30 gün   │      │ Sliding +  │
      │ RS256      │     │ SHA-512    │      │ absolute   │
      │ jti var    │     │ rotate     │      │ TTL        │
      │            │     │            │      │            │
      │ Redis'te   │     │ Redis'te   │      │ Cihaz +    │
      │ status     │     │ hash       │      │ IP + UA    │
      │ ACTIVE/    │     │ tutulur    │      │            │
      │ REVOKED    │     │            │      │            │
      └────────────┘     └────────────┘      └────────────┘
            │                  │                    │
            └──────────┬───────┴────────────────────┘
                       ▼
              Redis (auth cluster — AOF persistent)
```

### 3.2. Login akışı (yüksek seviye)

```
1. User → POST /auth/login (username, password)
                 │
                 ▼
2. identity-service:
   - Argon2id ile şifre doğrula
   - User'ı bul
   - Permission/scope yükle
   - jti üret (UUID v7), session_id üret
   - JWT access token imzala (RS256, claims: sub, jti, sid, tenant_id)
   - Refresh token üret (opaque, 64 byte random)
   - Refresh token'ın SHA-512 hash'ini al
                 │
                 ▼
3. Redis'e yaz (TX):
   access:{jti}        → { status: ACTIVE, sid, user_id, exp }     TTL=15dk
   refresh:{hash}      → { sid, user_id, family_id, exp }          TTL=7gün
   session:{sid}       → { user_id, device, ip, ua, created_at,
                            last_seen_at, status: ACTIVE }         TTL=30gün
   user:sessions:{uid} → SADD sid                                  (Set)
                 │
                 ▼
4. Response:
   - Body: { access_token: "eyJ..." }
   - Set-Cookie: refresh_token=<opaque>; HttpOnly; Secure;
                 SameSite=Strict; Path=/auth/refresh
```

### 3.3. Her API çağrısı

Bu **en kritik** akış. Her isteğin başında Spring Security filter çalışır:

```
HTTP Request → Kong (JWT signature validate) → Service Pod
                                                       │
                                                       ▼
                                  SecurityFilterChain (sırayla)
                                  ┌──────────────────────────┐
                                  │ 1. JWT parse + signature │
                                  │    (lokal, JWKS cached)  │
                                  ├──────────────────────────┤
                                  │ 2. exp/nbf/iss check     │
                                  ├──────────────────────────┤
                                  │ 3. Redis GET access:{jti}│
                                  │    → status == ACTIVE?   │
                                  │    yoksa 401             │
                                  ├──────────────────────────┤
                                  │ 4. Redis GET session:{sid│
                                  │    → status == ACTIVE?   │
                                  │    last_seen güncelle    │
                                  ├──────────────────────────┤
                                  │ 5. MDC: correlation, sub,│
                                  │    tenant_id, jti, sid   │
                                  ├──────────────────────────┤
                                  │ 6. DB session: SET LOCAL │
                                  │    app.tenant_id         │
                                  └──────────────────────────┘
                                                       │
                                                       ▼
                                                Controller
```

İki Redis okuması — `access:{jti}` ve `session:{sid}`. Ortalama 0.3-0.6 ms (lokal Redis Sentinel'a pipeline ile). Bu **maliyet, revoke kontrolünün karşılığıdır**.

### 3.4. Refresh akışı

```
1. Access token expired (401) → Frontend → POST /auth/refresh
                                            (Cookie: refresh_token=opaque)
                                                       │
                                                       ▼
2. identity-service:
   - hash = SHA-512(opaque)
   - Redis GET refresh:{hash}
   - Yoksa → 401, son
   - Varsa: family_id'yi al
                 │
                 ▼
3. ROTATION:
   - Eski refresh:{hash}'i DEL
   - Yeni refresh token + jti üret
   - Yeni access JWT imzala
   - Yeni refresh:{new_hash} yaz (aynı family_id ile)
   - Yeni access:{new_jti} yaz
   - session:{sid}.last_refresh_at güncelle
                 │
                 ▼
4. Response:
   - Body: { access_token: "eyJ..." }
   - Set-Cookie: refresh_token=<yeni opaque>
```

**Replay detection:** Aynı refresh token ikinci kez gelirse (silinmiş ama saldırgan yeniden gönderdi), tüm `family_id`'ye ait token'lar invalidate edilir. "Bu cihaz uzlaşmış" sinyali.

### 3.5. Logout akışı

```
POST /auth/logout
  │
  ▼
1. access:{jti} → status: REVOKED (veya DEL)
2. refresh:{hash} → DEL
3. session:{sid} → status: REVOKED
4. user:sessions:{uid} → SREM sid
5. Cookie: Set-Cookie: refresh_token=; Max-Age=0
6. Kafka event: AuthSessionRevoked (audit için)
```

**Logout-all** ise:
```
SMEMBERS user:sessions:{uid} → tüm sid'ler
  → Her sid için yukarıdaki adımları yap
```

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Servisler arası rol dağılımı

| Servis | Görev |
|---|---|
| **identity-service** | Login, refresh, logout endpoint'leri. Token + session yaratım/revoke. Permission resolution. |
| **Kong Gateway** | JWT signature validate (JWKS endpoint). Geçmezse 401. Header'ları downstream'e iletir. |
| **Diğer microservice'ler** | Spring Security filter ile Redis kontrol (status check). Permission/scope enforcement. |

Yani **Kong** ilk hızlı imza filter'i, **microservice** ise Redis-tabanlı revoke filter'i. İki katman.

### 4.2. JWT claim yapısı (Lumix standardı)

```json
{
  "iss": "lumix-identity",
  "sub": "user-uuid-v7",
  "iat": 1716800000,
  "exp": 1716800900,
  "jti": "access-jti-uuid-v7",
  "sid": "session-id-uuid-v7",
  "installation_id": "inst-uuid",
  "tenant_id": "tenant-uuid",
  "tenant_ids": ["t1", "t2"],
  "auth_method": "custom",
  "token_type": "access"
}
```

Önemli: **permission listesi JWT içinde YOK**. Çünkü permission değişimi her token'ı kirlerdi. Permission `/me/permissions` ile çekilir ve servis-içi cache'lenir.

### 4.3. Redis key tasarımı (özet)

| Key pattern | Tip | TTL | Açıklama |
|---|---|---|---|
| `access:{jti}` | Hash | 15dk | Access token status + metadata |
| `refresh:{sha512}` | Hash | 7-30gün | Refresh token state |
| `session:{sid}` | Hash | 30gün (absolute) | Session bilgisi |
| `user:sessions:{uid}` | Set | yok (manual) | Kullanıcının aktif tüm session id'leri |
| `refresh:family:{family_id}` | Set | 30gün | Replay detection için |

Detay: bkz. [Session & Device Lifecycle](./02-session-device-lifecycle.md).

### 4.4. Hangi Redis cluster?

Lumix **iki ayrı Redis Sentinel cluster'ı** çalıştırır:

- **auth-redis** → AOF persistence, `noeviction` policy. Token kaybolmaz.
- **cache-redis** → persistence kapalı, `allkeys-lfu`. Soyulabilir veri.

Token + session **auth-redis**'tedir. Detay: [Redis Sentinel Topology](../08-caching-redis/02-redis-sentinel-topology.md).

### 4.5. Spring Security konfigürasyonu

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            RedisTokenStatusFilter redisFilter,
                                            JwtAuthFilter jwtFilter) throws Exception {
        return http
            .csrf(csrf -> csrf.disable()) // Kong'ta + SameSite ile yönetiyoruz
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/login", "/auth/refresh", "/actuator/health").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(redisFilter, JwtAuthFilter.class)
            .build();
    }
}
```

JWT imza Spring `JwtAuthenticationConverter` ile lokal doğrulanır; Redis kontrolü ayrı filter'da.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

| Alternatif | Neden elenmedi/elendi |
|---|---|
| **Pure stateless JWT** | Revoke kontrolü yok; "üretim kazası" senaryoları yönetilemiyor. **Elendi.** |
| **Pure session id (opaque)** | JWT'nin downstream'e tenant/jti claim'i hazır taşıma avantajı kayboluyor. Gateway her şeye Redis sormak zorunda. **Elendi.** |
| **JWT + revoke blacklist (kısa TTL access)** | İkisinin ortası; ama Lumix permission değişimi/logout-all gibi feature'lar için her zaman storage gerektirir. Yarı-yarıya gelmektense **tam stateful** seçildi. |
| **Opaque + introspection (OAuth2 Introspection)** | Tasarım olarak güzel ama her servis için ekstra round-trip. Lumix doğrudan Redis check eder, daha hızlı. |
| **Fully stateful (Lumix)** | ✓ **Seçildi.** Latency biraz artar ama security/operational fayda baskın. |

### Trade-off'lar (kabul ettiğimiz)

- **Latency:** Her istek 0.3-0.6 ms ekstra Redis round-trip. Kabul.
- **Redis = SPOF:** Auth-redis düşerse hiçbir istek geçemez. Bu yüzden Sentinel + AOF + monitoring. Detay: [Redis Sentinel](../08-caching-redis/02-redis-sentinel-topology.md).
- **Storage cost:** Her aktif session ~500 byte Redis. 100k aktif kullanıcı için ~50 MB. Önemsiz.

### Kararı tekrar değerlendirme zamanı

- Eğer ileride **edge auth** (Cloudflare Workers + JWT validate) tipi bir mimariye gidersek stateless'a kısmen dönmek mantıklı olabilir.
- Şu an Lumix self-host + finans + KVKK → stateful kalıyor.

## 6. Pratik örnek

### 6.1. Login endpoint (Spring Boot)

```java
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpReq) {

        DeviceInfo device = DeviceInfo.builder()
            .ip(extractIp(httpReq))
            .userAgent(httpReq.getHeader("User-Agent"))
            .fingerprint(httpReq.getHeader("X-Device-Fingerprint"))
            .build();

        TokenPair tokens = authService.login(request.username(),
                                              request.password(),
                                              device);

        ResponseCookie refreshCookie = ResponseCookie
            .from("refresh_token", tokens.refreshTokenOpaque())
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/auth")
            .maxAge(Duration.ofDays(7))
            .build();

        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
            .body(new LoginResponse(tokens.accessToken(), tokens.expiresInSeconds()));
    }
}
```

### 6.2. AuthService.login (pseudo-code çekirdek)

```java
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;       // Argon2id
    private final JwtSigner jwtSigner;                    // RS256
    private final TokenStore tokenStore;                  // Redis facade
    private final SessionStore sessionStore;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenPair login(String username, String password, DeviceInfo device) {
        User user = userRepo.findActiveByUsername(username)
            .orElseThrow(() -> new BadCredentialsException("invalid_credentials"));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BadCredentialsException("invalid_credentials");
        }

        UUID sessionId = UuidV7.generate();
        UUID jti = UuidV7.generate();
        UUID familyId = UuidV7.generate();

        Instant now = Instant.now();
        Instant accessExp = now.plus(Duration.ofMinutes(15));
        Instant refreshExp = now.plus(Duration.ofDays(7));

        // 1. JWT imzala
        String accessJwt = jwtSigner.sign(JwtClaims.builder()
            .subject(user.getId().toString())
            .jti(jti.toString())
            .sid(sessionId.toString())
            .tenantId(user.getActiveTenantId())
            .tenantIds(user.getTenantIds())
            .installationId(user.getInstallationId())
            .authMethod("custom")
            .iat(now)
            .exp(accessExp)
            .build());

        // 2. Refresh token (opaque)
        byte[] refreshBytes = new byte[64];
        secureRandom.nextBytes(refreshBytes);
        String refreshOpaque = Base64Url.encode(refreshBytes);
        String refreshHash = Sha512.hex(refreshOpaque);

        // 3. Session + token state Redis'e
        sessionStore.create(SessionRecord.builder()
            .sid(sessionId)
            .userId(user.getId())
            .device(device)
            .createdAt(now)
            .lastSeenAt(now)
            .absoluteExpiresAt(now.plus(Duration.ofDays(30)))
            .status(ACTIVE)
            .build());

        tokenStore.saveAccess(jti, sessionId, user.getId(), accessExp);
        tokenStore.saveRefresh(refreshHash, sessionId, familyId,
                               user.getId(), refreshExp);
        tokenStore.addToUserSessionSet(user.getId(), sessionId);

        return new TokenPair(accessJwt, refreshOpaque,
                             (int) Duration.between(now, accessExp).toSeconds());
    }
}
```

### 6.3. Redis status check filter

```java
@Component
@RequiredArgsConstructor
public class RedisTokenStatusFilter extends OncePerRequestFilter {

    private final TokenStore tokenStore;
    private final SessionStore sessionStore;

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                     HttpServletResponse res,
                                     FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            chain.doFilter(req, res);
            return;
        }

        Jwt jwt = (Jwt) auth.getPrincipal();
        String jti = jwt.getId();
        String sid = jwt.getClaimAsString("sid");

        // Pipeline: tek round-trip iki GET
        TokenStatus tokenStatus = tokenStore.getAccessStatus(jti);
        SessionStatus sessionStatus = sessionStore.getStatusAndTouch(sid);

        if (tokenStatus != ACTIVE) {
            unauthorized(res, "token_revoked");
            return;
        }
        if (sessionStatus != ACTIVE) {
            unauthorized(res, "session_revoked");
            return;
        }

        chain.doFilter(req, res);
    }

    private void unauthorized(HttpServletResponse res, String code) throws IOException {
        res.setStatus(401);
        res.setContentType("application/problem+json");
        res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"code\":\"" + code + "\"}");
    }
}
```

### 6.4. application.yml

```yaml
lumix:
  auth:
    access-token:
      ttl: PT15M
      algorithm: RS256
      issuer: lumix-identity
      jwks-uri: http://identity-service:8080/.well-known/jwks.json
    refresh-token:
      ttl: P7D
      hash-algorithm: SHA-512
      rotation: true
    session:
      idle-timeout: PT30M
      absolute-timeout: P30D
    cookie:
      name: refresh_token
      same-site: Strict
      secure: true
      http-only: true
      path: /auth
```

## 7. Dikkat edilecek tuzaklar

- **Access token Redis'e yazılmadan response dönmek.** Login işleminin sonunda yazma + dönüş atomic değilse, kullanıcı token alır ama Redis'te kayıt yok → bütün istekler 401. **Çözüm:** Önce Redis'e yaz, sonra response dön.
- **JWT exp varken Redis check etmemek.** "İmza geçerli ve süresi dolmamış, geçerlidir" yanılgısı. **Lumix kuralı:** her istekte Redis check zorunlu.
- **Refresh token'ı body'de döndürmek.** XSS ile çalınır. **Kural:** sadece httpOnly cookie.
- **Refresh rotation yapmamak.** Saldırgan refresh token çaldıysa, victim de saldırgan da aynı token'ı sonsuza kadar kullanır. **Çözüm:** her refresh'te eskisini sil, yeni ver, replay detect et.
- **Permission listesini JWT'ye koymak.** Permission değişince token yenisiyle değişmez; sadece refresh anında değişir. **Lumix kuralı:** permission JWT'de YOK; `/me/permissions` endpoint'inden çekilir, kısa TTL ile servis-içi cache'lenir, permission-change event'inde invalidate olur.
- **Logout'ta sadece cookie silmek.** Saldırgan token'ın bir kopyasını aldıysa cookie silmek işe yaramaz. **Kural:** Redis'te de revoke etmek zorunlu.
- **JWKS cache uzun TTL.** Imza anahtarını rotate ederken eski JWKS'i cache'leyen servisler yeni token'ları reject eder. **Kural:** JWKS cache 5-10 dk, ETag ile.
- **Auth Redis'te `allkeys-lru`.** Token Redis'te eviction'a uğrarsa active kullanıcılar random çıkış yer. **Kural:** auth-redis'te `noeviction`.
- **JWT'de `tenant_id` claim yok.** Tenant context kaybolur, RLS bozulur. **Kural:** her access token'da `tenant_id` (veya multi-tenant için `tenant_ids`) zorunlu.

## 8. Diğer konularla ilişkisi

- [Session & Device Lifecycle](./02-session-device-lifecycle.md) — Redis key tasarımı ve session TTL detayı
- [Keycloak Entegrasyonu](./03-keycloak-integration.md) — opsiyonel IdP — Custom flow'un yanına nasıl oturuyor
- [Hibrit RBAC + ABAC](./04-rbac-abac-hybrid.md) — permission resolution
- [Permission Change & Revoke Flow](./06-permission-change-revoke-flow.md) — permission değişince ne olur
- [httpOnly Cookie Storage](./07-httponly-cookie-storage.md) — refresh token saklama detayı
- [Installation/Tenant/Scope](../01-tenancy-and-domain-model/01-installation-tenant-scope.md) — tenant claim nereden geliyor
- [Redis Sentinel Topology](../08-caching-redis/02-redis-sentinel-topology.md) — auth-redis cluster'ı

## 9. Daha derine inmek için

- IETF: [RFC 7519 — JSON Web Token](https://datatracker.ietf.org/doc/html/rfc7519)
- IETF: [RFC 7515 — JWS](https://datatracker.ietf.org/doc/html/rfc7515)
- OWASP: [JWT Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_for_Java_Cheat_Sheet.html)
- OWASP: [Session Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)
- Spring: [Spring Security Reference — OAuth2 Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- Search keywords:
  - `stateful jwt revocation pattern`
  - `jwt vs opaque token tradeoffs`
  - `refresh token rotation reuse detection`
  - `token introspection vs blacklist`
  - `argon2id password hashing parameters`

## 10. Sözlük

- **JWT (JSON Web Token)** — İmzalı, üç parçalı (header.payload.signature) token formatı. Lumix RS256 kullanır.
- **RS256** — RSA SHA-256 asimetrik imza algoritması. Private key ile imza, public key ile doğrulama.
- **jti** — JWT ID claim. Tekil token tanımlayıcısı; Redis key'i.
- **sid** — Session ID; bir token'ın bağlı olduğu session.
- **Opaque token** — İçeriği client tarafından okunamayan, sadece sunucuda anlam taşıyan rastgele string. Lumix refresh token böyle.
- **Fully stateful auth** — Access + refresh + session, hepsinin server-side store'da (Redis) tutulduğu auth modeli.
- **Refresh rotation** — Her refresh isteğinde yeni refresh token üretip eskisini geçersiz kılma.
- **Replay detection** — Daha önce kullanılmış refresh token'ın tekrar gelmesi durumunda saldırı varsayıp ailenin tamamını invalidate etme.
- **JWKS (JSON Web Key Set)** — Public key'lerin JSON formatında yayınlandığı endpoint. Lumix `/.well-known/jwks.json`.
- **Argon2id** — Modern, memory-hard password hashing algoritması. Lumix login password'leri için bunu kullanır.

