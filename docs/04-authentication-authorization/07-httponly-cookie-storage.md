---
title: httpOnly Cookie ile Token Saklama
description: Lumix'in refresh token httpOnly Secure cookie kararı, access token saklama opsiyonları, XSS koruması, CORS + credentials, CSRF token ve SameSite=Strict.
sidebar_position: 7
---

## Bu sayfa ne anlatıyor?

Frontend access token'ı nereye koymalı? Refresh token nasıl saklanmalı? `localStorage` neden tehlikeli? `httpOnly cookie` ne demek, nasıl çalışır, hangi saldırılara karşı korur ama hangilerine **karşı korumaz**? Bu sayfa Lumix'in **token saklama kararını** ve buna bağlı **CORS, CSRF, SameSite** konfigürasyonlarını anlatır.

## 1. Bu nedir? (Sıfırdan)

Frontend "ben bu kullanıcıyım" diye sunucuya tokeni nasıl gönderir? Bunun **3 klasik yolu** var:

| Yer | Erişim | Otomatik gönderim |
|---|---|---|
| **localStorage** | JavaScript erişebilir | Hayır (manuel header) |
| **JS memory (Redux/zustand)** | JavaScript erişebilir, sayfa kapanınca kaybolur | Hayır (manuel header) |
| **Cookie** | `document.cookie` (httpOnly değilse) | Evet (browser otomatik) |

**httpOnly cookie** = JavaScript'in **erişemediği** cookie. Browser var, sunucuya otomatik gönderir, ama `document.cookie` ile okunamaz.

```
Set-Cookie: refresh_token=eyJ...; HttpOnly; Secure; SameSite=Strict; Path=/auth
```

| Flag | Anlamı |
|---|---|
| `HttpOnly` | JS erişemez (XSS koruması) |
| `Secure` | Sadece HTTPS üzerinden gönderilir |
| `SameSite=Strict` | Sadece **aynı site**ten gelen istekte gönderilir (CSRF koruması) |
| `Path=/auth` | Sadece /auth altındaki endpoint'lere gönderilir (scope dar) |

### Günlük analoji

Cüzdanda kimliğini taşıyorsun. İki seçenek:
- **localStorage** = kimliği elinde tutuyorsun, herkes görüyor (XSS)
- **httpOnly cookie** = kimliği kapalı zarfta resepsiyona bıraktın, sen göremiyorsun ama otel resepsiyonu her gerektiğinde onu kullanıyor (otomatik gönderim)

## 2. Hangi problemi çözüyor?

### 2.1. XSS (Cross-Site Scripting)
Frontend'e enjekte edilmiş bir kötü amaçlı script `localStorage.getItem('refresh_token')` ile token'ı çalar. **httpOnly cookie ile bu mümkün değil.**

### 2.2. Token sızıntısı (bug / third-party script)
Yanlışlıkla console.log, Sentry'ye gönderilen state dump, third-party analytics → localStorage içeriği sızar. httpOnly cookie'de bu da yok.

### 2.3. Mobile vs Web ayrımı
Web tarayıcısı = cookie native. Mobil app = cookie yok, Authorization header. **Lumix:**
- **Web:** refresh token httpOnly cookie, access token kısa ömürlü memory veya cookie.
- **Mobile:** secure storage (Keychain / Android Keystore), Authorization header.

### 2.4. CSRF (Cross-Site Request Forgery)
Cookie otomatik gönderildiğinden saldırgan kullanıcının cookie'sini başka site'tan tetikleyebilir. Bunu **SameSite=Strict** + (varsa) CSRF token engeller.

### 2.5. Backend mimari netliği
"Token nereden gelir?" sorusu net cevap istiyor. Cookie + header karışıklığı = bug magnet.

## 3. Nasıl çözüyor? (Mekanizma + diyagram)

### 3.1. Lumix'in token saklama stratejisi

```
                          FRONTEND (React SPA — CSR)
        ┌─────────────────────────────────────────────────────────┐
        │                                                         │
        │   Access Token (15dk)                                   │
        │   ┌──────────────────────────────────────────────┐     │
        │   │  Memory only (Redux slice — auth.accessToken)│     │
        │   │  Sayfa refresh'inde kaybolur                  │     │
        │   │  Refresh ile yeniden alınır                   │     │
        │   └──────────────────────────────────────────────┘     │
        │             │                                            │
        │             ▼                                            │
        │   Authorization: Bearer <access_token>                  │
        │                                                         │
        │   Refresh Token (7-30gün)                               │
        │   ┌──────────────────────────────────────────────┐     │
        │   │  httpOnly Secure SameSite=Strict cookie       │     │
        │   │  Path=/auth (sadece auth endpoint'lerinde     │     │
        │   │  gönderilir)                                  │     │
        │   └──────────────────────────────────────────────┘     │
        │             │                                            │
        │             ▼                                            │
        │   Browser otomatik → POST /auth/refresh                 │
        │                                                         │
        └─────────────────────────────────────────────────────────┘
```

**Önemli karar:** Access token Lumix'te **memory'de** (Redux). `localStorage`'da değil. Çünkü:
- XSS riskini minimize ediyoruz
- Sayfa refresh edildiğinde refresh endpoint'i ile yenisi alınır (refresh cookie var olduğu için sessiz)
- Mobile + web'in API kontratı tutarlı kalsın diye access token Authorization header'da gönderilir (cookie ile karışıklık olmasın)

### 3.2. Login → cookie set

```
POST /auth/login
  Body: { username, password }
                │
                ▼
identity-service:
  Set-Cookie: refresh_token=opaque-base64;
              HttpOnly;
              Secure;
              SameSite=Strict;
              Path=/auth;
              Max-Age=604800;          (7 gün)
              Domain=lumix.example.com (opsiyonel)

  Response Body: {
    "access_token": "eyJ...",          (Authorization header için)
    "expires_in": 900,                 (15dk)
    "token_type": "Bearer"
  }
```

### 3.3. Refresh akışı (cookie otomatik gönderilir)

```
Access token expired → 401
       │
       ▼
Frontend axios interceptor:
   axios.post('/auth/refresh', {}, { withCredentials: true })
                                 │
                                 ▼
                          Browser cookie ekler:
                          Cookie: refresh_token=...
                                 │
                                 ▼
                       identity-service:
                       1. Cookie'den refresh token oku
                       2. SHA-512 hash → Redis lookup
                       3. Rotate: yeni token
                       4. Set-Cookie: refresh_token=<yeni>
                       5. Body: { access_token: "<yeni>" }
                                 │
                                 ▼
                       Frontend Redux update + retry original request
```

### 3.4. CORS + credentials akışı

Frontend domain: `app.lumix.com`
Backend domain: `api.lumix.com`

Cookie cross-origin gönderilebilmesi için **iki uçtan da** ayar gerek:

```
Backend response:
  Access-Control-Allow-Origin: https://app.lumix.com   (wildcard YASAK)
  Access-Control-Allow-Credentials: true
  Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS
  Access-Control-Allow-Headers: Authorization, Content-Type, X-Correlation-Id
  Access-Control-Max-Age: 600

Frontend axios:
  axios.defaults.withCredentials = true
```

Eğer **SameSite=Strict** kullanırsan ve frontend + backend farklı eTLD+1 (registrable domain) ise → cookie gönderilmez. Lumix bu yüzden iki yaklaşımdan birini seçer:
- **Same-domain:** `app.lumix.com` + `api.lumix.com` → tek `lumix.com` eTLD+1 → SameSite=Strict çalışır.
- **Subdomain:** Aynı.
- **Tamamen farklı domain:** SameSite=Lax veya `None` (Secure ile) gerekebilir.

Lumix kuralı: **app + api aynı eTLD+1**, SameSite=Strict.

### 3.5. CSRF defense in depth

SameSite=Strict cookie zaten CSRF'i büyük ölçüde engeller. Ama Lumix ek bir katman olarak:

- **Double-submit cookie** pattern: `X-CSRF-Token` header + `csrf_token` cookie eşleşmesi.
- Sadece **state-changing** endpoint'lerde (POST/PUT/PATCH/DELETE).
- `/auth/refresh` ve `/auth/login` hariç (zaten public ve idempotent değiller).

```
1. Login sonrası backend ek cookie set eder:
   Set-Cookie: csrf_token=random-256-bit; Secure; Path=/; SameSite=Strict
   (HttpOnly DEĞİL — JS okuyabilsin)

2. Frontend her POST/PUT/PATCH/DELETE öncesi:
   const csrf = readCookie('csrf_token');
   axios.defaults.headers['X-CSRF-Token'] = csrf;

3. Backend filter:
   header X-CSRF-Token == cookie csrf_token ?
   eşleşmezse 403.
```

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Cookie matrix

| Cookie | HttpOnly | Secure | SameSite | Path | Max-Age | İçerik |
|---|---|---|---|---|---|---|
| `refresh_token` | ✓ | ✓ | Strict | /auth | 7-30gün | Opaque random |
| `csrf_token` | ✗ (JS okur) | ✓ | Strict | / | session | Random 256-bit |
| `_lumix_lang` | ✗ | ✓ | Lax | / | 1yıl | tr / en |

**Access token** cookie değil. Memory + Authorization header.

### 4.2. Domain stratejisi

- Production: `app.lumix.example.com` + `api.lumix.example.com`
- Cookie domain: explicit set edilmez (default = host-only)
- eTLD+1: `lumix.example.com` (PSL'e göre değişir)

### 4.3. Spring Security cookie config

```yaml
lumix:
  cookie:
    refresh:
      name: refresh_token
      path: /auth
      same-site: Strict
      secure: true
      http-only: true
      max-age: P7D
    csrf:
      name: csrf_token
      path: /
      same-site: Strict
      secure: true
      http-only: false
```

### 4.4. CORS allowlist

```yaml
lumix:
  cors:
    allowed-origins:
      - https://app.lumix.example.com
      - https://admin.lumix.example.com
    allow-credentials: true
    allowed-methods: [GET, POST, PUT, PATCH, DELETE, OPTIONS]
    allowed-headers: [Authorization, Content-Type, X-Correlation-Id, X-CSRF-Token, X-Active-Tenant]
    max-age: 600
```

Wildcard origin (`*`) **yasak** — credentials ile uyumsuz, CORS spec gereği.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

| Saklama | Tartışma | Karar |
|---|---|---|
| **localStorage** (refresh + access) | XSS = total compromise. **Elendi.** | ✗ |
| **localStorage** (sadece access) | Access token çalınabilir. Refresh httpOnly olsa bile saldırgan elindeki access ile 15dk full erişim. **Elendi.** | ✗ |
| **httpOnly cookie** (refresh) + **memory** (access) | Refresh güvenli, access kısa ömürlü ve sayfa refresh'inde yeniden alınır. ✓ | **Lumix** |
| **httpOnly cookie** (refresh ve access ikisi de) | Mobile ile API kontratı farklılaşır (Authorization header yerine Cookie). Browser-only çözüm. | Düşünüldü, mobile için ayrıştırma getirdiği için elendi. |
| **SessionStorage** | Tab'lar arası paylaşılmaz, UX kötü. **Elendi.** | ✗ |

### Trade-off'lar

- **Memory access token + sayfa refresh:** Refresh endpoint çağrılır (200ms ekstra). Kabul.
- **SameSite=Strict:** İlk request başka site'ten (örn. email link tıklama) login değilse 401 → login sayfası. UX: kullanıcı tekrar login. Lumix bu güvenliği tercih etti.
- **CSRF token bakımı:** Double-submit cookie pattern minimal kod, kabul.

### Mobile için ne yapıyoruz?

Mobile app (React Native):
- iOS: Keychain
- Android: EncryptedSharedPreferences (Android Keystore-backed)
- Token Authorization header'da gönderilir (cookie yok)
- Refresh token rotation aynı şekilde çalışır
- Logout = secure storage'dan sil + backend revoke

## 6. Pratik örnek

### 6.1. Cookie write (Spring Boot)

```java
public class AuthCookies {

    private static final Duration WEB_REFRESH_TTL = Duration.ofDays(7);

    public static ResponseCookie refreshCookie(String opaque) {
        return ResponseCookie.from("refresh_token", opaque)
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/auth")
            .maxAge(WEB_REFRESH_TTL)
            .build();
    }

    public static ResponseCookie csrfCookie(String token) {
        return ResponseCookie.from("csrf_token", token)
            .httpOnly(false)        // JS okuyabilsin
            .secure(true)
            .sameSite("Strict")
            .path("/")
            .build();
    }

    public static ResponseCookie expiredRefresh() {
        return ResponseCookie.from("refresh_token", "")
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/auth")
            .maxAge(Duration.ZERO)
            .build();
    }
}
```

### 6.2. CORS configuration

```java
@Configuration
public class CorsConfig {

    @Value("${lumix.cors.allowed-origins}")
    private List<String> allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(allowedOrigins);   // explicit, no wildcard
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of(
            "Authorization", "Content-Type",
            "X-Correlation-Id", "X-CSRF-Token", "X-Active-Tenant"
        ));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
```

### 6.3. CSRF filter (double-submit)

```java
@Component
public class CsrfDoubleSubmitFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> EXEMPT_PATHS = Set.of(
        "/auth/login", "/auth/refresh", "/auth/login/keycloak", "/auth/callback/keycloak"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                     HttpServletResponse res,
                                     FilterChain chain) throws ServletException, IOException {
        String method = req.getMethod();
        String path = req.getRequestURI();

        if (SAFE_METHODS.contains(method) || EXEMPT_PATHS.contains(path)) {
            chain.doFilter(req, res); return;
        }

        String header = req.getHeader("X-CSRF-Token");
        String cookie = Optional.ofNullable(req.getCookies())
            .stream().flatMap(Arrays::stream)
            .filter(c -> "csrf_token".equals(c.getName()))
            .map(Cookie::getValue).findFirst().orElse(null);

        if (header == null || cookie == null || !MessageDigest.isEqual(
                header.getBytes(StandardCharsets.UTF_8),
                cookie.getBytes(StandardCharsets.UTF_8))) {
            res.setStatus(403);
            res.getWriter().write("{\"error\":\"csrf_mismatch\"}");
            return;
        }

        chain.doFilter(req, res);
    }
}
```

### 6.4. Frontend axios setup (React)

```ts
// axiosClient.ts
import axios from 'axios';
import { store } from '@/app/store';
import { authActions } from '@/features/auth/authSlice';

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL,
  withCredentials: true,            // refresh + csrf cookie gönder
});

api.interceptors.request.use((cfg) => {
  const accessToken = store.getState().auth.accessToken;
  if (accessToken) {
    cfg.headers.Authorization = `Bearer ${accessToken}`;
  }
  const csrf = readCookie('csrf_token');
  if (csrf && ['post', 'put', 'patch', 'delete'].includes(cfg.method ?? '')) {
    cfg.headers['X-CSRF-Token'] = csrf;
  }
  return cfg;
});

api.interceptors.response.use(
  (r) => r,
  async (error) => {
    if (error.response?.status === 401 && !error.config._retry) {
      error.config._retry = true;
      try {
        const r = await axios.post('/auth/refresh', {}, { withCredentials: true });
        store.dispatch(authActions.setAccessToken(r.data.access_token));
        error.config.headers.Authorization = `Bearer ${r.data.access_token}`;
        return api.request(error.config);
      } catch {
        store.dispatch(authActions.forceLogout('refresh_failed'));
        window.location.href = '/login';
      }
    }
    throw error;
  }
);

export default api;

function readCookie(name: string): string | null {
  const m = document.cookie.match(new RegExp('(^| )' + name + '=([^;]+)'));
  return m ? decodeURIComponent(m[2]) : null;
}
```

### 6.5. Logout

```java
@PostMapping("/logout")
public ResponseEntity<Void> logout(@CookieValue(name = "refresh_token", required = false) String refresh,
                                    @AuthenticationPrincipal Jwt jwt) {
    authService.logout(jwt.getId(), refresh);
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, AuthCookies.expiredRefresh().toString())
        .build();
}
```

## 7. Dikkat edilecek tuzaklar

- **`localStorage` reflex'i.** "Token'ı atalım, kolay." → XSS = total compromise. **Yasak.**
- **`document.cookie` access token okuyup header'a koymak.** HttpOnly olmayan cookie → JS okur → XSS yine sızdırır. **Yasak.**
- **`SameSite=None` + `Secure` olmadan.** Browser cookie'yi reddeder. Yeni cookie standardı.
- **CORS wildcard origin + credentials.** Tarayıcı CORS spec'ine göre reddeder; bazı eski tarayıcı kabul eder = güvenlik açığı. **Yasak.**
- **CSRF token'ı header AYNI cookie ile karşılaştırmamak.** Double-submit'in özü o. Sabit constant-time compare (`MessageDigest.isEqual`) kullan, naive `equals` timing-leak.
- **Path=/ refresh cookie.** Yanlışlıkla her endpoint'e refresh gider; sızma yüzeyi artar. **Path=/auth zorunlu.**
- **`Set-Cookie` yanıt header'ında newline injection.** Backend cookie value'nun sanitize edildiğinden emin ol (Spring `ResponseCookie` builder zaten yapar).
- **Mobile için httpOnly cookie kullanmak.** React Native cookie handling tutarsız + secure storage yok. **Mobile = Keychain/Keystore + Authorization header.**
- **CSRF cookie HttpOnly yapmak.** JS okuyamaz → header'a koyamaz → double-submit kırılır. **Çözüm:** csrf_token HttpOnly DEĞİL.
- **HTTP development'ta Secure flag.** Secure cookie HTTPS olmayan localhost'ta browser reddeder; bazı browser localhost exception verir. Lumix dev için `mkcert` ile HTTPS önerilir.
- **Logout'ta sadece cookie expire, backend revoke yok.** Cookie silinse de Redis'te aktif kalır → çalınmış refresh hâlâ çalışır. **Kural:** logout = backend revoke + cookie expire.

## 8. Diğer konularla ilişkisi

- [Fully Stateful Token Modeli](./stateful-token-model) — token formatları ve refresh akışı
- [Session & Device Lifecycle](./session-device-lifecycle) — refresh token Redis tarafı
- [Keycloak Entegrasyonu](./keycloak-integration) — Keycloak akışında da aynı cookie modeli
- [Permission Change & Revoke Flow](./permission-change-revoke-flow) — force logout sırasında cookie clear

## 9. Daha derine inmek için

- OWASP: [Session Management — Cookies Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html#cookies)
- IETF: [RFC 6265bis — Cookies: HTTP State Management Mechanism](https://datatracker.ietf.org/doc/draft-ietf-httpbis-rfc6265bis/)
- Mozilla MDN: [`Set-Cookie` (especially SameSite)](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Set-Cookie)
- OWASP: [CSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)
- Auth0: "Cookies vs. Tokens"
- Search keywords:
  - `httponly cookie xss prevention`
  - `samesite strict csrf protection`
  - `cors credentials same-site cookie`
  - `double submit cookie pattern`
  - `localStorage vs cookie security`

## 10. Sözlük

- **httpOnly Cookie** — JavaScript'in erişemediği, sadece HTTP request'lerde browser tarafından otomatik gönderilen cookie.
- **SameSite** — Cookie'nin cross-site isteklerde gönderilip gönderilmeyeceğini kontrol eden flag: Strict / Lax / None.
- **Secure flag** — Cookie'nin sadece HTTPS bağlantılarında gönderilmesini zorunlu kılan flag.
- **eTLD+1** — Effective Top-Level Domain + 1 label; cookie domain kapsamının taban birimi (örn. `lumix.com`).
- **XSS (Cross-Site Scripting)** — Saldırganın frontend'e script enjekte etmesi.
- **CSRF (Cross-Site Request Forgery)** — Saldırganın kullanıcı kimliğiyle istem dışı istek tetiklemesi.
- **Double-submit cookie** — CSRF token'ı hem cookie hem header'da gönderme ve eşleşme kontrolü.
- **PSL (Public Suffix List)** — Hangi domain segmentinin "kayıt edilebilir" olduğunu belirleyen liste; eTLD+1 hesabında kullanılır.
- **withCredentials** — Browser/Fetch API'sinde cross-origin istekte cookie/Authorization gönderme izni.

