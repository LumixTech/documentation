---
title: RTK Query (Server State, Tag-Based Invalidation, Optimistic UI)
description: Lumix server state stratejisi — RTK Query ile createApi, endpoints, tag invalidation, optimistic update, auth refresh interceptor, tenant-aware queries.
sidebar_position: 5
---

## Bu sayfa ne anlatıyor?

Lumix'in **server state** (backend'den gelen data: attendance, mesajlar, kullanıcılar, fatura...) yönetimi için kullandığı **RTK Query** mimarisini anlatır. Bu sayfanın sonunda şunları bileceksin:

- Server state ile client state farkı
- RTK Query nedir, neden Redux Toolkit'in parçası
- `createApi`, endpoint, tag-based invalidation nasıl çalışıyor
- Lumix'in `baseQuery` + auth refresh interceptor + tenant header injection mantığı
- Mesaj listesi ve attendance ekranı için query/mutation planı
- Optimistic UI nasıl kurulur
- Cache lifecycle (`keepUnusedDataFor`, `refetchOnFocus`, `refetchOnReconnect`)

Bu sayfa **TanStack Query doc'unun yerini alır** — Lumix'in resmi server state aracı RTK Query'dir.

## 1. RTK Query nedir? (Sıfırdan)

**RTK Query**, Redux Toolkit'in bir parçası olan **server state cache + invalidation kütüphanesi**. Server'dan veri çeker, cache'ler, stale olduğunda yeniden çeker; mutation yapınca ilgili cache'i invalidate eder.

### Günlük hayattan analoji

Bir kütüphaneci düşün:

- **Query** = "Bana 11-A sınıfının dünkü yoklamasını getir." Kütüphaneci kitabı bulur, sana verir, bir **kopyasını masada tutar** (cache).
- **Cache** = masadaki kopyalar. Aynı kitabı tekrar istersen kütüphaneci raftan değil masadan verir (hızlı).
- **Stale** = "Bu kitabın güncel olup olmadığından emin değilim, yeniden bakayım" durumu. Belirli bir süre sonra (staleTime) cache eskimiş sayılır.
- **Mutation** = "Bu yoklamayı güncelle." Kütüphaneci işlemi yapar, sonra **etkilenen kitapları masadan kaldırır** (invalidation) — bir sonraki istekte taze kopya alırsın.
- **Tag** = kitabın türü/kategorisi. "Tag: attendance, classroom=11A" — mutation aynı tag'a sahip cache'leri tetikler.

### Server state vs client state

| Özellik | Client state (Redux slice) | Server state (RTK Query) |
|---|---|---|
| Kaynak | Tarayıcıda üretilir | Backend'de yaşar |
| Stale olur mu? | Hayır | Evet (başkası değiştirebilir) |
| Eventual consistency? | Yok | Var (mutation sonrası invalidation) |
| Persist? | Bazen (localStorage opsiyonel) | Cache, restart'ta kaybolur |
| Örnekler | sidebar collapsed, theme, aktif tenant ID, toast queue | öğrenci listesi, mesajlar, fatura, izinler |

## 2. Hangi problemi çözüyor?

RTK Query olmadan server data:

- **Loading/error state'i her component'te tekrar tekrar**:
  ```tsx
  const [data, setData] = useState();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState();
  useEffect(() => { /* fetch + try/catch + setState'ler */ }, []);
  ```
  Her component'te 30 satır boilerplate.
- **Cache yok**: Aynı listeyi iki ekranda gösteriyorsan iki kez fetch.
- **Invalidation yok**: Mesaj gönderince mesaj listesi tazelenmiyor → kullanıcı F5'liyor.
- **Race condition**: Hızlı clicks → response sırası karışıyor → eski response yeni state'i ezer.
- **Auth refresh**: 401 alınca her component'in kendi başına refresh denemesi.
- **Tenant context**: Tenant değişince eski tenant'ın cache'i ekranda kalır.

RTK Query bunların hepsini **tek doğru yerden** çözer.

## 3. Nasıl çözüyor? (Mekanizma)

### 3.1. `createApi` — endpoint'leri tanımlama

```ts
import { createApi, fetchBaseQuery } from '@reduxjs/toolkit/query/react';

export const lumixApi = createApi({
  reducerPath: 'lumixApi',
  baseQuery: fetchBaseQuery({ baseUrl: '/' }),
  tagTypes: ['Attendance', 'Conversation', 'Message', 'User', 'Permission'],
  endpoints: () => ({}), // injectEndpoints ile slice başına genişletilir
});
```

`tagTypes` cache invalidation için etiketler.

### 3.2. Endpoint enjeksiyonu (modüler)

```ts
// entities/attendance/api/attendanceApi.ts
import { lumixApi } from '@/shared/api/lumixApi';

export const attendanceApi = lumixApi.injectEndpoints({
  endpoints: (build) => ({
    getClassroomAttendance: build.query<AttendanceResponse, GetParams>({
      query: ({ classroomId, date }) =>
        `/api/v1/attendance/classroom/${classroomId}?date=${date}`,
      providesTags: (_res, _err, arg) => [
        { type: 'Attendance', id: `${arg.classroomId}:${arg.date}` },
      ],
    }),
    markAttendance: build.mutation<void, MarkAttendanceArgs>({
      query: (body) => ({
        url: '/api/v1/attendance/mark',
        method: 'POST',
        body,
      }),
      invalidatesTags: (_res, _err, arg) => [
        { type: 'Attendance', id: `${arg.classroomId}:${arg.date}` },
      ],
    }),
  }),
});

export const { useGetClassroomAttendanceQuery, useMarkAttendanceMutation } =
  attendanceApi;
```

### 3.3. Cache lifecycle

```
Component mount → useQuery → cache'te var mı?
  → varsa cached data döner, arka planda staleTime aştıysa refetch
  → yoksa fetch başlatılır, status='pending'
       ↓
  Fetch tamamlanır → cache'e yazılır → component re-render
       ↓
  Component unmount → keepUnusedDataFor (default 60s) süresince cache durur
       ↓
  Süre geçince cache silinir
```

### 3.4. Tag-based invalidation akışı

```
mutation: markAttendance({ classroomId='11A', date='2026-05-27' })
   invalidatesTags: [{ type: 'Attendance', id: '11A:2026-05-27' }]
                              ↓
       RTK Query: hangi query'ler bu tag'i provide ediyor?
                              ↓
       getClassroomAttendance({ classroomId: '11A', date: '2026-05-27' })
                              ↓
       O query "stale" işaretlenir → refetch
                              ↓
       UI taze data ile re-render
```

## 4. Biz nasıl kullanıyoruz? (Lumix)

### 4.1. `baseQuery` — auth + tenant + refresh interceptor

```ts
import {
  createApi,
  fetchBaseQuery,
  BaseQueryFn,
  FetchArgs,
  FetchBaseQueryError,
} from '@reduxjs/toolkit/query/react';
import { Mutex } from 'async-mutex';

import type { RootState } from '@/app/store';
import { tokenRefreshed, loggedOut } from '@/features/auth/model/authSlice';

const refreshMutex = new Mutex();

const rawBaseQuery = fetchBaseQuery({
  baseUrl: '/',
  credentials: 'include',         // httpOnly refresh cookie
  prepareHeaders: (headers, { getState }) => {
    const state = getState() as RootState;
    const accessToken = state.auth.accessToken;
    const tenantId = state.tenant.activeTenantId;
    if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);
    if (tenantId) headers.set('X-Tenant-Id', tenantId);
    headers.set('X-Correlation-Id', crypto.randomUUID());
    return headers;
  },
});

export const lumixBaseQuery: BaseQueryFn<
  string | FetchArgs,
  unknown,
  FetchBaseQueryError
> = async (args, api, extraOptions) => {
  await refreshMutex.waitForUnlock();
  let result = await rawBaseQuery(args, api, extraOptions);

  if (result.error?.status === 401) {
    if (!refreshMutex.isLocked()) {
      const release = await refreshMutex.acquire();
      try {
        const refresh = await rawBaseQuery(
          { url: '/api/v1/auth/refresh', method: 'POST' },
          api,
          extraOptions,
        );
        if (refresh.data) {
          const { accessToken } = refresh.data as { accessToken: string };
          api.dispatch(tokenRefreshed({ accessToken }));
          // Orijinal isteği yeni token ile tekrarla
          result = await rawBaseQuery(args, api, extraOptions);
        } else {
          api.dispatch(loggedOut());
        }
      } finally {
        release();
      }
    } else {
      // Refresh in progress, bekle ve tekrar dene
      await refreshMutex.waitForUnlock();
      result = await rawBaseQuery(args, api, extraOptions);
    }
  }
  return result;
};

export const lumixApi = createApi({
  reducerPath: 'lumixApi',
  baseQuery: lumixBaseQuery,
  tagTypes: [
    'Attendance', 'Conversation', 'Message', 'User',
    'Permission', 'Tenant', 'Invoice', 'Notification',
  ],
  endpoints: () => ({}),
});
```

### 4.2. Mesaj listesi (Lumix)

```ts
// entities/message/api/messageApi.ts
import { lumixApi } from '@/shared/api/lumixApi';

type Conversation = { id: string; lastMessage: string; unread: number };
type Message = { id: string; conversationId: string; body: string; createdAt: string };

export const messageApi = lumixApi.injectEndpoints({
  endpoints: (build) => ({
    listConversations: build.query<Conversation[], void>({
      query: () => '/api/v1/messages/conversations',
      providesTags: (result) =>
        result
          ? [
              ...result.map(({ id }) => ({ type: 'Conversation' as const, id })),
              { type: 'Conversation', id: 'LIST' },
            ]
          : [{ type: 'Conversation', id: 'LIST' }],
    }),
    listMessages: build.query<Message[], { conversationId: string }>({
      query: ({ conversationId }) =>
        `/api/v1/messages/conversation/${conversationId}`,
      providesTags: (result, _err, arg) =>
        result
          ? [
              ...result.map(({ id }) => ({ type: 'Message' as const, id })),
              { type: 'Message', id: `LIST:${arg.conversationId}` },
            ]
          : [{ type: 'Message', id: `LIST:${arg.conversationId}` }],
    }),
    sendMessage: build.mutation<Message, { conversationId: string; body: string }>({
      query: (body) => ({
        url: `/api/v1/messages/conversation/${body.conversationId}`,
        method: 'POST',
        body: { body: body.body },
      }),
      invalidatesTags: (_res, _err, arg) => [
        { type: 'Message', id: `LIST:${arg.conversationId}` },
        { type: 'Conversation', id: 'LIST' }, // son mesaj/unread güncellensin
      ],
      // OPTIMISTIC UPDATE
      async onQueryStarted(arg, { dispatch, queryFulfilled }) {
        const tempId = `temp-${crypto.randomUUID()}`;
        const patch = dispatch(
          messageApi.util.updateQueryData(
            'listMessages',
            { conversationId: arg.conversationId },
            (draft) => {
              draft.push({
                id: tempId,
                conversationId: arg.conversationId,
                body: arg.body,
                createdAt: new Date().toISOString(),
              });
            },
          ),
        );
        try {
          const { data: serverMessage } = await queryFulfilled;
          // Optimistic mesajı server'dan dönen ile değiştir
          dispatch(
            messageApi.util.updateQueryData(
              'listMessages',
              { conversationId: arg.conversationId },
              (draft) => {
                const i = draft.findIndex((m) => m.id === tempId);
                if (i >= 0) draft[i] = serverMessage;
              },
            ),
          );
        } catch {
          patch.undo(); // rollback
        }
      },
    }),
  }),
});

export const {
  useListConversationsQuery,
  useListMessagesQuery,
  useSendMessageMutation,
} = messageApi;
```

### 4.3. Attendance ekranı (Lumix)

```ts
// entities/attendance/api/attendanceApi.ts
type AttendanceRecord = { studentId: string; status: 'present' | 'absent' | 'late' };

export const attendanceApi = lumixApi.injectEndpoints({
  endpoints: (build) => ({
    getClassroomAttendance: build.query<
      { classroomName: string; students: Array<{ id: string; name: string; status: AttendanceRecord['status'] | null }> },
      { classroomId: string; date: string }
    >({
      query: ({ classroomId, date }) =>
        `/api/v1/attendance/classroom/${classroomId}?date=${date}`,
      providesTags: (_res, _err, arg) => [
        { type: 'Attendance', id: `${arg.classroomId}:${arg.date}` },
      ],
    }),
    submitAttendance: build.mutation<
      void,
      { classroomId: string; date: string; records: AttendanceRecord[] }
    >({
      query: (body) => ({
        url: '/api/v1/attendance/submit',
        method: 'POST',
        body,
      }),
      invalidatesTags: (_res, _err, arg) => [
        { type: 'Attendance', id: `${arg.classroomId}:${arg.date}` },
      ],
    }),
  }),
});

export const {
  useGetClassroomAttendanceQuery,
  useSubmitAttendanceMutation,
} = attendanceApi;
```

### 4.4. Tenant değişimi → cache reset

```ts
// app/store/middleware/tenantChangeMiddleware.ts
import { Middleware } from '@reduxjs/toolkit';
import { activeTenantSwitched, tenantCleared } from '@/entities/tenant/model/tenantSlice';
import { loggedOut } from '@/features/auth/model/authSlice';
import { lumixApi } from '@/shared/api/lumixApi';

export const cacheResetMiddleware: Middleware = (store) => (next) => (action) => {
  const result = next(action);
  if (
    activeTenantSwitched.match(action) ||
    tenantCleared.match(action) ||
    loggedOut.match(action)
  ) {
    store.dispatch(lumixApi.util.resetApiState());
  }
  return result;
};
```

`configureStore` middleware'ine eklenir.

### 4.5. Lumix kuralları

- **Tag isimlendirme**: PascalCase, plural değil singular (`User`, `Conversation`)
- **Tag id formatı**: `LIST` (genel liste tag'ı), `${id}` (tek entity), `LIST:${parentId}` (parent-scoped liste)
- **Tenant scoped query** → tag id'sine tenant koymaya **gerek yok**, çünkü tenant değişince `resetApiState()` çağrılıyor
- **Optimistic update** sadece **rollback edilebildiği** ve **conflict olasılığı düşük** durumlarda
- **`keepUnusedDataFor`**: hassas data için kısa (5s), genel için default (60s)
- **Polling**: gerçek-time için WebSocket kullan, polling sadece fallback olarak

## 5. Neden RTK Query? (Alternatifler)

| Alternatif | Neden elendi |
|---|---|
| **TanStack Query** | Çok iyi ama Redux store ile ayrı — iki cache lifecycle, iki devtools, iki entegrasyon noktası |
| **SWR** | Hafif ama mutation/invalidation TanStack/RTK kadar zengin değil |
| **Apollo Client** | GraphQL'e bağlı (Lumix REST + gRPC, GraphQL yok) |
| **Custom fetch + Redux slice** | Boilerplate cehennemi, baştan yazıyoruz |
| **RTK Query** ✅ | Redux store ile entegre, tek devtools, tek mental model |

### Kazandığımız

- **Tek state lib**: Server + client state aynı Redux store + DevTools
- **TypeScript first-class**: Generic'lerle full inference
- **Tag invalidation**: Açık ve modellenebilir
- **Optimistic update API**: `onQueryStarted` ile temiz
- **`baseQuery` override**: Auth refresh, tenant header tek yerde

### TanStack'ten kaybettiğimiz

- TanStack'in **devtools görselliği** (RTK DevTools query view yeni ama TanStack daha olgun)
- TanStack'in geniş community plugin ekosistemi

### Ne zaman gözden geçiririz?

- Backend GraphQL'e geçerse → Apollo veya urql düşünülür
- TanStack Query Redux entegrasyonunu native verirse

## 6. Pratik örnek (component)

```tsx
import { useState } from 'react';

import {
  useGetClassroomAttendanceQuery,
  useSubmitAttendanceMutation,
} from '@/entities/attendance/api/attendanceApi';

export function AttendanceForm({ classroomId, date }: { classroomId: string; date: string }) {
  const { data, isLoading, isError, refetch } = useGetClassroomAttendanceQuery({
    classroomId,
    date,
  });
  const [submit, { isLoading: isSubmitting }] = useSubmitAttendanceMutation();
  const [records, setRecords] = useState<Record<string, 'present' | 'absent' | 'late'>>({});

  if (isLoading) return <p>Yükleniyor...</p>;
  if (isError) return <button onClick={() => refetch()}>Tekrar dene</button>;
  if (!data) return null;

  const handleSubmit = async () => {
    try {
      await submit({
        classroomId,
        date,
        records: Object.entries(records).map(([studentId, status]) => ({ studentId, status })),
      }).unwrap();
      // invalidatesTags otomatik refetch tetikler
    } catch (e) {
      console.error('Submit failed', e);
    }
  };

  return (
    <div>
      <h2>{data.classroomName} — {date}</h2>
      <ul>
        {data.students.map((s) => (
          <li key={s.id}>
            {s.name}
            <select
              value={records[s.id] ?? s.status ?? ''}
              onChange={(e) => setRecords({ ...records, [s.id]: e.target.value as any })}
            >
              <option value="">—</option>
              <option value="present">Var</option>
              <option value="absent">Yok</option>
              <option value="late">Geç</option>
            </select>
          </li>
        ))}
      </ul>
      <button onClick={handleSubmit} disabled={isSubmitting}>Kaydet</button>
    </div>
  );
}
```

## 7. Tuzaklar

- **Tag id `undefined`**: `providesTags: [{ type: 'User', id: arg.id }]` ama `arg.id` undefined → tag çalışmaz. Defensive check yap.
- **`invalidatesTags`'i `providesTags` ile eşleştirememe**: Mutation invalidate ediyor ama query o tag'i provide etmiyor → cache stale kalır.
- **Optimistic update'te rollback unutmak**: `try/catch` yok → mutation fail olursa UI yanlış state'te kalır.
- **Mutex'siz refresh**: Aynı anda 5 401 → 5 refresh → race + kullanıcı çıkış yapar gibi olur. Mutex zorunlu.
- **Cache silmek için `resetApiState` yerine `invalidateTags`**: tenant değişince invalidate yetmez (eski tenant veriler hâlâ cache'te); tamamen reset gerek.
- **`refetchOnMountOrArgChange: true` her query'ye**: Performans öldürücü; sadece gerçekten her mount'ta taze isteyen sayfalar için.
- **Polling her ekrana**: WebSocket varken polling yapma → backend yoğunluğu.
- **Tag çok generic** (`{ type: 'User', id: 'LIST' }`): bir mutation tüm user listesini invalidate ediyor, oysa sadece belirli tenant'ı. Tag id'sini specific tut.
- **Component içinde `useQuery` koşullu çağırma**: `if (cond) useGetX()` → Hook rule violation. `skip` parametresi kullan: `useGetX(arg, { skip: !cond })`.
- **Mutation response body'sini cache'e yazmayı unutmak**: Mutation server'dan tam obje döndürüyorsa `updateQueryData` ile direkt cache'e yaz, ekstra refetch'ten kaçın.

## 8. Diğer konularla ilişkisi

- [Redux Toolkit](./02-redux-toolkit.md) — RTK Query Redux store içinde yaşar
- [Token Storage](./06-frontend-token-storage.md) — refresh interceptor detayı
- [Permission Cache](./09-frontend-permission-cache.md) — `/me/permissions` RTK Query ile
- [Form Handling](./07-form-handling.md) — mutation tetikleyen form'lar
- [FSD ve Feature Boundary](./frontend-fsd-feature-boundaries) — endpoint'ler hangi katmana
- [Installation/Tenant/Scope](../01-tenancy-and-domain-model/01-installation-tenant-scope.md) — `X-Tenant-Id` header'ın anlamı

## 9. Daha derine

- RTK Query dokümantasyonu: https://redux-toolkit.js.org/rtk-query/overview
- Tag invalidation: https://redux-toolkit.js.org/rtk-query/usage/automated-refetching
- Optimistic updates: https://redux-toolkit.js.org/rtk-query/usage/optimistic-updates
- Search keywords:
  - `rtk query tag invalidation patterns`
  - `rtk query optimistic update rollback`
  - `rtk query auth refresh interceptor mutex`
  - `rtk query tenant aware base query`
  - `rtk query vs tanstack query`

## 10. Sözlük

- **Query** — Server'dan veri okuma (GET).
- **Mutation** — Server'da veri değiştirme (POST/PUT/DELETE).
- **Cache** — RTK Query'nin Redux store'da tuttuğu veri kopyası.
- **Tag** — Cache entry'lerini gruplamak için kullanılan etiket; mutation tag tetikleyerek invalidate eder.
- **`providesTags`** — Bir query'nin hangi tag'leri sağladığı.
- **`invalidatesTags`** — Bir mutation'ın hangi tag'leri invalidate ettiği.
- **Stale** — Cache'in tazeliği geçmiş, refetch edilebilir durumu.
- **`baseQuery`** — Tüm endpoint'lerin altında çalışan HTTP istemci fonksiyonu.
- **`prepareHeaders`** — Her isteğin header'larını programatik olarak ayarlama yeri.
- **`onQueryStarted`** — Mutation/query başladığında çalışan async lifecycle hook (optimistic update yeri).
- **`util.updateQueryData`** — Cache'i imperative olarak güncelleme API'si.
- **`util.resetApiState`** — Tüm cache'i sıfırlama (logout, tenant switch).
