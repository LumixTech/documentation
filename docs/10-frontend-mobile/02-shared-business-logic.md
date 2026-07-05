---
title: Web/Mobile Shared Business Logic
description: Lumix monorepo'da web ve mobile arasında Redux Toolkit slice + RTK Query api + types paylaşımı (@lumix/core), platform-spesifik UI ayrımı.
sidebar_position: 2
---

## Bu sayfa ne anlatıyor?

Lumix'in **web ve mobile uygulamaları**, ortak business logic'i paylaşır. Bu sayfa şunları açıklar:

- Monorepo yapısı (pnpm workspace)
- `@lumix/core` paketinin içeriği (Redux slice, RTK Query, types, schemas)
- Platform-spesifik kodun ayrımı (UI, native module)
- HTTP istemcisinde platform farkları (cookie vs Keychain)
- i18n paket paylaşımı
- TypeScript paket konfigürasyonu (path alias, build)
- CI/CD'de paket sırası ve cache

Bu sayfa, **iki uygulamanın aynı dilden konuşmasının** mimarisidir.

## 1. Kod paylaşımı nedir? (Sıfırdan)

İki uygulama — `apps/web` (React + Vite) ve `apps/mobile` (React Native) — aynı backend'i kullanır, aynı domain mantığını içerir. Mesela:

- Login endpoint çağrısı aynı (`POST /api/v1/auth/login`)
- Auth state (`user`, `accessToken`, `permissions`) aynı yapıda
- Mesaj cache invalidation kuralları aynı
- Date format, currency format, zod schema, type tanımları aynı

Bu mantığı **iki kez yazmak** = bug 2x, bakım 2x, deviation kaçınılmaz.

**Çözüm**: Ortak kodu **`packages/core` paketine** koy, hem `apps/web` hem `apps/mobile` import etsin.

### Günlük hayattan analoji

Bir restoran zinciri düşün:

- **Reçeteler** = `packages/core` (her şubede aynı)
- **Mutfak ekipmanları** = `apps/web` / `apps/mobile` (Web → modern mutfak; Mobile → açık alanda barbekü)
- **Tabak sunum** = UI component'leri (her ortamda farklı tabak)

Reçete (business logic) tek; sunum (UI) iki türlü.

## 2. Hangi problemi çözüyor?

Paylaşım yoksa:

- **Auth slice iki defa yazılır** → biri rotation logic atlar → mobile prod'da revoke gecikmesi
- **RTK Query endpoint'leri iki defa** → URL değişiminde biri unutulur
- **Zod schema iki defa** → backend'in beklediği DTO ile çakışma
- **Date utility iki defa** → birinde DST bug'ı, diğerinde yok

Paylaşım ile:

- **Tek source of truth**
- **Yeni feature backend'de bittikten sonra core'a yaz** → hem web hem mobile aynı anda alır
- **Test bir kere** (core'da)

Trade-off:
- Monorepo ek kurulum yükü (pnpm workspace, build orchestration)
- Paket boundary'ları net olmazsa "domino effect" değişiklik

## 3. Nasıl çözüyor? (Mekanizma)

### 3.1. pnpm workspace

```yaml
# pnpm-workspace.yaml
packages:
  - apps/*
  - packages/*
```

`apps/web/package.json`:
```json
{
  "name": "@lumix/web",
  "dependencies": {
    "@lumix/core": "workspace:*",
    "react": "^18.3.0"
  }
}
```

`apps/mobile/package.json`:
```json
{
  "name": "@lumix/mobile",
  "dependencies": {
    "@lumix/core": "workspace:*",
    "react": "18.2.0",
    "react-native": "0.74.5"
  }
}
```

`workspace:*` → pnpm sembolik link kurar; her değişiklik anlık aynalanır.

### 3.2. `@lumix/core` paketi içeriği

```
packages/core/
├── src/
│   ├── store/
│   │   ├── store.ts             # configureStore (web ve mobile için ortak)
│   │   ├── hooks.ts             # useAppDispatch, useAppSelector
│   │   └── middleware/
│   │       └── cacheReset.ts
│   ├── auth/
│   │   ├── authSlice.ts
│   │   ├── api.ts               # authApi (login, logout, refresh)
│   │   ├── types.ts
│   │   └── schemas.ts           # zod
│   ├── tenant/
│   │   ├── tenantSlice.ts
│   │   └── api.ts
│   ├── attendance/
│   │   ├── api.ts
│   │   └── types.ts
│   ├── messages/
│   │   ├── api.ts
│   │   └── types.ts
│   ├── permission/
│   │   ├── api.ts
│   │   └── types.ts
│   ├── shared/
│   │   ├── api/
│   │   │   ├── lumixApi.ts      # createApi
│   │   │   └── baseQuery.ts     # PLATFORM ADAPTER (aşağıda)
│   │   ├── lib/
│   │   │   ├── date.ts
│   │   │   ├── currency.ts
│   │   │   ├── correlationId.ts
│   │   │   └── env.ts
│   │   └── types/
│   │       └── problemDetails.ts # RFC 7807
│   └── index.ts                 # barrel exports
├── package.json
└── tsconfig.json
```

### 3.3. Platform farklarını adapter ile soyutla

En kritik fark: **HTTP storage**. Web'de cookie, mobile'da Keychain. Çözüm: **platform adapter**.

```ts
// packages/core/src/shared/api/baseQuery.ts
import {
  fetchBaseQuery,
  BaseQueryFn,
  FetchArgs,
  FetchBaseQueryError,
} from '@reduxjs/toolkit/query/react';
import { Mutex } from 'async-mutex';
import { tokenRefreshed, loggedOut } from '../../auth/authSlice';
import type { RootState } from '../../store';

// Adapter interface
export type PlatformAdapter = {
  /** Auth refresh sırasında platforma özel header/credential ekleme */
  prepareRefreshRequest: (req: { headers: Headers }) => Promise<void>;
  /** Persist edilen refresh token oku (mobile için Keychain) */
  readPersistedRefreshToken?: () => Promise<string | null>;
  /** Refresh sonrası yeni refresh token sakla (web'de no-op, mobile'da Keychain) */
  persistRefreshToken?: (token: string) => Promise<void>;
  /** Refresh token sil (logout) */
  clearPersistedRefreshToken?: () => Promise<void>;
  /** fetch credentials davranışı: web 'include', mobile 'omit' */
  credentialsMode: 'include' | 'omit' | 'same-origin';
};

let adapter: PlatformAdapter | null = null;

export function setPlatformAdapter(a: PlatformAdapter) {
  adapter = a;
}

const refreshMutex = new Mutex();

export function createLumixBaseQuery(baseUrl: string) {
  const raw = fetchBaseQuery({
    baseUrl,
    credentials: adapter?.credentialsMode,
    prepareHeaders: (headers, { getState }) => {
      const state = getState() as RootState;
      const accessToken = state.auth.accessToken;
      if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);
      if (state.tenant.activeTenantId)
        headers.set('X-Tenant-Id', state.tenant.activeTenantId);
      headers.set('X-Correlation-Id', generateCorrelationId());
      return headers;
    },
  });

  const baseQuery: BaseQueryFn<
    string | FetchArgs,
    unknown,
    FetchBaseQueryError
  > = async (args, api, extra) => {
    await refreshMutex.waitForUnlock();
    let result = await raw(args, api, extra);

    if (result.error?.status === 401) {
      if (!refreshMutex.isLocked()) {
        const release = await refreshMutex.acquire();
        try {
          const refreshArgs = await buildRefreshRequest();
          const r = await raw(refreshArgs, api, extra);
          if (r.data) {
            const { accessToken, refreshToken } = r.data as {
              accessToken: string;
              refreshToken?: string;
            };
            api.dispatch(tokenRefreshed({ accessToken }));
            if (refreshToken && adapter?.persistRefreshToken)
              await adapter.persistRefreshToken(refreshToken);
            result = await raw(args, api, extra);
          } else {
            api.dispatch(loggedOut());
            if (adapter?.clearPersistedRefreshToken)
              await adapter.clearPersistedRefreshToken();
          }
        } finally {
          release();
        }
      } else {
        await refreshMutex.waitForUnlock();
        result = await raw(args, api, extra);
      }
    }
    return result;
  };

  return baseQuery;
}

async function buildRefreshRequest(): Promise<FetchArgs> {
  const args: FetchArgs = { url: '/api/v1/auth/refresh', method: 'POST' };
  if (adapter?.readPersistedRefreshToken) {
    const token = await adapter.readPersistedRefreshToken();
    if (token) args.body = { refreshToken: token };
  }
  return args;
}

function generateCorrelationId(): string {
  // crypto.randomUUID() RN polyfill ile çalışır; ya da nanoid
  return typeof crypto !== 'undefined' && crypto.randomUUID
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}
```

### 3.4. Web adapter

```ts
// apps/web/src/shared/api/webAdapter.ts
import { setPlatformAdapter } from '@lumix/core/shared/api';

setPlatformAdapter({
  credentialsMode: 'include', // httpOnly cookie tarayıcı otomatik gönderir
  prepareRefreshRequest: async () => {
    // cookie zaten dahil; ek bir şey yok
  },
  // readPersistedRefreshToken: tanımlamıyoruz → web body içinde refresh token yollamaz
});
```

### 3.5. Mobile adapter

```ts
// apps/mobile/src/shared/api/mobileAdapter.ts
import * as Keychain from 'react-native-keychain';
import { setPlatformAdapter } from '@lumix/core/shared/api';

const KEYCHAIN_KEY = 'lumix.refreshToken';

setPlatformAdapter({
  credentialsMode: 'omit',
  prepareRefreshRequest: async () => {},
  readPersistedRefreshToken: async () => {
    const c = await Keychain.getGenericPassword({ service: KEYCHAIN_KEY });
    return c ? c.password : null;
  },
  persistRefreshToken: async (token) => {
    await Keychain.setGenericPassword('refresh', token, {
      service: KEYCHAIN_KEY,
      accessible: Keychain.ACCESSIBLE.AFTER_FIRST_UNLOCK,
    });
  },
  clearPersistedRefreshToken: async () => {
    await Keychain.resetGenericPassword({ service: KEYCHAIN_KEY });
  },
});
```

### 3.6. Mobile için backend farkı

Backend mobile için `/api/v1/auth/login` ve `/api/v1/auth/refresh` body'sinde **refresh token döner** (cookie değil). Frontend Keychain'e koyar.

Web için aynı endpoint **httpOnly cookie** olarak döner.

Endpoint **client type'a göre** response davranışı değiştirebilir: header `X-Client-Type: mobile` veya farklı endpoint (`/api/v1/auth/login/mobile`).

## 4. Biz nasıl kullanıyoruz? (Lumix)

### 4.1. Paylaşılan paketler

| Paket | İçerik | Boyut tahmini |
|---|---|---|
| **`@lumix/core`** | Redux store, slices, RTK Query api, types, base query adapter | büyük |
| **`@lumix/ui-shared`** | Platform-agnostic helpers (formatDate, formatCurrency, validators) | küçük |
| **`@lumix/eslint-config`** | ESLint, Prettier, tsconfig base | küçük |

UI component **paylaşılmaz** (web DOM ↔ RN view farkı). Paylaşılan sadece logic + types.

### 4.2. Slice ve API paylaşımı örnek

`packages/core/src/auth/authSlice.ts` (zaten Redux doc'unda gösterildi)

`packages/core/src/auth/api.ts`:

```ts
import { createApi } from '@reduxjs/toolkit/query/react';
import { createLumixBaseQuery } from '../shared/api/baseQuery';

export const lumixApi = createApi({
  reducerPath: 'lumixApi',
  baseQuery: createLumixBaseQuery('/'),
  tagTypes: ['Attendance', 'Conversation', 'Message', 'User', 'Permission'],
  endpoints: () => ({}),
});

export const authApi = lumixApi.injectEndpoints({
  endpoints: (build) => ({
    login: build.mutation({
      query: (body) => ({ url: '/api/v1/auth/login', method: 'POST', body }),
    }),
    logout: build.mutation({
      query: () => ({ url: '/api/v1/auth/logout', method: 'POST' }),
    }),
    refresh: build.mutation({
      query: () => ({ url: '/api/v1/auth/refresh', method: 'POST' }),
    }),
    me: build.query({ query: () => '/api/v1/auth/me' }),
  }),
});

export const { useLoginMutation, useLogoutMutation, useMeQuery } = authApi;
```

### 4.3. Mobile screen kullanımı (web ile aynı hook)

```tsx
// apps/mobile/src/screens/login/LoginScreen.tsx
import { useLoginMutation } from '@lumix/core/auth';
import { useAppDispatch } from '@lumix/core/store';
import { loggedIn } from '@lumix/core/auth';

export function LoginScreen() {
  const [login] = useLoginMutation();
  const dispatch = useAppDispatch();
  // ... web ile %95 aynı kod
}
```

```tsx
// apps/web/src/features/auth/ui/LoginForm.tsx
import { useLoginMutation } from '@lumix/core/auth';
import { useAppDispatch } from '@lumix/core/store';
import { loggedIn } from '@lumix/core/auth';

export function LoginForm() {
  const [login] = useLoginMutation();
  const dispatch = useAppDispatch();
  // ... aynı slice, aynı hook
}
```

### 4.4. `@lumix/core` package.json

```json
{
  "name": "@lumix/core",
  "version": "0.0.0",
  "private": true,
  "type": "module",
  "exports": {
    ".": { "types": "./src/index.ts", "default": "./src/index.ts" },
    "./auth": { "types": "./src/auth/index.ts", "default": "./src/auth/index.ts" },
    "./store": { "types": "./src/store/index.ts", "default": "./src/store/index.ts" },
    "./shared/api": { "types": "./src/shared/api/index.ts", "default": "./src/shared/api/index.ts" }
  },
  "peerDependencies": {
    "@reduxjs/toolkit": "^2.2.0",
    "react": "^18.2.0",
    "react-redux": "^9.1.0"
  },
  "dependencies": {
    "async-mutex": "^0.4.0",
    "zod": "^3.22.0"
  }
}
```

`exports` field ile **deep import** kontrolü; tüketici sadece public API'ı görür.

### 4.5. tsconfig path alias (uygulamalardan core'a)

`apps/web/tsconfig.json`:
```json
{
  "compilerOptions": {
    "paths": {
      "@lumix/core/*": ["../../packages/core/src/*"]
    }
  }
}
```

`apps/mobile/tsconfig.json` aynı şekilde.

### 4.6. Metro config (RN için workspace resolve)

```js
// apps/mobile/metro.config.js
const path = require('path');
const { getDefaultConfig } = require('@react-native/metro-config');

const projectRoot = __dirname;
const workspaceRoot = path.resolve(projectRoot, '../..');

const config = getDefaultConfig(projectRoot);
config.watchFolders = [workspaceRoot];
config.resolver.nodeModulesPaths = [
  path.resolve(projectRoot, 'node_modules'),
  path.resolve(workspaceRoot, 'node_modules'),
];
config.resolver.disableHierarchicalLookup = true;
module.exports = config;
```

### 4.7. i18n paket paylaşımı

```
packages/i18n/
├── locales/
│   ├── tr/
│   │   ├── common.json
│   │   ├── auth.json
│   │   └── ...
│   └── en/
│       └── ...
└── src/
    ├── index.ts
    └── types.ts
```

- Web: `public/locales`'a build sırasında kopyalanır (Vite plugin)
- Mobile: bundle'a embedded olarak yüklenir (`require('@lumix/i18n/locales/tr/common.json')`)

## 5. Neden monorepo? (Alternatifler)

| Alternatif | Neden elendi |
|---|---|
| **Multi-repo (web ayrı, mobile ayrı, core ayrı)** | Sürüm sync zor, PR'lar bağımlılık zinciri, dev experience kötü |
| **npm published package** | İç pakete versiyon yönetimi gereksiz yük; her değişimde publish |
| **Git submodule** | UX kötü, dev her gün ile sıkıntı |
| **Lerna** | pnpm zaten workspace handles; Lerna ek katman |
| **Nx / Turborepo** | Caching çok güzel ama Lumix ölçeği için fazla — gerekirse sonra ekle |
| **pnpm workspace** ✅ | Hafif, hızlı, modern, well-supported |

### Trade-off

- **Build orchestration**: web build mobile build'i etkilemez ama core değişince ikisi de etkilenir. CI'da paralel olabilir.
- **Lock file**: `pnpm-lock.yaml` tek; tüm paketler aynı dep versiyon disipline gider (peer dep çakışmazsa).
- **IDE**: VS Code multi-root workspace ile rahat.

## 6. Pratik örnek — store paylaşımı

```ts
// packages/core/src/store/store.ts
import { configureStore } from '@reduxjs/toolkit';
import authReducer from '../auth/authSlice';
import tenantReducer from '../tenant/tenantSlice';
import uiReducer from '../ui/uiSlice';
import { lumixApi } from '../shared/api/lumixApi';
import { cacheResetMiddleware } from './middleware/cacheReset';

export function createLumixStore() {
  return configureStore({
    reducer: {
      auth: authReducer,
      tenant: tenantReducer,
      ui: uiReducer,
      [lumixApi.reducerPath]: lumixApi.reducer,
    },
    middleware: (getDefault) =>
      getDefault().concat(lumixApi.middleware).concat(cacheResetMiddleware),
  });
}

export const store = createLumixStore();
export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;
```

```ts
// apps/web/src/app/main.tsx
import { setPlatformAdapter } from '@lumix/core/shared/api';
import { store } from '@lumix/core/store';
import '@/shared/api/webAdapter'; // side-effect: adapter set
```

```ts
// apps/mobile/src/App.tsx
import { store } from '@lumix/core/store';
import '@/shared/api/mobileAdapter'; // side-effect: adapter set
```

## 7. Tuzaklar

- **Native module'leri core'a koyma**: `react-native-keychain` core'da değil mobile app'te. Core platform-agnostic kalmalı.
- **Browser-only API'ler core'a koyma**: `document`, `window`, `localStorage` core'da olmaz. Adapter pattern kullan.
- **Cyclic dependency**: `apps/web` → `packages/core` → `apps/web` olmaz; tek yönlü.
- **`workspace:*` yerine `"latest"`**: pnpm install dış paket çeker; intra-monorepo değil. Hep `workspace:*`.
- **React versiyon çakışması**: Web ve mobile farklı React versiyon kullanabilir; core peer dependency olarak en düşük major versiyonu deklare etmeli.
- **TypeScript farkı**: `lib: ['DOM']` web'de var, mobile'da yok. Core'da DOM type'larına bağlanma.
- **`crypto.randomUUID`**: Web'de var, mobile'da polyfill gerek (`react-native-get-random-values`). Core'da fallback yaz.
- **Build cache uniqueness**: pnpm `--filter` ile spesifik paket build et; tüm monorepo build her zaman gerek değil.
- **Test paylaşımı**: Core'a unit test yaz; her uygulamada integration test.
- **Path alias bozulması**: tsconfig.base'te aliases tanımla, app'lerden extends et.
- **Bundle size mobile'da**: Core'a "her şey" koyma; lazy import veya tree-shaking edilebilir hale getir.
- **Mobile'da `fetch credentials`**: `include` kullanma — RN'de cookie semantiği yok; backend body üzerinden refresh.

## 8. Diğer konularla ilişkisi

- [React Native Foundation](./01-react-native-foundation.md) — mobile temel
- [Push Notifications](./03-push-notifications.md) — platform-spesifik native modüller
- [Redux Toolkit](../frontend-architecture/02-redux-toolkit.md) — slice tanımları
- [RTK Query](../frontend-architecture/33-rtk-query.md) — endpoint tanımları
- [Token Storage](../frontend-architecture/06-frontend-token-storage.md) — adapter farkı
- [Form Handling](../frontend-architecture/07-form-handling.md) — Zod schema paylaşımı

## 9. Daha derine

- pnpm workspace: https://pnpm.io/workspaces
- React Native monorepo: https://github.com/byCedric/react-native-monorepo-tools
- Metro config workspace: https://reactnative.dev/docs/metro
- Turborepo (gelecekte): https://turbo.build/repo
- Search keywords:
  - `pnpm workspace react native monorepo`
  - `react native metro symlink workspace`
  - `cross platform redux toolkit shared`
  - `react native keychain refresh token`
  - `monorepo typescript path aliases`

## 10. Sözlük

- **Monorepo** — Birden fazla paketi tek git repository'sinde tutmak.
- **pnpm workspace** — pnpm'in monorepo desteği; `workspace:*` ile local link.
- **`@lumix/core`** — Lumix'in web + mobile paylaşımlı business logic paketi.
- **Platform adapter** — Platform farklılıklarını runtime'da kapsayan arayüz.
- **Peer dependency** — Tüketicinin sağlaması gereken bağımlılık (versiyon çakışmasını önler).
- **Barrel export** — `index.ts` ile paketin public API'ını gruplama.
- **Metro** — React Native'in JS bundler'ı.
- **Symlink** — Sembolik link; pnpm node_modules içinde paketleri böyle bağlar.
- **Path alias** — TypeScript'te `@lumix/core/*` gibi yol kısaltması.
- **Workspace root** — Monorepo'nun en üst klasörü (pnpm-workspace.yaml burada).
