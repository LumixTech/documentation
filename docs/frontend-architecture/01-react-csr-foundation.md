---
title: React CSR Temelleri (React 18 + Vite)
description: Lumix web frontend'inin temeli — React 18, Vite, Client-Side Rendering (CSR), proje yapısı, dev server, build optimizasyonu.
sidebar_position: 1
---

## Bu sayfa ne anlatıyor?

Lumix'in **web frontend'inin temel taşı** olan React 18 + Vite + CSR yığınını anlatır. Yeni gelen geliştirici bu sayfayı okuyunca şunları bilecek:

- React nedir, neden 18 versiyonunu seçtik
- Client-Side Rendering (CSR) ne demek, neden SSR yerine bunu tercih ettik
- Vite neden Create React App'e tercih edildi
- Lumix frontend'inin klasör yapısı ve giriş noktaları (`main.tsx`, `App.tsx`)
- Dev server, hot reload ve production build nasıl çalışıyor
- Bundle splitting, lazy loading ve performans optimizasyonu nasıl yapılıyor

Bu sayfa **tüm diğer frontend doc'larının altyapısı**. FSD, Redux Toolkit, RTK Query, form, i18n — hepsi bu temelin üstüne kuruluyor.

## 1. React nedir? (Sıfırdan)

**React**, kullanıcı arayüzünü **component**'lere bölerek inşa eden bir JavaScript kütüphanesidir. Facebook (Meta) tarafından 2013'te açık kaynak yayınlandı; bugün web frontend'in fiili standardı.

### Günlük hayattan analoji

Bir restoranı düşün. Müşteri masada oturuyor, garson gelip "ne istiyorsunuz?" diye soruyor, sipariş alıp mutfağa götürüyor. React'te de aynı şey:

- **Component** = restoran masası (kendi içinde bütün, başka masayı bilmek zorunda değil)
- **State** = masadaki müşterinin siparişi (değişebilir, değiştiğinde garson yine gelir)
- **Render** = garsonun masaya yeni tabağı koyması (state değişti, ekran güncellendi)
- **Virtual DOM** = mutfak içindeki düzen (gerçek müşteriye gitmeden önce siparişin doğruluğunu kontrol ediyor)

React'in özü tek cümlede: **`UI = f(state)`**. Yani arayüz, state'in bir fonksiyonu. State değişince arayüz otomatik yeniden render edilir.

### React 18'in getirdikleri (özet)

- **Automatic batching** — Birden fazla `setState` tek render'a birleşir (artık Promise/setTimeout içinde de).
- **Concurrent rendering** — React render'ı bölebilir, kritik olmayanı erteleyebilir.
- **`useTransition` / `useDeferredValue`** — Pahalı update'leri "background" olarak işaretleme.
- **Suspense iyileştirmeleri** — Async component'ler için daha iyi loading orchestration.
- **`createRoot` API** — Yeni mount yöntemi (eski `ReactDOM.render` deprecated).

## 2. Hangi problemi çözüyor?

React öncesi UI dünyası:

- **jQuery dönemi**: DOM'u elle manipüle ediyorduk. `document.getElementById('btn').addEventListener(...)`. Karmaşık ekranlarda spagetti kod kaçınılmazdı.
- **State sync sorunu**: "Veri değişti, kaç tane DOM elementinin güncellenmesi lazım?" sorusu her büyük uygulamada bug fabrikasıydı.
- **Tekrar kullanılabilirlik yoktu**: Aynı arayüz parçasını başka sayfada kullanmak için copy-paste yapıyorduk.
- **Test edilemezlik**: DOM'a sıkı bağlı kod, unit test imkansız.

React bunları **declarative + component-based** modelle çözer:

- Sen "ne olmalı"yı yazıyorsun (`<UserCard user={ahmet} />`); React "nasıl güncellensin"i hallediyor.
- Component'ler izole, yeniden kullanılabilir, test edilebilir.

## 3. Nasıl çözüyor? (Mekanizma)

### 3.1. Component ve JSX

```tsx
function Welcome({ name }: { name: string }) {
  return <h1>Merhaba, {name}!</h1>;
}

// Kullanım
<Welcome name="Hüseyin" />
```

JSX, JavaScript'in içinde HTML benzeri syntax. Babel/SWC bunu `React.createElement(...)` çağrısına çevirir.

### 3.2. State ve render döngüsü

```tsx
function Counter() {
  const [count, setCount] = useState(0);

  return (
    <button onClick={() => setCount(count + 1)}>
      Tıklandı: {count}
    </button>
  );
}
```

Akış:

```
1. Kullanıcı butona tıklar
2. setCount(1) çağrılır
3. React: "state değişti, component'i tekrar render etmem lazım"
4. Counter() fonksiyonu yeniden çağrılır
5. JSX → Virtual DOM diff
6. Gerçek DOM sadece değişen yerde güncellenir (text node "0" → "1")
```

### 3.3. CSR (Client-Side Rendering) mekanizması

```
1. Tarayıcı index.html alır (boş <div id="root"></div>)
2. Tarayıcı app.js bundle'ını yükler
3. React Vite ile build edilmiş bundle çalışır
4. createRoot(document.getElementById('root')).render(<App />)
5. App component'i ağacı kurulur
6. İlk render → DOM'a yazılır
7. Kullanıcı etkileşimi → setState → re-render → DOM güncelle
```

İlk yüklemede sunucu **boş HTML + JS bundle** gönderir. Tüm render tarayıcıda olur.

### 3.4. Vite mekanizması

Vite, build tool. İki ana özelliği:

- **Dev modunda**: Native ES Modules (ESM) kullanır. Tarayıcı `import` istediğinde Vite o dosyayı **anlık** transform edip gönderir. Webpack gibi her şeyi önceden bundle etmez. Sonuç: 10 saniye yerine 100ms'de başlar.
- **Production'da**: Rollup ile optimal bundle üretir. Tree-shaking, code-splitting, asset optimization hepsi var.

```
Dev mode:
  tarayıcı → import './App.tsx'
              ↓
  Vite dev server: dosyayı SWC ile transform et, gönder
              ↓
  tarayıcı çalıştırır

Build mode:
  vite build → Rollup → bundle/chunk üretir
            → assets/index-[hash].js, index-[hash].css
```

## 4. Biz nasıl kullanıyoruz? (Lumix)

### 4.1. Versiyon ve araçlar

| Konu | Karar |
|---|---|
| React | **18.3.x** (concurrent mode, automatic batching) |
| Build tool | **Vite 5+** |
| Dil | **TypeScript 5+** (strict mode) |
| Transformer | **SWC** (Vite default) |
| Package manager | **pnpm** (workspace desteği için) |
| Node | **22 LTS** |

### 4.2. Klasör yapısı (FSD ile uyumlu)

```
apps/web/
├── public/
│   └── favicon.svg
├── src/
│   ├── app/
│   │   ├── providers/        # ReduxProvider, RouterProvider, I18nProvider
│   │   ├── router/           # routes.tsx, RoleRoute, ScopeRoute
│   │   ├── store/            # configureStore, root reducer
│   │   ├── styles/           # global.css, theme tokens
│   │   ├── App.tsx
│   │   └── main.tsx          # entry: createRoot + <App />
│   ├── pages/                # route-level screens
│   ├── widgets/              # composed blocks
│   ├── features/             # user actions
│   ├── entities/             # domain models
│   └── shared/
│       ├── api/              # baseQuery, axios instance, types
│       ├── ui/               # generic Button, Modal, Input
│       ├── lib/              # date, money, i18n helpers
│       └── config/           # env, constants
├── index.html
├── vite.config.ts
├── tsconfig.json
└── package.json
```

### 4.3. `main.tsx` (entry point)

```tsx
import { createRoot } from 'react-dom/client';
import { Provider as ReduxProvider } from 'react-redux';
import { RouterProvider } from 'react-router-dom';
import { I18nextProvider } from 'react-i18next';

import { store } from '@/app/store';
import { router } from '@/app/router';
import { i18n } from '@/shared/lib/i18n';
import './app/styles/global.css';

const root = createRoot(document.getElementById('root')!);

root.render(
  <ReduxProvider store={store}>
    <I18nextProvider i18n={i18n}>
      <RouterProvider router={router} />
    </I18nextProvider>
  </ReduxProvider>
);
```

### 4.4. `vite.config.ts` (Lumix standardı)

```ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react-swc';
import path from 'node:path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
      '@app': path.resolve(__dirname, 'src/app'),
      '@pages': path.resolve(__dirname, 'src/pages'),
      '@features': path.resolve(__dirname, 'src/features'),
      '@entities': path.resolve(__dirname, 'src/entities'),
      '@shared': path.resolve(__dirname, 'src/shared'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080', // Kong Gateway local
        changeOrigin: true,
        secure: false,
      },
    },
  },
  build: {
    target: 'es2022',
    sourcemap: true,
    rollupOptions: {
      output: {
        manualChunks: {
          'react-vendor': ['react', 'react-dom', 'react-router-dom'],
          'redux-vendor': ['@reduxjs/toolkit', 'react-redux'],
          'form-vendor': ['react-hook-form', 'zod', '@hookform/resolvers'],
        },
      },
    },
  },
});
```

### 4.5. Neden SSR yok?

Lumix bir **SaaS kurumsal panel** (B2B):

- SEO gereksinimi yok — login arkasındaki ekranlar
- İlk render hızı kritik değil — kullanıcı login olup gün boyu kullanıyor
- Server-side rendering ekstra altyapı yükü (Node SSR server, hidration karmaşıklığı, cache invalidation problemi)
- React Native mobile ile **kod paylaşımı** istiyoruz (Next.js SSR mobile'a uymaz)
- CSR daha **basit dağıtım**: static dosyalar Nginx/Traefik üzerinden servis

İleride landing page / pazarlama sitesi için ayrı Next.js projesi olabilir — ama uygulama tarafı CSR kalır.

## 5. Neden Vite seçildi? (Alternatifler)

### Düşünülen alternatifler

| Alternatif | Neden elendi |
|---|---|
| **Create React App (CRA)** | Deprecated, yavaş dev server, webpack-tabanlı, modern özellikleri yok |
| **Next.js** | SSR/SSG bizim için fazla; dev server da yavaş; mobile paylaşımı zor |
| **Webpack (custom)** | Konfigürasyon yükü çok; Vite dev experience çok daha iyi |
| **Parcel** | Ekosistem küçük; plugin sınırı |
| **Remix** | SSR/loader paradigması bizim CSR + Redux modelimize uymaz |
| **Vite** ✅ | Hızlı dev, modern, geniş ekosistem, React resmi desteği |

### Vite'ın kazandıkları

- **Dev server 100ms'de başlar**, hot reload anlık
- **Native ESM** — modern tarayıcı kullanan dev ortamında bundle bekleme yok
- **Rollup ile prod build** — tree-shaking, code-splitting, asset optimization optimal
- **React, Vue, Svelte, vanilla** — framework-agnostic (gelecekte değişim esnekliği)
- **Plugin ekosistemi** — `vite-plugin-pwa`, `@vitejs/plugin-react-swc`, vs.

### Trade-off

- IE11 desteği yok (umurumuzda değil, B2B SaaS, evergreen tarayıcılar)
- Bazı niş webpack-only plugin'ler Vite'a port edilmemiş (Lumix'te denk gelmedi)

## 6. Pratik örnek — minimal ama gerçek

`pages/attendance/AttendancePage.tsx`:

```tsx
import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';

import { useGetClassroomAttendanceQuery } from '@/entities/attendance/api';
import { AttendanceBoard } from '@/widgets/attendance-board';
import { Spinner } from '@/shared/ui/Spinner';
import { ErrorState } from '@/shared/ui/ErrorState';

export function AttendancePage() {
  const { classroomId } = useParams<{ classroomId: string }>();
  const { t } = useTranslation('attendance');
  const [date, setDate] = useState(() => new Date().toISOString().slice(0, 10));

  const { data, isLoading, isError, refetch } =
    useGetClassroomAttendanceQuery({ classroomId: classroomId!, date });

  if (isLoading) return <Spinner label={t('loading')} />;
  if (isError) return <ErrorState onRetry={refetch} />;
  if (!data) return null;

  return (
    <div className="attendance-page">
      <h1>{t('title', { className: data.classroomName })}</h1>
      <input
        type="date"
        value={date}
        onChange={(e) => setDate(e.target.value)}
      />
      <AttendanceBoard
        classroomId={classroomId!}
        date={date}
        students={data.students}
      />
    </div>
  );
}
```

`app/router/routes.tsx`:

```tsx
import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { lazy, Suspense } from 'react';

import { AppLayout } from '@/app/layouts/AppLayout';
import { RequireAuth } from '@/features/auth/RequireAuth';
import { Spinner } from '@/shared/ui/Spinner';

const AttendancePage = lazy(() =>
  import('@/pages/attendance').then((m) => ({ default: m.AttendancePage })),
);
const MessagesPage = lazy(() =>
  import('@/pages/messages').then((m) => ({ default: m.MessagesPage })),
);

export const router = createBrowserRouter([
  {
    path: '/',
    element: <RequireAuth><AppLayout /></RequireAuth>,
    children: [
      {
        path: 'attendance/:classroomId',
        element: (
          <Suspense fallback={<Spinner />}>
            <AttendancePage />
          </Suspense>
        ),
      },
      {
        path: 'messages',
        element: (
          <Suspense fallback={<Spinner />}>
            <MessagesPage />
          </Suspense>
        ),
      },
    ],
  },
]);
```

### Build optimizasyon kuralları

- **Route-level code splitting** → `React.lazy + Suspense` (yukarıdaki gibi).
- **Vendor split** → `manualChunks` ile React, Redux, form lib ayrı bundle.
- **Image lazy loading** → `<img loading="lazy" />`.
- **Bundle analiz** → `vite-bundle-visualizer` ile düzenli ölçüm.
- **Source map** → production'da aktif (Sentry/Loki source map mapping için).

### Dev server kullanımı

```bash
pnpm dev                # vite dev server (port 5173, proxy /api → Kong)
pnpm build              # production bundle
pnpm preview            # build'i lokal olarak serve et
pnpm typecheck          # tsc --noEmit
pnpm lint               # eslint
```

## 7. Dikkat edilecek tuzaklar

- **`useEffect` infinite loop**: Dependency array yanlış → her render'da yeni referans → her render'da effect → setState → re-render → loop. `useMemo`/`useCallback` veya dependency'i sabitle.
- **Stale closure**: Effect veya event handler içinde state'in eski değerini kullanma. Functional update kullan: `setCount((c) => c + 1)`.
- **`createRoot` iki kez render eder StrictMode'da**: Bu **kasıtlı** (dev modunda effect double-fire ile bug bulma). Production'da bir kez. Effect'leri idempotent yaz.
- **CSR ve SEO**: Public sayfan olursa CSR SEO için kötü; landing page'i ayrı Next.js'te tut.
- **Bundle size patlaması**: `import * from 'lodash'` yapma → tüm lodash bundle'a girer. `import debounce from 'lodash/debounce'`.
- **Vite alias unutmak**: `tsconfig.json` `paths` ile `vite.config.ts` `alias`'ı **eşit** olmalı, yoksa IDE bir şey çalıştırır, build başka.
- **`React.memo` her yere koyma**: Pre-mature optimization. Önce profile et, sonra optimize et.
- **Public klasörden absolute path**: Sadece `public/` içindeki dosyalar `/file.svg` ile erişilebilir. `src/assets/` içindeki dosyalar `import logo from './assets/logo.svg'` ile.
- **Process.env**: Vite'ta `import.meta.env.VITE_*` kullanılır; sadece `VITE_` prefix'li env değişkenleri client'a expose edilir.

## 8. Diğer konularla ilişkisi

- [Frontend FSD ve Feature Boundary'leri](./frontend-fsd-feature-boundaries) — bu temelin üstüne kurulan mimari
- [Redux Toolkit](./02-redux-toolkit.md) — client state yönetimi
- [RTK Query](./33-rtk-query.md) — server state yönetimi
- [Token Storage](./06-frontend-token-storage.md) — auth flow
- [Form Handling](./07-form-handling.md) — React Hook Form + Zod
- [i18n Stratejisi](./08-i18n-strategy.md) — react-i18next
- [Genel Mimari](../00-overview/03-overall-architecture.md) — frontend'in sistem içindeki yeri

## 9. Daha derine

- React resmi dokümantasyonu: https://react.dev/
- React 18 release: https://react.dev/blog/2022/03/29/react-v18
- Vite: https://vitejs.dev/
- SWC: https://swc.rs/
- Search keywords:
  - `react 18 concurrent rendering`
  - `vite vs create react app`
  - `vite manual chunks code splitting`
  - `react csr vs ssr saas dashboard`
  - `react lazy suspense route splitting`

## 10. Sözlük

- **CSR (Client-Side Rendering)** — Sayfanın tarayıcıda JavaScript ile render edilmesi. Sunucu boş HTML + JS bundle gönderir.
- **SSR (Server-Side Rendering)** — Sayfanın sunucuda HTML olarak üretilip gönderilmesi.
- **Vite** — Modern frontend build tool. ESM tabanlı dev server + Rollup tabanlı production bundle.
- **JSX** — JavaScript içine HTML benzeri syntax katmaya yarayan dil uzantısı.
- **Virtual DOM** — React'in iç DOM temsili; gerçek DOM'a yansıtmadan önce diff alır.
- **Concurrent rendering** — React 18'in render'ı bölebilme ve önceliklendirebilme yeteneği.
- **Code splitting** — Bundle'ı parçalara bölüp ihtiyaç anında yükleme.
- **Tree-shaking** — Kullanılmayan kodu bundle'dan çıkarma.
- **SWC** — Rust ile yazılmış JS/TS transformer (Babel alternatifi).
- **HMR (Hot Module Replacement)** — Dev ortamında modülün anlık yenilenmesi.
