---
title: Keycloak Entegrasyonu (Opsiyonel IdP)
description: Lumix custom login varsayılan, müşteri talep ederse Keycloak federated identity ile devreye alınır. İki AuthenticationProvider, tenant config flag, JIT user provisioning.
sidebar_position: 3
---

## Bu sayfa ne anlatıyor?

Lumix'te kimlik doğrulamanın **iki yolu** vardır: (1) **Custom Login** (varsayılan, identity-service kendi yapar) ve (2) **Keycloak** (opsiyonel, federated IdP). Bu sayfa Keycloak'un ne olduğunu, **niye opsiyonel** tutulduğunu, Spring Security'de iki provider'ın yan yana nasıl yaşadığını ve tenant başına `auth_method` flag'inin nasıl seçim yaptığını anlatır.

## 1. Bu nedir? (Sıfırdan)

**Keycloak** = açık kaynak Identity & Access Management sunucusu. Kullanıcı yönetimi, login UI, SSO, OAuth 2.0 / OpenID Connect (OIDC) / SAML 2.0 protokolleri ve federated identity sağlar.

Federated identity ne demek? "**Kullanıcı kimliği başka bir yerde, biz oradan tanıyoruz**". Örnek: kurumun mevcut Active Directory'si var. Lumix kendisi kullanıcı şifresi tutmaz; Keycloak AD'ye bağlanır, kullanıcı oradan login olur, Keycloak Lumix'e "evet bu Hüseyin" der.

### Günlük analoji

Üniversiteye geldin, çeşitli binalara girmen lazım (kütüphane, kafeterya, lab). İki model var:
- **Custom (Lumix default):** Her bina ayrı kimliğini ister; her birine ayrı kart yaptırırsın.
- **Keycloak / Federated:** Tek "öğrenci kimlik kartın" var, hepsi onu kabul ediyor. Kartı üniversitenin merkezi öğrenci işleri yapıyor.

Keycloak öğrenci işleri rolünde. Bina'lar ise Lumix uygulamaları.

### Protokol nedir? — OIDC kısa hikayesi

- **OAuth 2.0** = "yetki verme" protokolü ("şu app benim Twitter'ıma erişebilir mi?")
- **OpenID Connect (OIDC)** = OAuth 2.0 üzerine bindirilen **kimlik** katmanı ("kullanıcı kim?"). Lumix Keycloak'u OIDC ile entegre eder.

Tipik OIDC flow:
1. Frontend, Keycloak'a yönlendirir (`/realms/lumix/protocol/openid-connect/auth`)
2. Kullanıcı Keycloak login UI'ında doğrulanır
3. Keycloak `code` döner
4. Backend bu code'u `access_token` + `id_token`'a çevirir
5. `id_token` içinde `sub`, `email`, `groups` vs. var

## 2. Hangi problemi çözüyor?

### 2.1. Mevcut kurumsal kimlik
Bir okul kurumu zaten AD/LDAP kullanıyor. "Personel zaten oraya login oluyor, Lumix için ikinci hesap yapmasınlar." → Keycloak AD'ye bağlanır, SSO sağlar.

### 2.2. Birden fazla uygulamada SSO
Müşteri kurumunun Lumix dışında 3 farklı iç uygulaması var (HR, BIM, ticket). Hepsi Keycloak ile SSO. Lumix da aynı IdP'ye bağlanır → tek login.

### 2.3. Compliance gereksinimi
Bazı müşteriler "kullanıcı şifresi sizin DB'nizde durmasın" diye yazılı talep eder. Keycloak ile şifre asla Lumix DB'sine değmez.

### 2.4. MFA / WebAuthn merkezi yönetim
Müşteri kendi MFA stratejisini Keycloak'ta tek noktadan yönetir. Lumix bunu kendisi reimplement etmez.

### Ama neden default değil?
- Keycloak başka bir bileşen demek → daha karmaşık deployment + bakım.
- Küçük müşteri için "okul yöneticisi 50 öğretmen" senaryosu Keycloak overkill.
- Lumix custom login zaten production-grade (Argon2id + brute-force lock + MFA hook).

Bu yüzden Lumix **custom login default**, Keycloak **müşteri talep ederse aktif**.

## 3. Nasıl çözüyor? (Mekanizma + diyagram)

### 3.1. İki yolun yan yana yaşaması

```
┌──────────────────────────────────────────────────────────────────┐
│                       LUMIX IDENTITY SERVICE                     │
│                                                                  │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                Spring Security Filter Chain              │   │
│   │                                                          │   │
│   │   Tenant config: auth_method = ?                         │   │
│   │           │                                              │   │
│   │           ├── "custom"    ──►  CustomAuthProvider       │   │
│   │           │                    (DB password check)       │   │
│   │           │                                              │   │
│   │           └── "keycloak"  ──►  OIDC redirect            │   │
│   │                                    │                     │   │
│   │                                    ▼                     │   │
│   │                                  Keycloak realm          │   │
│   │                                    │                     │   │
│   │                                    ▼                     │   │
│   │                              id_token verify             │   │
│   │                                    │                     │   │
│   │                                    ▼                     │   │
│   │                              JIT user provision          │   │
│   │                              (lokal DB'ye user yarat     │   │
│   │                               veya bul + sync)           │   │
│   │           │                                              │   │
│   │           ▼                                              │   │
│   │      ORTAK YOL: Lumix access JWT + Redis state +         │   │
│   │                 session + refresh cookie (her ikisinde)  │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

**Önemli ilke:** Keycloak'un id_token'ı API isteklerinde kullanılmaz. Keycloak sadece **login adımındadır**. Sonrasında Lumix kendi access JWT'sini ve Redis-tabanlı state'ini kullanır. Yani fully stateful model her iki yol için de geçerlidir.

### 3.2. Keycloak login flow (kod akışı)

```
1. Frontend → GET /auth/login/keycloak?tenant=...
   identity-service tenant_config oku → realm bilgisi
   redirect: https://keycloak.example.com/realms/{realm}/protocol/openid-connect/auth
             ?client_id=lumix
             &redirect_uri=https://lumix.example.com/auth/callback/keycloak
             &response_type=code
             &scope=openid+profile+email+groups
             &state=<csrf-token>

2. Browser → Keycloak login UI → kullanıcı şifre + MFA

3. Keycloak → GET /auth/callback/keycloak?code=...&state=...
   identity-service:
     - state CSRF check
     - code → token exchange (Keycloak'a back-channel POST)
     - id_token signature verify (Keycloak JWKS)
     - sub, email, preferred_username, groups oku

4. JIT provisioning:
   - DB'de user var mı? (`external_id = id_token.sub`)
     - YOK → INSERT user (status=ACTIVE, external_id, email, ...)
     - VAR → UPDATE email/groups sync
   - Groups → role mapping (Keycloak groups → Lumix roles)
   - tenant_id seçimi (kullanıcının tenant assignment'larından)

5. Lumix kendi access JWT + refresh + session yarat
   (CustomFlow ile birebir aynı: 02-session-device-lifecycle)

6. Response: refresh cookie + access body
   Frontend Lumix native ile devam eder
```

### 3.3. Tenant config seçimi

```sql
CREATE TABLE tenant_config (
    tenant_id     UUID PRIMARY KEY,
    auth_method   TEXT NOT NULL DEFAULT 'custom',  -- 'custom' | 'keycloak'
    keycloak_realm TEXT,
    keycloak_client_id TEXT,
    keycloak_client_secret_ref TEXT,    -- Vault path
    group_to_role_mapping JSONB,
    require_mfa BOOLEAN DEFAULT false,
    CONSTRAINT auth_method_check CHECK (auth_method IN ('custom', 'keycloak'))
);
```

`identity-service` login endpoint:

```
POST /auth/login
Body: { username, password }
       │
       ▼
1. Lookup username → user → tenant_id
2. config = tenantConfigService.get(tenant_id)
3. if (config.auth_method == 'custom') → CustomAuthProvider
   else if (config.auth_method == 'keycloak') → 400 BadRequest:
        "Bu tenant Keycloak kullanıyor. /auth/login/keycloak'a yönlendir."
```

Veya daha kullanıcı dostu: frontend tenant seçimi sırasında `auth_method` öğrenir, doğrudan ilgili akışa yönlendirir.

### 3.4. Mixed mode tenant

Bir tenant içinde **bazı kullanıcılar Keycloak, bazıları local** olabilir:
- Personel → Keycloak (AD ile sync)
- Veliler → Custom (AD'de değiller, Lumix self-register ediyorlar)

Bu durumda `users.external_id IS NOT NULL` ise Keycloak akışına, NULL ise custom akışa gider.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Versiyon ve deployment

- **Keycloak 25.x** (Quarkus distribution)
- Her installation içinde **opsiyonel** olarak çalışır (Helm chart `keycloak.enabled=true` ile)
- Tek realm: `lumix-{installation_id}`
- Client: confidential, client_id = `lumix-backend`, client_secret Vault'ta

### 4.2. Configuration source-of-truth

Realm export edilen `lumix-realm.json` ArgoCD ile yönetilir (GitOps). Manual UI değişikliği yasak. Realm import operatörü (Keycloak Operator) ile uygulanır.

### 4.3. Spring konfigürasyonu

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          keycloak:
            client-id: lumix-backend
            client-secret: ${KEYCLOAK_CLIENT_SECRET}    # Vault'tan inject
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/auth/callback/keycloak"
            scope: openid,profile,email,groups
        provider:
          keycloak:
            issuer-uri: https://keycloak.example.com/realms/lumix-inst-001
            user-name-attribute: preferred_username

lumix:
  identity:
    keycloak:
      enabled: true
      jit-provisioning: true
      group-to-role-mapping:
        "/lumix/admins": "ADMIN"
        "/lumix/teachers": "TEACHER"
        "/lumix/parents": "PARENT"
```

### 4.4. Group ↔ Role mapping

Keycloak'ta gruplar (`/lumix/teachers`) Lumix rollerine map'lenir. Login anında resolve edilir, lokal DB'ye yazılır. Yeniden login'de re-sync.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

| Alternatif | Tartışma |
|---|---|
| **Tek yol: Keycloak (zorunlu)** | Küçük müşteri için Keycloak kurmak overkill. **Elendi.** |
| **Tek yol: Custom (Keycloak yok)** | Kurumsal müşteri AD entegrasyonu isteyince yapacak bir şey yok. **Elendi.** |
| **Auth0 / Okta (SaaS IdP)** | Self-host şartı + müşteri verisinin 3.party'de yaşamaması gereği → kabul edilemez. **Elendi.** |
| **Spring Authorization Server** | Lightweight, ama UI + realm management + admin UI yok. Custom yazmamız gerekirdi. **Elendi.** |
| **Custom (default) + Keycloak (opsiyonel)** | ✓ İki dünya. Müşteri seçer. |

### Trade-off'lar

- **İki provider = iki test path:** CI'da hem custom hem keycloak login E2E test edilir.
- **Keycloak ayrı bir bileşen demek:** Helm chart, monitoring (Prometheus exporter), backup (realm export script) eklenir.
- **Realm config drift riski:** Manual UI değişikliği yapan müşteri admin'i config'i bozar. Çözüm: realm read-only, kullanıcı yönetimi Lumix Customer Admin Panel'den.

## 6. Pratik örnek

### 6.1. SecurityConfig (iki provider'lı)

```java
@Configuration
@EnableWebSecurity
public class IdentitySecurityConfig {

    @Bean
    public SecurityFilterChain api(HttpSecurity http,
                                    CustomLoginFilter customFilter,
                                    OAuth2LoginSuccessHandler oidcHandler) throws Exception {
        return http
            .securityMatcher("/api/**", "/auth/**")
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/auth/login", "/auth/refresh",
                                  "/auth/login/keycloak", "/auth/callback/keycloak").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(customFilter, UsernamePasswordAuthenticationFilter.class)
            .oauth2Login(oauth -> oauth
                .successHandler(oidcHandler)
                .failureHandler(new SimpleUrlAuthenticationFailureHandler("/login?error=oidc")))
            .build();
    }
}
```

### 6.2. OAuth2 callback → Lumix token

```java
@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final UserProvisioningService provisioning;
    private final AuthService authService;
    private final TenantConfigService tenantConfig;
    private final ObjectMapper json;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest req,
                                          HttpServletResponse res,
                                          Authentication auth) throws IOException {
        OAuth2User oauth2User = (OAuth2User) auth.getPrincipal();
        OidcIdToken idToken = ((OidcUser) oauth2User).getIdToken();

        String externalId = idToken.getSubject();
        String email = idToken.getEmail();
        String preferredUsername = idToken.getPreferredUsername();
        @SuppressWarnings("unchecked")
        List<String> groups = (List<String>) idToken.getClaims().getOrDefault("groups", List.of());

        // JIT provisioning
        User user = provisioning.findOrCreateExternalUser(
            externalId, email, preferredUsername, groups);

        DeviceInfo device = DeviceInfo.fromRequest(req);

        // Lumix native token & session yarat (custom login ile aynı)
        TokenPair tokens = authService.issueTokensFor(user, device, "keycloak");

        // Refresh cookie + access JSON
        Cookies.writeRefresh(res, tokens.refreshTokenOpaque());
        res.setContentType("application/json");
        json.writeValue(res.getOutputStream(),
            new LoginResponse(tokens.accessToken(), tokens.expiresInSeconds()));
    }
}
```

### 6.3. JIT user provisioning

```java
@Service
@RequiredArgsConstructor
public class UserProvisioningService {

    private final UserRepository userRepo;
    private final RoleResolver roleResolver;

    @Transactional
    public User findOrCreateExternalUser(String externalId,
                                          String email,
                                          String username,
                                          List<String> groups) {
        return userRepo.findByExternalId(externalId)
            .map(existing -> updateFromIdp(existing, email, username, groups))
            .orElseGet(() -> createFromIdp(externalId, email, username, groups));
    }

    private User createFromIdp(String externalId, String email,
                                 String username, List<String> groups) {
        Set<Role> roles = roleResolver.mapGroupsToRoles(groups);
        User user = User.builder()
            .id(UuidV7.generate())
            .externalId(externalId)
            .username(username)
            .email(email)
            .passwordHash(null)              // Keycloak user, lokal şifre yok
            .authMethod("keycloak")
            .status(UserStatus.ACTIVE)
            .roles(roles)
            .build();
        return userRepo.save(user);
    }

    private User updateFromIdp(User existing, String email,
                                 String username, List<String> groups) {
        existing.setEmail(email);
        existing.setUsername(username);
        existing.setRoles(roleResolver.mapGroupsToRoles(groups));
        existing.setLastIdpSyncAt(Instant.now());
        return existing;
    }
}
```

### 6.4. Tenant config karar dağıtımı

```java
@Service
@RequiredArgsConstructor
public class LoginRouter {

    private final TenantConfigService tenantConfig;
    private final CustomAuthService customAuth;

    public LoginResponse login(LoginRequest req, DeviceInfo device) {
        TenantConfig cfg = tenantConfig.findForUsername(req.username());
        if (cfg.authMethod() == AuthMethod.KEYCLOAK) {
            throw new RedirectToIdpException(
                "/auth/login/keycloak?tenant=" + cfg.tenantId());
        }
        return customAuth.login(req, device);
    }
}
```

## 7. Dikkat edilecek tuzaklar

- **Keycloak id_token'ı API auth header olarak kabul etmek.** Lumix kendi access JWT'sini kullanır; Keycloak token'ı sadece login sırasında geçerli. **Kural:** API endpoint'leri Lumix issuer JWT'sini ister, Keycloak issuer reject.
- **Realm config'i UI'dan değiştirmek.** Drift olur, ArgoCD bir sonraki sync'te overwrite eder. **Kural:** GitOps zorunlu.
- **JIT provisioning'de tenant_id atamayı unutmak.** Yeni Keycloak kullanıcısı geldi, hangi tenant'a aitse oraya enrollment yapılmazsa user "homeless". **Çözüm:** Keycloak group prefix'i ile tenant resolve (`/lumix/inst-001/teachers`).
- **Client secret'ı env'e plaintext koymak.** Vault inject zorunlu.
- **Public client kullanmak (SPA için).** PKCE şart; Lumix backend-for-frontend (confidential client) yapar, public client önerilmez.
- **Logout sadece Lumix tarafında.** Kullanıcı Keycloak SSO ile geldiyse, "logout" Lumix session'ını kapatır ama Keycloak session'ı açık. **Çözüm:** opsiyonel `RP-Initiated Logout` ile Keycloak'a da çıkış sinyali.
- **Group → role mapping'i hardcode etmek.** Müşteri AD'sinde gruplar değişirse update lazım. **Çözüm:** mapping `tenant_config.group_to_role_mapping` JSONB.
- **JWKS cache uzun.** Keycloak imza anahtarı rotate ederse Lumix eski key ile validate denemeye devam eder. **Kural:** 5-10dk cache + ETag.
- **MFA bypass.** Keycloak realm'da MFA disabled ise zayıflık. **Kural:** Lumix Customer Admin Panel'de "MFA zorunluluğu" toggle Keycloak realm-required-action ile sync.

## 8. Diğer konularla ilişkisi

- [Fully Stateful Token Modeli](./01-stateful-token-model.md) — Keycloak login sonrası Lumix native token issue
- [Session & Device Lifecycle](./02-session-device-lifecycle.md) — Keycloak flow'da da aynı session model
- [Hibrit RBAC + ABAC](./04-rbac-abac-hybrid.md) — group → role mapping permission resolution
- [httpOnly Cookie Storage](./07-httponly-cookie-storage.md) — refresh cookie hem custom hem keycloak akışı için ortak

## 9. Daha derine inmek için

- Keycloak: [Server Administration Guide](https://www.keycloak.org/docs/latest/server_admin/)
- OpenID Connect: [Core 1.0 spec](https://openid.net/specs/openid-connect-core-1_0.html)
- OAuth 2.0: [Authorization Code Flow + PKCE (RFC 7636)](https://datatracker.ietf.org/doc/html/rfc7636)
- Spring Security: [OAuth 2.0 Login](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/index.html)
- Keycloak Operator: [Documentation](https://www.keycloak.org/operator/installation)
- Search keywords:
  - `keycloak spring boot integration oidc`
  - `just-in-time user provisioning idp`
  - `keycloak realm export gitops`
  - `oidc rp-initiated logout`
  - `keycloak group to role mapping`

## 10. Sözlük

- **Keycloak** — Açık kaynak IAM/SSO server. Lumix'te opsiyonel federated IdP rolünde.
- **IdP (Identity Provider)** — Kimliği yöneten ve doğrulayan sistem.
- **Federated Identity** — Kullanıcı kimliğinin uygulamadan ayrı bir IdP'de tutulması.
- **OIDC (OpenID Connect)** — OAuth 2.0 üstüne kurulu kimlik katmanı; `id_token` taşır.
- **Realm** — Keycloak'ta izole kullanıcı/kimlik alanı; Lumix bir installation = bir realm.
- **id_token** — OIDC'nin kullanıcı kimliğini taşıyan JWT'si.
- **JIT (Just-In-Time) Provisioning** — IdP'den ilk login eden kullanıcıyı uygulama DB'sine o anda yaratma.
- **PKCE (Proof Key for Code Exchange)** — Public client'larda authorization code intercept saldırısını engelleyen mekanizma.
- **RP-Initiated Logout** — Relying party (Lumix) tarafından başlatılan, IdP session'ını da kapatan logout.
- **Confidential client** — Client secret'a sahip, server-side OAuth client (Lumix backend).
- **Group-to-role mapping** — Keycloak gruplarının Lumix rollerine dönüştürülmesi.

