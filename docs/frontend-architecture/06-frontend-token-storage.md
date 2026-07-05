---
title: Token Storage (httpOnly Cookie + Memory Access Token)
description: Lumix frontend token saklama stratejisi — refresh token httpOnly cookie, access token Redux memory, 401 → auto refresh, CSRF, logout cleanup.
sidebar_position: 6
---

## Bu sayfa ne anlatıyor?

Lumix web ve mobile frontend'inde **token nasıl saklanır, nasıl yenilenir, nasıl temizlenir**? Bu sayfayı okuyan biri şunları öğrenmiş olacak:

- Access token ve refresh token farkı
- httpOnly cookie neden seçildi (XSS koruması)
- Access token Redux memory'de neden tutuluyor
- 401 alındığında auto refresh akışı (mutex ile race-free)
- CSRF saldırısı ve double-submit cookie pattern
- Logout'ta tüm token + cache nasıl temizlenir
- Mobile (React Native) tarafında farkı

Bu sayfa, [Stateful Token Modeli](../04-authentication-authorization/01-stateful-token-model.md) backend doc'unun frontend ayağıdır.

## 1. Token nedir? (Sıfırdan)

Login sonrası backend sana **"kim olduğunu kanıtlayan bir kağıt"** verir. Her request'te bu kağıdı gösterirsin. Bu kağıdın adı **token**.

İki çeşit token:

- **Access token**: kısa ömürlü (15 dk), her API çağrısında `Authorization` header'ında yollanır.
- **Refresh token**: uzun ömürlü (30 gün), sadece "bana yeni access token ver" demek için kullanılır.

### Günlük hayattan analoji

Bir AVM düşün:

- **Access token** = AVM giriş bilekliği. 15 dakika geçerli. Her dükkana girerken gösteriyorsun. Düşürürsen başkası kullanabilir.
- **Refresh token** = AVM üyelik kartın. Cebinde kilitli, sadece bileklik yenilemek için kullanıyorsun.
- **Login form** = ilk üyelik başvurusu (email + şifre).

### Lumix'in karması

Lumix tam **stateful**: token'lar JWT (imzalı) ama Redis'te de status tutuluyor → revoke edilebilir. Detay backend doc'unda. Frontend tarafından bizi ilgilendiren:

- **Access token**: backend'den `LoginResponse.accessToken` olarak gelir → Redux memory'de durur, her isteğe header olarak eklenir.
- **Refresh token**: backend'den **`Set-Cookie: refresh_token=...; HttpOnly; Secure; SameSite=Strict`** ile gelir → JavaScript göremez, tarayıcı otomatik gönderir.

## 2. Hangi problemi çözüyor?

Token saklama yanlış olursa:

- **localStorage'da access token** → XSS açığı varsa attacker tokenı çalar, sunucu tarafında "yetkili" olur. 2020-2024'te çoğu SaaS'in büyük breach'i bu yolla oldu.
- **localStorage'da refresh token** → uzun ömürlü token, çalınırsa kullanıcının tüm hesabı tehlikede.
- **Cookie'de access token, HttpOnly olmadan** → JavaScript okur, XSS riski aynı.
- **Cookie'de refresh token, SameSite olmadan** → CSRF saldırısı.
- **Cookie'de hiçbir şey** → cross-domain (CORS) ve subdomain senaryolarında problemler.

Lumix'in seçimi bu tuzakların hepsinden kaçar:

| Token | Yer | Erişim | Süre | Risk |
|---|---|---|---|---|
| Access token | Redux memory (in-memory) | JS okur (kasıtlı) | 15 dk | F5'te kaybolur (acceptable) — refresh ile geri kazanılır |
| Refresh token | httpOnly Secure cookie | JS okuyamaz | 30 gün | Tarayıcı otomatik gönderir; XSS koruması |

## 3. Nasıl çözüyor? (Mekanizma)

### 3.1. Login akışı

```
Browser                           Backend
   │                                 │
   ├─── POST /api/v1/auth/login ────►│
   │     { email, password }         │
   │                                 │  identity-service:
   │                                 │   - verify password
   │                                 │   - issue JWT access token
   │                                 │   - generate refresh token
   │                                 │   - SHA-512 hash → Redis
   │                                 │
   │◄────── 200 OK ──────────────────┤
   │   body: { accessToken, user, permissions, tenantIds }
   │   Set-Cookie: refresh_token=...; HttpOnly; Secure;
   │               SameSite=Strict; Path=/api/v1/auth;
   │               Max-Age=2592000
   │
   ├─ Redux: dispatch(loggedIn({ accessToken, user, ... }))
   ├─ Cookie tarayıcıda otomatik saklandı (JS göremez)
```

### 3.2. Authenticated request akışı

```
Component → useGetXQuery
            ↓
   RTK Query baseQuery.prepareHeaders
   ├── Authorization: Bearer <accessToken>  (Redux'tan)
   ├── X-Tenant-Id: <activeTenantId>
   └── X-Correlation-Id: <uuid>
            ↓
   fetch(url, { credentials: 'include' })
   ├── Cookie: refresh_token=... (tarayıcı otomatik ekledi)
            ↓
   Backend Kong → identity-service: JWT validate + Redis status check
            ↓
   200 OK → data
```

### 3.3. 401 → otomatik refresh akışı

```
Component → useGetXQuery → fetch → 401
                              ↓
   lumixBaseQuery wrapper devreye girer
   ├── Mutex check: başka refresh devam ediyor mu?
   │     ├── EVET: bekle, refresh bitince orijinal isteği tekrarla
   │     └── HAYIR: mutex acquire
            ↓
   POST /api/v1/auth/refresh
   ├── credentials: 'include' (refresh cookie tarayıcıda var)
            ↓
   200 OK → { accessToken: <yeni> }
   ├── Redux: dispatch(tokenRefreshed({ accessToken }))
   ├── (backend rotation yapıyorsa) yeni Set-Cookie geliyor
            ↓
   mutex release
            ↓
   Orijinal istek tekrarlanır → 200 OK
```

Refresh başarısızsa (refresh token expired/revoked):

```
   POST /api/v1/auth/refresh → 401 veya 403
            ↓
   Redux: dispatch(loggedOut())
   ├── lumixApi.util.resetApiState() (cache temizle)
   └── router: navigate('/login')
```

### 3.4. CSRF koruması

httpOnly cookie tarayıcı otomatik gönderdiği için, **CSRF saldırısı** mümkün: attacker bir başka sitede `<form action="https://lumix/api/v1/something" method="POST">` koyar, kullanıcı tıklarsa cookie ile gönderilir.

Lumix'in koruma katmanları:

1. **SameSite=Strict** — cross-site requestlerde cookie gönderilmez (modern tarayıcılarda)
2. **CSRF token** — login sonrası backend `XSRF-TOKEN` cookie'si (httpOnly DEĞİL) ve `X-CSRF-Token` header zorunluluğu (state-changing endpoint'ler için)
3. **Origin / Referer kontrolü** — Kong veya backend bunu doğrular

Frontend tarafında:

```ts
prepareHeaders: (headers, { getState }) => {
  const csrfToken = getCookie('XSRF-TOKEN');
  if (csrfToken) headers.set('X-CSRF-Token', csrfToken);
  // ...
}
```

`getCookie` fonksiyonu `document.cookie`'den XSRF token'ı okur (bu cookie HttpOnly değil — kasıtlı).

### 3.5. Logout akışı

```
User clicks "Çıkış"
            ↓
   POST /api/v1/auth/logout
   ├── credentials: 'include'
            ↓
   Backend: refresh token'ı Redis'ten sil, session revoke
   ├── Set-Cookie: refresh_token=; Max-Age=0 (silinir)
            ↓
   Frontend:
   ├── dispatch(loggedOut())
   ├── dispatch(tenantCleared())
   ├── lumixApi.util.resetApiState()  (RTK Query cache sıfırla)
   ├── i18n: dil cookie'si kalsın (tercih)
   └── navigate('/login')
```

## 4. Biz nasıl kullanıyoruz? (Lumix)

### 4.1. Kararlar matrisi

| Konu | Karar | Sebep |
|---|---|---|
| Access token storage | **Redux store (in-memory)** | XSS yüzeyini minimize; F5'te refresh ile recover |
| Refresh token storage | **httpOnly Secure SameSite=Strict cookie** | XSS koruması, CSRF koruması |
| Token rotation | **Her refresh'te rotate** | Replay attack detection |
| Mutex | **`async-mutex`** | Concurrent 401'lerde tek refresh |
| Logout-all | **`POST /api/v1/auth/logout-all`** desteklenir | Cihaz kaybı senaryosu |
| Inactivity timeout | **15dk + idle prompt** (opsiyonel, customer admin için aktif) | Compliance |
| `localStorage` kullanımı | **Token için yasak**; sadece tema, dil tercihi gibi non-sensitive | XSS riski |

### 4.2. Login sonrası state

```ts
// features/auth/api/authApi.ts
type LoginResponse = {
  accessToken: string;           // memory'e
  user: { id: string; email: string; fullName: string; roles: string[] };
  permissions: string[];
  tenantIds: string[];
  defaultTenantId: string;
  installationId: string;
  // refresh token cookie ile geldi; body'de YOK
};
```

```tsx
// features/auth/ui/LoginForm.tsx
const onSubmit = async (values) => {
  const result = await login(values).unwrap();
  dispatch(loggedIn({
    user: result.user,
    accessToken: result.accessToken,
    permissions: result.permissions,
  }));
  dispatch(tenantsAssigned({
    tenantIds: result.tenantIds,
    activeTenantId: result.defaultTenantId,
  }));
  navigate('/');
};
```

### 4.3. F5 sonrası recover

Kullanıcı F5'ledi → Redux memory sıfırlandı → accessToken yok ama refresh cookie hâlâ var.

```tsx
// app/providers/AuthBootstrap.tsx
import { useEffect, useState } from 'react';
import { useAppDispatch } from '@/app/store/hooks';
import { loggedIn, loggedOut } from '@/features/auth/model/authSlice';

export function AuthBootstrap({ children }: { children: React.ReactNode }) {
  const dispatch = useAppDispatch();
  const [ready, setReady] = useState(false);

  useEffect(() => {
    (async () => {
      try {
        const resp = await fetch('/api/v1/auth/refresh', {
          method: 'POST',
          credentials: 'include',
        });
        if (resp.ok) {
          const data = await resp.json();
          // bootstrap için /me çağırılır
          const me = await fetch('/api/v1/auth/me', {
            headers: { Authorization: `Bearer ${data.accessToken}` },
            credentials: 'include',
          });
          if (me.ok) {
            const meData = await me.json();
            dispatch(loggedIn({
              user: meData.user,
              accessToken: data.accessToken,
              permissions: meData.permissions,
            }));
          }
        } else {
          dispatch(loggedOut());
        }
      } catch {
        dispatch(loggedOut());
      } finally {
        setReady(true);
      }
    })();
  }, [dispatch]);

  if (!ready) return <SplashScreen />;
  return <>{children}</>;
}
```

### 4.4. Mobile (React Native) farklılığı

Mobile'da `httpOnly cookie` mantığı çoğu zaman kullanılmaz çünkü native HTTP istemcilerinde otomatik tarayıcı semantiği yok. Lumix mobile için:

- **Refresh token** → **Keychain (iOS)** / **Keystore (Android)** → `react-native-keychain` library
- **Access token** → Redux memory (web ile aynı)
- Backend mobile için **JSON body refresh token** veya **`X-Refresh-Token` header** alternatifi sağlar

Detay: [Mobile Shared Business Logic](../10-frontend-mobile/02-shared-business-logic.md).

## 5. Neden bu seçildi? (Alternatifler)

| Alternatif | Neden elendi |
|---|---|
| **Access token localStorage'da** | XSS riski kabul edilemez (admin panel, finance verisi) |
| **Access token httpOnly cookie'de** | JS Authorization header yazamaz, her endpoint cookie-based auth gerekir; CORS ve subdomain karmaşıklığı |
| **Refresh token localStorage'da** | XSS riski daha da yüksek (uzun ömürlü) |
| **Session-only cookie** (refresh + access ikisi de) | Multi-tenant header injection için JS access gerekiyor, pure cookie auth karmaşıklaşır |
| **Service worker token isolation** | Karmaşık, fallback'siz tarayıcılarda kırılır |
| **memory access + httpOnly refresh** ✅ | Standard, well-understood, XSS koruması net |

### Trade-off'lar

- **F5'te kısa loading**: Access token kaybolur, refresh çağrısı gerekir (200-500ms). Splash screen ile maskele.
- **Subdomain karmaşıklığı**: `app.lumix.com` ve `api.lumix.com` farklı subdomain'lerde → cookie `Domain=.lumix.com` veya same-origin proxy.
- **CORS credentials**: `credentials: 'include'` zorunlu; CORS config'de `Access-Control-Allow-Credentials: true` + spesifik origin.

## 6. Pratik örnek — auth flow başından sonuna

```ts
// shared/api/lumixBaseQuery.ts (özet — tam hali RTK Query doc'unda)
import { fetchBaseQuery, BaseQueryFn, FetchArgs, FetchBaseQueryError } from '@reduxjs/toolkit/query';
import { Mutex } from 'async-mutex';
import type { RootState } from '@/app/store';
import { tokenRefreshed, loggedOut } from '@/features/auth/model/authSlice';

const refreshMutex = new Mutex();

const rawBaseQuery = fetchBaseQuery({
  baseUrl: '/',
  credentials: 'include',
  prepareHeaders: (headers, { getState }) => {
    const state = getState() as RootState;
    if (state.auth.accessToken) {
      headers.set('Authorization', `Bearer ${state.auth.accessToken}`);
    }
    if (state.tenant.activeTenantId) {
      headers.set('X-Tenant-Id', state.tenant.activeTenantId);
    }
    const xsrf = getCookie('XSRF-TOKEN');
    if (xsrf) headers.set('X-CSRF-Token', xsrf);
    headers.set('X-Correlation-Id', crypto.randomUUID());
    return headers;
  },
});

export const lumixBaseQuery: BaseQueryFn<
  string | FetchArgs, unknown, FetchBaseQueryError
> = async (args, api, extra) => {
  await refreshMutex.waitForUnlock();
  let result = await rawBaseQuery(args, api, extra);

  if (result.error?.status === 401) {
    if (!refreshMutex.isLocked()) {
      const release = await refreshMutex.acquire();
      try {
        const refresh = await rawBaseQuery(
          { url: '/api/v1/auth/refresh', method: 'POST' },
          api,
          extra,
        );
        if (refresh.data) {
          const { accessToken } = refresh.data as { accessToken: string };
          api.dispatch(tokenRefreshed({ accessToken }));
          result = await rawBaseQuery(args, api, extra);
        } else {
          api.dispatch(loggedOut());
        }
      } finally {
        release();
      }
    } else {
      await refreshMutex.waitForUnlock();
      result = await rawBaseQuery(args, api, extra);
    }
  }
  return result;
};

function getCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`));
  return match ? decodeURIComponent(match[1]) : null;
}
```

`features/auth/api/authApi.ts`:

```ts
import { lumixApi } from '@/shared/api/lumixApi';

export const authApi = lumixApi.injectEndpoints({
  endpoints: (build) => ({
    login: build.mutation({
      query: (body) => ({
        url: '/api/v1/auth/login',
        method: 'POST',
        body,
      }),
    }),
    logout: build.mutation<void, void>({
      query: () => ({ url: '/api/v1/auth/logout', method: 'POST' }),
      async onQueryStarted(_arg, { dispatch, queryFulfilled }) {
        try {
          await queryFulfilled;
        } finally {
          dispatch(loggedOut());
          dispatch(tenantCleared());
          dispatch(lumixApi.util.resetApiState());
        }
      },
    }),
    logoutAll: build.mutation<void, void>({
      query: () => ({ url: '/api/v1/auth/logout-all', method: 'POST' }),
    }),
  }),
});

export const { useLoginMutation, useLogoutMutation, useLogoutAllMutation } = authApi;
```

## 7. Tuzaklar

- **`localStorage.setItem('token', ...)`** — Lumix'te yasak. CI lint kuralı: `no-localStorage-token`.
- **`document.cookie` ile refresh token okumaya çalışmak** — httpOnly cookie JS'den **görünmez**; bu zaten korumanın amacı.
- **`SameSite=None`** — Cross-site cookie istisnası; ancak gerçekten cross-site iframe senaryosu varsa. Lumix'te `Strict` veya `Lax`.
- **`credentials: 'include'` unutmak** — Cookie gönderilmez, refresh çalışmaz.
- **CORS yanlış konfigürasyonu** — `Access-Control-Allow-Origin: *` ile `credentials: include` aynı anda olmaz; spesifik origin gerek.
- **401 retry sonsuz döngüsü** — Refresh de 401 dönerse retry et**me**; loggedOut + redirect.
- **Mutex'siz refresh** — 5 paralel istek 401 → 5 refresh → backend rotation aktifse 4'ü hatalı sayılır.
- **Page reload kaybolan state'e ek state koymak** — `auth.user` da memory'de; F5'te `me` çağrısı ile yeniden yüklenir, bootstrap yap.
- **CSRF unutmak** — Sadece SameSite=Strict yeterli değil (Lax bazı durumlarda gönderir, eski tarayıcılar). Double-submit pattern ek katman.
- **Logout sonrası cache temizlemeyi unutmak** — Eski kullanıcının data'sı yeni login'de görünür → veri sızıntısı.
- **Refresh token cookie'sini her path'e koymak** — `Path=/api/v1/auth` ile sınırla; static asset request'lerinde gönderilmesin (gereksiz exposure).

## 8. Diğer konularla ilişkisi

- [Stateful Token Modeli (backend)](../04-authentication-authorization/01-stateful-token-model.md) — refresh, rotation, Redis status
- [Redux Toolkit](./02-redux-toolkit.md) — auth slice, tokenRefreshed action
- [RTK Query](./33-rtk-query.md) — baseQuery, mutex, retry mantığı
- [Permission Cache](./09-frontend-permission-cache.md) — login sonrası permission yüklemesi
- [Mobile Shared Logic](../10-frontend-mobile/02-shared-business-logic.md) — Keychain/Keystore farkı

## 9. Daha derine

- OWASP Cheat Sheet — JWT for Java: https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_for_Java_Cheat_Sheet.html
- OWASP CSRF Prevention: https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html
- Auth0 Blog: "Where to store tokens": https://auth0.com/docs/secure/security-guidance/data-security/token-storage
- Search keywords:
  - `httponly cookie refresh token jwt access token memory`
  - `csrf double submit cookie pattern react`
  - `samesite strict refresh token security`
  - `react axios mutex 401 refresh retry`
  - `react native keychain refresh token storage`

## 10. Sözlük

- **Access token** — Kısa ömürlü, her API çağrısında kullanılan token.
- **Refresh token** — Uzun ömürlü, yeni access token üretmek için kullanılan token.
- **httpOnly cookie** — JavaScript'ten erişilemeyen cookie; sadece tarayıcı HTTP request'lerine ekler.
- **Secure cookie** — Sadece HTTPS bağlantıda gönderilen cookie.
- **SameSite** — Cross-site request'lerde cookie'nin nasıl gönderileceğini belirten attribute (Strict / Lax / None).
- **CSRF (Cross-Site Request Forgery)** — Saldırgan başka siteden kullanıcının yetkili request'ini gönderme saldırısı.
- **XSS (Cross-Site Scripting)** — Saldırganın site'a JS enjekte etmesi; localStorage erişimi açar.
- **Mutex** — Birden fazla coroutine'in aynı kaynağa erişimini koordine eden senkronizasyon primitif'i.
- **Token rotation** — Her refresh'te yeni refresh token üretmek; eski olanın geçersiz olması.
- **Idle timeout** — Kullanıcı aktivitesizken oturumu sonlandırma süresi.
