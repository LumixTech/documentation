---
title: Permission Cache ve Scope-Aware UI
description: Lumix frontend permission cache stratejisi — /me/permissions, RTK Query tag invalidation, PermissionChanged WebSocket event, scope-aware UI rendering.
sidebar_position: 9
---

## Bu sayfa ne anlatıyor?

Lumix'te bir kullanıcının **ne yapabileceği** (permission) ve **ne kadarını görebileceği** (scope) UI'da nasıl yansıtılır? Bu sayfa şunları anlatır:

- `/me/permissions` endpoint'i ve frontend cache'i
- Permission değişikliği (admin yetki ekledi/çıkardı) → UI nasıl anlık güncellenir
- WebSocket `PermissionChanged` event ile RTK Query cache invalidation
- `<PermissionGate />` ve `<ScopeGate />` component'leri
- Route-level guard'lar (`RequireAuth`, `RequirePermission`)
- Sidebar/menu rendering — gizli butonlar
- Backend nihai otorite (frontend rendering güvenlik DEĞİL)

Bu sayfa **[Hibrit Authorization Model](../security-compliance/hybrid-rbac-abac-authorization)** doc'unun frontend ayağıdır.

## 1. Permission ve scope (Sıfırdan)

İki kavram karıştırılır:

- **Permission** = "**ne** yapabilirim?" → `attendance:write`, `payment:refund`, `user:create`
- **Scope** = "**kimin/neyin üzerinde**?" → "11-A ve 12-B sınıflarında", "Kadıköy şubesinde"

İkisi birlikte: Hüseyin'in `attendance:write` permission'ı + scope'u `class_ids=[11-A,12-B]` = "Yoklama alabilir AMA sadece bu iki sınıfta."

Detaylı backend modeli: [Installation/Tenant/Scope](../tenancy-and-domain-model/installation-tenant-scope), [Hibrit Authorization](../security-compliance/hybrid-rbac-abac-authorization).

### Günlük hayattan analoji

Bir hastanede:

- **Permission** = doktorluk diploması ("reçete yazabilir, ameliyat yapabilir...")
- **Scope** = hangi departmana atandığın ("dahiliye servisi, 3-5 numaralı odalar")
- **UI** = doktor odasındaki yetkilerine göre yanan/sönen butonlar

Frontend doktorun ekranında "ameliyat butonu"nu göstermez (yetki yok). Ama doktorun yetkisi olsa bile "kalp cerrahisi odası" butonu görünmez (scope yok).

## 2. Hangi problemi çözüyor?

Cache yoksa veya kötü tasarlanırsa:

- **Her render permission API çağrısı** → backend bombardımanı, yavaş UI
- **Permission değişti ama UI eski** → kullanıcı butona basıyor, 403 alıyor, kafası karışıyor
- **localStorage'da uzun süre cache** → admin yetki çıkardı ama kullanıcının ekranında hâlâ buton görünüyor (revoke gecikmesi)
- **Sadece backend kontrolü** → kullanıcı yetkisiz butonları görüyor, deneme yapıyor, hata mesajları
- **Sadece frontend kontrolü** → frontend kodu manipüle edilebilir, **güvenlik açığı**

Lumix'in çözümü:

- Frontend cache **UX iyileştirme amaçlı** (yetkisiz buton göstermemek)
- Backend nihai otorite (her endpoint ayrıca `@PreAuthorize` ile korunur)
- Permission değişimi **WebSocket event** ile gerçek-time invalidate
- Token revoke + force refresh kuralı backend tarafında zaten var (bkz: tech stack §10)

## 3. Nasıl çözüyor? (Mekanizma)

### 3.1. Login sonrası permission yükleme

```
1. Login mutation → backend
2. Backend response:
   {
     accessToken, user,
     permissions: ["attendance:write", "messages:send", ...],
     roles: ["teacher"],
     tenantIds: [...],
     scopes: [
       { tenantId: "uuid-kadikoy", type: "class", targetIds: ["11A", "12B"] }
     ]
   }
3. Redux: dispatch(loggedIn({ user, accessToken, permissions, roles }))
4. RTK Query: getMyPermissions cache'ine de yazılır (initial entry)
5. UI: <PermissionGate> ve sidebar render
```

### 3.2. Permission değişimi (admin yetki ekledi/çıkardı)

```
Admin panel → POST /api/v1/users/{userId}/permissions
              ↓
Backend identity-service:
  - DB update
  - User token revoke (Redis: token:{jti} status='revoked')
  - Kafka event: PermissionChangedV1 { userId, tenantId }
              ↓
WebSocket service: STOMP topic /user/{userId}/permission-changed
              ↓
Frontend WebSocket client:
  on message 'permission-changed':
    dispatch(lumixApi.util.invalidateTags(['Permission', 'CurrentUser']))
    ↓
RTK Query: getMyPermissions refetch
    ↓
Backend response: yeni permissions (eski token expired? → 401 → auto refresh)
    ↓
Redux: dispatch(permissionsUpdated(newPermissions))
    ↓
UI re-render: butonlar görünür/gizlenir, route'lar güncellenir
```

### 3.3. WebSocket yoksa fallback

- **Tab focus**: `refetchOnFocus: true` ile permission refetch
- **Polling**: 5dk'da bir background refetch (degraded mode)
- **Optimistic UI**: kullanıcı butona basıyor → backend `403` → toast: "Yetkiniz değişmiş, yenileniyor..."

## 4. Biz nasıl kullanıyoruz? (Lumix)

### 4.1. RTK Query endpoint

```ts
// entities/permission/api/permissionApi.ts
import { lumixApi } from '@/shared/api/lumixApi';

type Scope = { tenantId: string; type: 'school' | 'class' | 'student'; targetIds: string[] };

export type MyPermissionsResponse = {
  permissions: string[];      // ["attendance:write", "messages:send", ...]
  roles: string[];            // ["teacher"]
  scopes: Scope[];
};

export const permissionApi = lumixApi.injectEndpoints({
  endpoints: (build) => ({
    getMyPermissions: build.query<MyPermissionsResponse, void>({
      query: () => '/api/v1/me/permissions',
      providesTags: ['Permission', 'CurrentUser'],
      keepUnusedDataFor: 600, // 10 dakika cache
    }),
  }),
});

export const { useGetMyPermissionsQuery } = permissionApi;
```

### 4.2. `<PermissionGate />` component

```tsx
// features/auth/ui/PermissionGate.tsx
import { useGetMyPermissionsQuery } from '@/entities/permission/api/permissionApi';

type Props = {
  permission: string | string[];  // tek veya çoklu (AND)
  fallback?: React.ReactNode;
  children: React.ReactNode;
};

export function PermissionGate({ permission, fallback = null, children }: Props) {
  const { data } = useGetMyPermissionsQuery();
  if (!data) return null;

  const required = Array.isArray(permission) ? permission : [permission];
  const allowed = required.every((p) => data.permissions.includes(p));

  return <>{allowed ? children : fallback}</>;
}

// hook variant
export function useHasPermission(permission: string | string[]) {
  const { data } = useGetMyPermissionsQuery();
  if (!data) return false;
  const required = Array.isArray(permission) ? permission : [permission];
  return required.every((p) => data.permissions.includes(p));
}
```

### 4.3. `<ScopeGate />` component

```tsx
// features/auth/ui/ScopeGate.tsx
import { useGetMyPermissionsQuery } from '@/entities/permission/api/permissionApi';
import { useAppSelector } from '@/app/store/hooks';

type Props = {
  resource: { type: 'school' | 'class' | 'student'; id: string };
  fallback?: React.ReactNode;
  children: React.ReactNode;
};

export function ScopeGate({ resource, fallback = null, children }: Props) {
  const { data } = useGetMyPermissionsQuery();
  const activeTenantId = useAppSelector((s) => s.tenant.activeTenantId);
  if (!data || !activeTenantId) return null;

  const scope = data.scopes.find((s) => s.tenantId === activeTenantId);
  if (!scope) return <>{fallback}</>;

  // School-level scope: tüm child resource'lar erişilebilir
  if (scope.type === 'school') return <>{children}</>;
  // Class-level: kullanıcı sadece o sınıflara erişebilir
  if (scope.type === 'class' && resource.type === 'class')
    return <>{scope.targetIds.includes(resource.id) ? children : fallback}</>;
  // Class-level + student resource → ek logic backend'de; UI optimistik gösterir
  // Student-level
  if (scope.type === 'student' && resource.type === 'student')
    return <>{scope.targetIds.includes(resource.id) ? children : fallback}</>;

  return <>{fallback}</>;
}
```

### 4.4. Route-level guard'lar

```tsx
// features/auth/ui/RequirePermission.tsx
import { Navigate, Outlet } from 'react-router-dom';
import { useHasPermission } from './PermissionGate';

export function RequirePermission({ permission }: { permission: string | string[] }) {
  const allowed = useHasPermission(permission);
  if (!allowed) return <Navigate to="/forbidden" replace />;
  return <Outlet />;
}

// routes.tsx kullanımı
{
  path: 'admin/users',
  element: <RequirePermission permission="user:manage" />,
  children: [
    { index: true, element: <UserListPage /> },
    { path: ':id', element: <UserDetailPage /> },
  ],
}
```

### 4.5. WebSocket invalidation

```tsx
// app/providers/PermissionWatcher.tsx
import { useEffect } from 'react';
import { useAppDispatch, useAppSelector } from '@/app/store/hooks';
import { lumixApi } from '@/shared/api/lumixApi';
import { useStompClient } from '@/shared/lib/websocket';
import { loggedOut } from '@/features/auth/model/authSlice';

export function PermissionWatcher({ children }: { children: React.ReactNode }) {
  const dispatch = useAppDispatch();
  const userId = useAppSelector((s) => s.auth.user?.id);
  const stomp = useStompClient();

  useEffect(() => {
    if (!stomp || !userId) return;
    const sub = stomp.subscribe(`/user/${userId}/permission-changed`, () => {
      // Cache invalidate → otomatik refetch
      dispatch(lumixApi.util.invalidateTags(['Permission', 'CurrentUser']));
    });
    const revokeSub = stomp.subscribe(`/user/${userId}/token-revoked`, () => {
      dispatch(loggedOut());
    });
    return () => {
      sub.unsubscribe();
      revokeSub.unsubscribe();
    };
  }, [stomp, userId, dispatch]);

  return <>{children}</>;
}
```

### 4.6. Sidebar rendering (gizli menü)

```tsx
// widgets/sidebar/Sidebar.tsx
import { Link } from 'react-router-dom';
import { useHasPermission } from '@/features/auth/ui/PermissionGate';
import { useTranslation } from 'react-i18next';

type NavItem = { to: string; label: string; permission?: string };

const items: NavItem[] = [
  { to: '/dashboard', label: 'nav.dashboard' },
  { to: '/attendance', label: 'nav.attendance', permission: 'attendance:read' },
  { to: '/messages', label: 'nav.messages', permission: 'messages:read' },
  { to: '/billing', label: 'nav.billing', permission: 'billing:read' },
  { to: '/admin/users', label: 'nav.users', permission: 'user:manage' },
];

export function Sidebar() {
  const { t } = useTranslation('common');
  return (
    <nav>
      {items.map((it) => {
        // hook'u koşullu çağırmamak için, helper component kullan
        return <SidebarItem key={it.to} item={it} label={t(it.label)} />;
      })}
    </nav>
  );
}

function SidebarItem({ item, label }: { item: NavItem; label: string }) {
  const allowed = useHasPermission(item.permission ?? '');
  if (item.permission && !allowed) return null;
  return <Link to={item.to}>{label}</Link>;
}
```

## 5. Neden bu seçim? (Alternatifler)

| Alternatif | Neden elendi |
|---|---|
| **JWT'de permission claim'leri** | Permission listesi büyürse JWT şişer; revoke için yine refresh gerek |
| **Her render permission API çağrısı** | Performans kötü, backend yoğunluğu |
| **localStorage uzun cache** | Revoke gecikmesi, stale UI |
| **CASL (frontend RBAC lib)** | Library overhead, RTK Query ile entegre etmek ekstra iş |
| **Custom Redux selector-only** | İyi çalışır ama WebSocket invalidation tag desteği RTK Query'de hazır |
| **RTK Query + WebSocket invalidation** ✅ | Cache + invalidation + real-time, tek frameworkte |

### Kabul ettiğimiz trade-off

- **Frontend permission güvenlik DEĞİL**: Kullanıcı DevTools açıp Redux state'i değiştirebilir → backend endpoint koruması zorunlu.
- **WebSocket bağlantısı koparsa**: 5dk polling fallback ile geç de olsa invalidate olur.
- **İlk yüklemede permission yokken UI flicker**: `Suspense` veya `isLoading` ile boş UI göster, sonra render.

## 6. Pratik örnek — Mesaj gönder buton (permission + scope)

```tsx
// features/messages/ui/SendMessageButton.tsx
import { useTranslation } from 'react-i18next';
import { PermissionGate } from '@/features/auth/ui/PermissionGate';
import { ScopeGate } from '@/features/auth/ui/ScopeGate';
import { useSendMessageMutation } from '@/entities/message/api/messageApi';

export function SendMessageButton({ conversationId, classId, body }: Props) {
  const { t } = useTranslation('messages');
  const [send, { isLoading }] = useSendMessageMutation();
  return (
    <PermissionGate permission="messages:send" fallback={null}>
      <ScopeGate resource={{ type: 'class', id: classId }} fallback={null}>
        <button
          disabled={isLoading}
          onClick={() => send({ conversationId, body })}
        >
          {t('send')}
        </button>
      </ScopeGate>
    </PermissionGate>
  );
}
```

### Admin: permission değiştir + invalidate

```tsx
// features/admin/ui/UpdateUserPermissionsForm.tsx
import { useUpdateUserPermissionsMutation } from '../api/adminApi';

export function UpdateUserPermissionsForm({ userId }: { userId: string }) {
  const [update] = useUpdateUserPermissionsMutation();
  const onSubmit = async (newPerms: string[]) => {
    await update({ userId, permissions: newPerms }).unwrap();
    // Backend zaten WebSocket event basacak → kullanıcı UI'sı kendiliğinden refresh
  };
  // ...
}
```

## 7. Tuzaklar

- **Frontend permission'ı güvenlik sayma**: DevTools'tan Redux state set edilir. Backend `@PreAuthorize` ŞART.
- **Permission cache'i logout'ta temizlememek**: `lumixApi.util.resetApiState()` ile sıfırlanmalı.
- **Tenant switch sonrası permission revalidate etmemek**: Aktif tenant değişince permission/scope context'i değişebilir. `resetApiState` veya tag invalidate.
- **WebSocket subscription leak**: `useEffect` cleanup'ında `unsubscribe` unutmak.
- **`useGetMyPermissionsQuery` `data === undefined` durumunda permissive gösterim**: Default `false` olmalı, `true` değil.
- **`PermissionGate` her render hooks çağrısı**: Component'in dışına çıkarma; `useHasPermission` ile kondisyonel kullan ama hook'u top-level çağır.
- **Conditional hook**: Sidebar item'larında `items.map((it) => useHasPermission(it.permission))` — hook rule violation. `SidebarItem` ayrı component yap.
- **Çoklu permission AND/OR karışıklığı**: `<PermissionGate permission={['a','b']}>` → AND. OR için ayrı helper: `useHasAnyPermission`.
- **Scope check sadece UI'da**: Backend RLS zaten filter ediyor; UI gate "yetkisiz buton görme" UX'i için. İkisi de gerek.
- **Cache invalidation race condition**: Permission update + WebSocket event sıralaması. RTK Query mutation `onQueryStarted` ile manuel invalidate ekle (WebSocket gecikirse).
- **`/me/permissions` ile JWT permissions çakışması**: Source-of-truth `/me/permissions` (refetch edilebilir); JWT'deki sadece roles claim'i veya stale olabilir.

## 8. Diğer konularla ilişkisi

- [Hibrit Authorization (RBAC + ABAC)](../security-compliance/hybrid-rbac-abac-authorization) — backend model
- [Installation/Tenant/Scope](../tenancy-and-domain-model/installation-tenant-scope) — scope hiyerarşisi
- [Stateful Token Modeli](../security-compliance/auth-jwt-refresh-flow) — permission değişince token revoke
- [Redux Toolkit](./redux-toolkit) — authSlice
- [RTK Query](./rtk-query) — tag invalidation
- [Token Storage](./frontend-token-storage) — `/me` çağrısı
- [WebSocket Backplane](../00-overview/02-technology-stack-decisions) — Redis Pub/Sub multi-pod

## 9. Daha derine

- OWASP Access Control Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Access_Control_Cheat_Sheet.html
- RTK Query tag invalidation: https://redux-toolkit.js.org/rtk-query/usage/automated-refetching
- STOMP over WebSocket: https://stomp.github.io/
- Search keywords:
  - `frontend permission cache real-time invalidation websocket`
  - `react rbac abac scope-aware ui rendering`
  - `rtk query invalidate tags external trigger`
  - `permission gate component pattern react`
  - `multi tenant scope filtering frontend`

## 10. Sözlük

- **Permission** — Atomic yetki tanımı (`attendance:write`).
- **Scope** — Kullanıcının tenant içinde görebileceği veri kapsamı (school / class / student).
- **PermissionGate** — Frontend UI'ı permission'a göre gösteren/gizleyen component.
- **ScopeGate** — Belirli bir resource için scope kontrolü yapan component.
- **Cache invalidation** — Cache entry'sini stale işaretleyerek refetch tetikleme.
- **WebSocket / STOMP** — Real-time bidirectional iletişim protokolü.
- **Tag (RTK Query)** — Cache invalidation'ı modellemek için kullanılan etiket.
- **Effective scope** — ScopeResolver tarafından hesaplanan kullanıcının nihai kapsamı.
- **RBAC** — Role-Based Access Control.
- **ABAC** — Attribute-Based Access Control.
- **Token revoke** — Bir token'ı Redis'te `revoked` işaretleyerek kullanılamaz hale getirme.
