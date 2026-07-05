---
title: Redux Toolkit (createSlice, configureStore)
description: Lumix client state yönetimi — Redux Toolkit ile auth, tenant, UI slice yapısı; configureStore; selectors; typed hooks.
sidebar_position: 4
---

## Bu sayfa ne anlatıyor?

Lumix'in **client state** (UI state, auth context, tenant context) yönetimi için kullandığı **Redux Toolkit (RTK)** mimarisini anlatır. Bu sayfanın sonunda şunları bileceksin:

- Redux nedir, neden hâlâ kullanılıyor
- Redux Toolkit nedir, klasik Redux'tan farkı ne
- `createSlice`, `configureStore`, `createAsyncThunk` ne işe yarar
- Lumix'te `authSlice`, `tenantSlice`, `uiSlice` nasıl tasarlandı
- Typed hooks (`useAppSelector`, `useAppDispatch`) ile TypeScript güvenliği
- Server state ile client state ayrımı (Redux vs RTK Query)

Bu sayfa **frontend'in client-side veri katmanını** anlatır; server state için ayrı doc var: [RTK Query](./33-rtk-query.md).

## 1. Redux nedir? (Sıfırdan)

**Redux**, JavaScript uygulamaları için **öngörülebilir state container**'ı. Yani uygulamanın "şu anki durumu"nu **tek bir merkezi yerde** tutar ve değişiklikleri kontrollü bir şekilde yapmana izin verir.

### Günlük hayattan analoji

Bir restoran zincirinin **merkez ofisini** düşün:

- **Store** = merkez ofis. Tüm şubelerin sipariş, stok, çalışan listesi orada.
- **State** = ofiste duran defter. O anki tüm bilgi.
- **Action** = şubeden gelen telgraf. "Kadıköy şubesi 5 numara masaya hesap kesti."
- **Reducer** = ofis görevlisi. Telgrafı alır, defterin ilgili sayfasına işler. Defteri **yeni baştan yazar** (eski versiyon arşivde kalır).
- **Selector** = "Bana bugünkü toplam ciroyu söyle" diye defteri okuyan müfettiş.

Redux'ın "saf"lığı şuradan: defter **immutable** (yeni state eski state'i değiştirmez, yeni obje üretir), her değişim **action** ile yapılır (kim ne yaptı belli), her değişim **deterministic** (aynı action + aynı state → aynı sonuç).

### Redux'ın temel akışı

```
Component dispatch(action)
        ↓
  ┌──────────────┐
  │   Action     │  { type: 'auth/loggedIn', payload: { user, token } }
  └──────────────┘
        ↓
  ┌──────────────┐
  │   Reducer    │  (state, action) => newState
  └──────────────┘
        ↓
  ┌──────────────┐
  │    Store     │  state güncellenir
  └──────────────┘
        ↓
  Component re-render (useSelector ile)
```

## 2. Hangi problemi çözüyor?

Redux öncesi React state hayatı:

- **Prop drilling**: `App → Layout → Sidebar → UserMenu → LogoutButton` zincirinde `user` prop'unu beş seviye aşağı geçiriyordun. Ara component'ler ne kullanıyor ne ne yapıyor.
- **State eşleştirme sorunu**: İki farklı component'in aynı state'i bilmesi gerekiyor (örn. theme dark mode + sidebar collapse). React Context yetersiz kalıyor karmaşık state'te.
- **State değişimlerini takip edememe**: "Bu değer nereden değişti?" sorusu cevapsız.
- **Time-travel debugging yok**: Bug oluşunca "şu üç state arasında ne oldu" diye geri sarma yok.

Redux bunları çözer:

- **Tek source of truth**: Tüm UI state tek store'da.
- **Action log**: Her değişim bir action; Redux DevTools ile tüm tarihçeyi görürsün.
- **Immutable update**: State asla mutate edilmez; yeni obje üretilir → React `===` ile referans karşılaştırması yapabilir → gereksiz render olmaz.
- **Predictability**: Pure function (reducer) + immutable state = test edilebilir, debug edilebilir.

## 3. Redux Toolkit nasıl çözüyor? (Mekanizma)

Klasik Redux **çok boilerplate** içeriyordu:
- Action type constant'ları (`const LOGIN_SUCCESS = 'auth/loginSuccess'`)
- Action creator fonksiyonları (`function loginSuccess(user) { return { type: LOGIN_SUCCESS, payload: user } }`)
- Reducer içinde switch-case
- Immutable update için spread (`return { ...state, user: action.payload }`)
- Thunk middleware kurmak

**Redux Toolkit (RTK)** bunların hepsini paket eder:

### 3.1. `createSlice` — tek dosyada slice tanımı

```ts
import { createSlice, PayloadAction } from '@reduxjs/toolkit';

type AuthState = {
  user: { id: string; email: string } | null;
  status: 'idle' | 'authenticating' | 'authenticated' | 'error';
};

const authSlice = createSlice({
  name: 'auth',
  initialState: { user: null, status: 'idle' } satisfies AuthState as AuthState,
  reducers: {
    loggedIn(state, action: PayloadAction<{ user: AuthState['user'] }>) {
      state.user = action.payload.user;       // Immer ile "mutate" yazabilirsin
      state.status = 'authenticated';
    },
    loggedOut(state) {
      state.user = null;
      state.status = 'idle';
    },
  },
});

export const { loggedIn, loggedOut } = authSlice.actions;
export default authSlice.reducer;
```

Kazandığımız:

- Action type otomatik (`'auth/loggedIn'`)
- Action creator otomatik (`loggedIn({ user })`)
- Reducer'da "mutate" yazıyoruz ama arka planda **Immer** immutable update yapar
- TypeScript inference tam çalışıyor

### 3.2. `configureStore` — kurulum tek satır

```ts
import { configureStore } from '@reduxjs/toolkit';
import authReducer from '@/features/auth/model/authSlice';
import tenantReducer from '@/entities/tenant/model/tenantSlice';
import uiReducer from '@/shared/model/uiSlice';
import { lumixApi } from '@/shared/api/lumixApi';

export const store = configureStore({
  reducer: {
    auth: authReducer,
    tenant: tenantReducer,
    ui: uiReducer,
    [lumixApi.reducerPath]: lumixApi.reducer,
  },
  middleware: (getDefaultMiddleware) =>
    getDefaultMiddleware().concat(lumixApi.middleware),
  devTools: import.meta.env.MODE !== 'production',
});

export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;
```

Tek satırda:
- Redux DevTools entegrasyonu
- Default middleware (thunk, immutability check, serializability check)
- RTK Query entegrasyonu (aşağıda anlatılacak)

### 3.3. Typed hooks

```ts
// app/store/hooks.ts
import { useDispatch, useSelector } from 'react-redux';
import type { TypedUseSelectorHook } from 'react-redux';
import type { RootState, AppDispatch } from './store';

export const useAppDispatch: () => AppDispatch = useDispatch;
export const useAppSelector: TypedUseSelectorHook<RootState> = useSelector;
```

Bundan sonra component'lerde:

```tsx
import { useAppSelector, useAppDispatch } from '@/app/store/hooks';
import { loggedOut } from '@/features/auth/model/authSlice';

function LogoutButton() {
  const dispatch = useAppDispatch();
  const user = useAppSelector((s) => s.auth.user); // tam typed

  return (
    <button onClick={() => dispatch(loggedOut())}>
      {user?.email} — Çıkış
    </button>
  );
}
```

## 4. Biz nasıl kullanıyoruz? (Lumix)

### 4.1. Lumix'in slice topolojisi

| Slice | Ne tutar | Nerede yaşar |
|---|---|---|
| **`authSlice`** | `user`, `accessToken`, `permissions`, `roles`, status | `features/auth/model/` |
| **`tenantSlice`** | aktif `tenantId`, `tenantIds[]` (multi-tenant), tenant config | `entities/tenant/model/` |
| **`uiSlice`** | sidebar collapsed, theme (light/dark), locale, snackbar queue | `shared/model/` |
| **`permissionSlice`** | (opsiyonel) UI rendering için cache'lenmiş permission listesi | `features/auth/model/` |
| **`lumixApi`** | RTK Query (server state) | `shared/api/` |

**Önemli prensip**: Her **server data**'sı Redux store'a değil **RTK Query** cache'ine gider. Redux store sadece **client UI state + auth/tenant context**.

### 4.2. `authSlice` (Lumix gerçek hali)

```ts
import { createSlice, PayloadAction } from '@reduxjs/toolkit';
import { lumixApi } from '@/shared/api/lumixApi';

export type AuthUser = {
  id: string;
  email: string;
  fullName: string;
  roles: string[];
  installationId: string;
};

export type AuthState = {
  user: AuthUser | null;
  accessToken: string | null;
  permissions: string[];
  status: 'idle' | 'authenticating' | 'authenticated' | 'error';
  error: string | null;
};

const initialState: AuthState = {
  user: null,
  accessToken: null,
  permissions: [],
  status: 'idle',
  error: null,
};

const authSlice = createSlice({
  name: 'auth',
  initialState,
  reducers: {
    authenticating(state) {
      state.status = 'authenticating';
      state.error = null;
    },
    loggedIn(
      state,
      action: PayloadAction<{
        user: AuthUser;
        accessToken: string;
        permissions: string[];
      }>,
    ) {
      state.user = action.payload.user;
      state.accessToken = action.payload.accessToken;
      state.permissions = action.payload.permissions;
      state.status = 'authenticated';
      state.error = null;
    },
    tokenRefreshed(state, action: PayloadAction<{ accessToken: string }>) {
      state.accessToken = action.payload.accessToken;
    },
    loggedOut(state) {
      state.user = null;
      state.accessToken = null;
      state.permissions = [];
      state.status = 'idle';
      state.error = null;
    },
    authError(state, action: PayloadAction<string>) {
      state.status = 'error';
      state.error = action.payload;
    },
  },
});

export const { authenticating, loggedIn, tokenRefreshed, loggedOut, authError } =
  authSlice.actions;

// Selectors
export const selectAuth = (s: { auth: AuthState }) => s.auth;
export const selectIsAuthenticated = (s: { auth: AuthState }) =>
  s.auth.status === 'authenticated' && !!s.auth.user;
export const selectAccessToken = (s: { auth: AuthState }) => s.auth.accessToken;
export const selectPermissions = (s: { auth: AuthState }) => s.auth.permissions;
export const selectHasPermission =
  (perm: string) => (s: { auth: AuthState }) =>
    s.auth.permissions.includes(perm);

export default authSlice.reducer;
```

### 4.3. `tenantSlice`

```ts
import { createSlice, PayloadAction } from '@reduxjs/toolkit';

export type TenantState = {
  activeTenantId: string | null;       // tek tenant
  tenantIds: string[];                 // multi-tenant (bölge müdürü)
  tenantConfig: {
    timezone: string;
    locale: string;
    currency: string;
  } | null;
};

const initialState: TenantState = {
  activeTenantId: null,
  tenantIds: [],
  tenantConfig: null,
};

const tenantSlice = createSlice({
  name: 'tenant',
  initialState,
  reducers: {
    tenantsAssigned(
      state,
      action: PayloadAction<{ tenantIds: string[]; activeTenantId: string }>,
    ) {
      state.tenantIds = action.payload.tenantIds;
      state.activeTenantId = action.payload.activeTenantId;
    },
    activeTenantSwitched(state, action: PayloadAction<string>) {
      if (state.tenantIds.includes(action.payload)) {
        state.activeTenantId = action.payload;
      }
    },
    tenantConfigLoaded(
      state,
      action: PayloadAction<TenantState['tenantConfig']>,
    ) {
      state.tenantConfig = action.payload;
    },
    tenantCleared(state) {
      state.activeTenantId = null;
      state.tenantIds = [];
      state.tenantConfig = null;
    },
  },
});

export const {
  tenantsAssigned,
  activeTenantSwitched,
  tenantConfigLoaded,
  tenantCleared,
} = tenantSlice.actions;

export const selectActiveTenantId = (s: { tenant: TenantState }) =>
  s.tenant.activeTenantId;
export const selectTenantIds = (s: { tenant: TenantState }) =>
  s.tenant.tenantIds;
export const selectIsMultiTenant = (s: { tenant: TenantState }) =>
  s.tenant.tenantIds.length > 1;

export default tenantSlice.reducer;
```

### 4.4. `uiSlice`

```ts
import { createSlice, PayloadAction, nanoid } from '@reduxjs/toolkit';

type ToastType = 'info' | 'success' | 'warning' | 'error';
type Toast = { id: string; type: ToastType; message: string };

export type UiState = {
  sidebarCollapsed: boolean;
  theme: 'light' | 'dark';
  toasts: Toast[];
};

const initialState: UiState = {
  sidebarCollapsed: false,
  theme: 'light',
  toasts: [],
};

const uiSlice = createSlice({
  name: 'ui',
  initialState,
  reducers: {
    sidebarToggled(state) {
      state.sidebarCollapsed = !state.sidebarCollapsed;
    },
    themeChanged(state, action: PayloadAction<UiState['theme']>) {
      state.theme = action.payload;
    },
    toastPushed: {
      reducer(state, action: PayloadAction<Toast>) {
        state.toasts.push(action.payload);
      },
      prepare(input: { type: ToastType; message: string }) {
        return { payload: { id: nanoid(), ...input } };
      },
    },
    toastDismissed(state, action: PayloadAction<string>) {
      state.toasts = state.toasts.filter((t) => t.id !== action.payload);
    },
  },
});

export const { sidebarToggled, themeChanged, toastPushed, toastDismissed } =
  uiSlice.actions;

export default uiSlice.reducer;
```

### 4.5. Cross-slice etkileşim — `extraReducers`

Logout olduğunda tenant'ı da temizle:

```ts
import { createSlice } from '@reduxjs/toolkit';
import { loggedOut } from '@/features/auth/model/authSlice';

const tenantSlice = createSlice({
  name: 'tenant',
  initialState,
  reducers: { /* ... */ },
  extraReducers: (builder) => {
    builder.addCase(loggedOut, (state) => {
      state.activeTenantId = null;
      state.tenantIds = [];
      state.tenantConfig = null;
    });
  },
});
```

## 5. Neden Redux Toolkit seçildi? (Alternatifler)

| Alternatif | Neden elendi |
|---|---|
| **Klasik Redux** | Boilerplate çok, productivity düşük |
| **Zustand** | Daha basit ama RTK Query gibi entegre server state çözümü yok; ekosistem ufak |
| **Jotai/Recoil** | Atomic state iyi ama ekibin Redux deneyimi var, RTK Query'nin sıkı entegrasyonu yok |
| **MobX** | "Magic" çok; debugging zor; immutable model değil |
| **Context + useReducer** | Küçük uygulama için tamam; performans (her context değişiminde tüm consumer render) ve DevTools eksik |
| **React Query alone** | Server state çözer ama client state için ek lib gerekir; tek araç istiyoruz |
| **Redux Toolkit** ✅ | Boilerplate az, TypeScript first-class, RTK Query ile birleşik server+client state |

### Kabul ettiğimiz trade-off

- **Learning curve**: Yeni gelene "store, action, reducer, selector" anlatmak Zustand'a göre daha çetin. → Bunu doc'la çözüyoruz.
- **Bundle size**: ~30KB. Önemli değil (toplam bundle ile karşılaştır).
- **Mental overhead**: Server state RTK Query'de, client state Redux'ta — ayrımı çekirdek olarak öğretiyoruz.

### Ne zaman tekrar gözden geçiririz?

- React resmi olarak "Redux gereksiz" diye bir state çözümü çıkarırsa
- React Native + Web kod paylaşımı bozulursa
- Ekibin %80'i Zustand isterse (demokrasi)

## 6. Pratik örnek (Lumix login akışı)

`features/auth/api/authApi.ts`:

```ts
import { lumixApi } from '@/shared/api/lumixApi';
import type { LoginRequest, LoginResponse } from './types';

export const authApi = lumixApi.injectEndpoints({
  endpoints: (build) => ({
    login: build.mutation<LoginResponse, LoginRequest>({
      query: (body) => ({
        url: '/api/v1/auth/login',
        method: 'POST',
        body,
        credentials: 'include', // httpOnly refresh cookie
      }),
    }),
    me: build.query<{ user: AuthUser; permissions: string[] }, void>({
      query: () => '/api/v1/auth/me',
    }),
  }),
});

export const { useLoginMutation, useMeQuery } = authApi;
```

`features/auth/ui/LoginForm.tsx`:

```tsx
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';

import { useAppDispatch } from '@/app/store/hooks';
import { authenticating, loggedIn, authError } from '../model/authSlice';
import { tenantsAssigned } from '@/entities/tenant/model/tenantSlice';
import { useLoginMutation } from '../api/authApi';

type FormValues = { email: string; password: string };

export function LoginForm() {
  const { register, handleSubmit, formState: { isSubmitting } } =
    useForm<FormValues>();
  const [login] = useLoginMutation();
  const dispatch = useAppDispatch();
  const navigate = useNavigate();

  const onSubmit = async (values: FormValues) => {
    dispatch(authenticating());
    try {
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
    } catch (e: any) {
      dispatch(authError(e?.data?.detail ?? 'Giriş başarısız'));
    }
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)}>
      <input type="email" {...register('email', { required: true })} />
      <input type="password" {...register('password', { required: true })} />
      <button type="submit" disabled={isSubmitting}>Giriş</button>
    </form>
  );
}
```

`features/auth/ui/PermissionGate.tsx`:

```tsx
import { useAppSelector } from '@/app/store/hooks';
import { selectHasPermission } from '../model/authSlice';

export function PermissionGate({
  permission,
  children,
  fallback = null,
}: {
  permission: string;
  children: React.ReactNode;
  fallback?: React.ReactNode;
}) {
  const allowed = useAppSelector(selectHasPermission(permission));
  return <>{allowed ? children : fallback}</>;
}

// Kullanım
<PermissionGate permission="attendance:write">
  <button>Yoklama Al</button>
</PermissionGate>
```

## 7. Tuzaklar

- **State'i mutate etme (Immer dışında)**: `createSlice` içinde mutate sözdizimi serbest çünkü Immer var. Selector veya başka yerde aynı şeyi yapma → bug.
- **Server state'i Redux'a koyma**: Mesaj listesi, attendance, kullanıcı listesi — bunlar **RTK Query** cache'inde durur. Redux store'a kopyalama; sync problemi başlar.
- **`useSelector` ile büyük obje seçmek**: `useSelector((s) => s.entities)` — bu obje her değişiklikte yeni referans olur → tüm consumer re-render. Spesifik field seç.
- **Selector'da yeni obje üretmek**: `useSelector((s) => ({ a: s.x, b: s.y }))` — her render'da yeni obje, sonsuz re-render. **`createSelector` (reselect)** veya `useSelector` + ayrı çağrı kullan.
- **Action payload'da non-serializable obje**: `Date`, `Map`, function — serializability check uyarı verir; storage/devtools kırılır. ISO string kullan.
- **`useDispatch` typed olmayan**: Plain `useDispatch` thunk'u type-check etmez. **`useAppDispatch`** kullan.
- **Slice'lar arası direct import**: `authSlice`'tan `tenantSlice`'a action dispatch çağırma. **`extraReducers`** ile cross-slice listen yap.
- **Persisting yanlış slice**: `redux-persist` ile `auth.accessToken`'ı localStorage'a yazma — XSS riski. Lumix'te access token Redux'ta in-memory, refresh httpOnly cookie'de. Detay: [Token Storage](./06-frontend-token-storage.md).
- **Slice ismi çakışma**: `name: 'auth'` iki yerde olmazsa action type collision. CI gate yaz.

## 8. Diğer konularla ilişkisi

- [React CSR Temelleri](./01-react-csr-foundation.md) — bu mimari Redux'tan önce
- [RTK Query](./33-rtk-query.md) — server state Redux ile aynı store'da
- [Token Storage](./06-frontend-token-storage.md) — auth token nasıl saklanıyor
- [Permission Cache](./09-frontend-permission-cache.md) — permission UI rendering
- [FSD ve Feature Boundary](./frontend-fsd-feature-boundaries) — slice'lar FSD katmanlarına nasıl oturuyor
- [Installation/Tenant/Scope](../01-tenancy-and-domain-model/01-installation-tenant-scope.md) — tenant slice'ı doğuran multi-tenancy modeli

## 9. Daha derine

- Redux Toolkit dokümantasyonu: https://redux-toolkit.js.org/
- Redux Style Guide: https://redux.js.org/style-guide/
- Immer: https://immerjs.github.io/immer/
- Search keywords:
  - `redux toolkit createslice typescript`
  - `redux toolkit vs zustand vs jotai`
  - `redux toolkit extraReducers cross slice`
  - `redux store best practices typescript`
  - `redux selector memoization reselect`

## 10. Sözlük

- **Store** — Redux'ın tüm state'i tuttuğu merkezi obje.
- **Slice** — Bir alandaki state + reducer + action'ları gruplayan modül (Redux Toolkit konsepti).
- **Action** — State değişimini tetikleyen mesaj (`{ type, payload }`).
- **Reducer** — `(state, action) => newState` pure function.
- **Selector** — State'ten parça okuyan fonksiyon (`(state) => state.auth.user`).
- **Immer** — "Mutate" yazıp arka planda immutable kopya üreten library.
- **Thunk** — Async action; `dispatch` ve `getState` parametre alan fonksiyon.
- **DevTools** — Redux'ın tarayıcı eklentisi; action geçmişi, time-travel.
- **Typed hooks** — `useAppDispatch` / `useAppSelector` ile TypeScript safety.
- **RootState** — `ReturnType<typeof store.getState>` ile elde edilen typed state.
