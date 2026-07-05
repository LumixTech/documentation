---
title: Customer Admin Panel (Müşteri Yöneticisi)
description: Lumix Customer Admin Panel — müşteri kurumun yöneticisi için tenant, kullanıcı, rol/scope, fatura, ayar yönetimi. React + Redux Toolkit + RTK Query stack.
sidebar_position: 1
---

## Bu sayfa ne anlatıyor?

**Customer Admin Panel**, müşteri kurumun (örn. "Ömer Okulları") yöneticisinin Lumix'i yönetmek için kullandığı admin paneldir. Bu sayfada şunları öğreneceksin:

- Customer Admin Panel'in amacı ve hedef kullanıcısı
- Ana özellikler (tenant CRUD, kullanıcı yönetimi, permission, fatura, ayarlar)
- Mimari kararlar (ayrı app mı, route subset mi)
- Tenant-aware filtering UX
- Multi-tenant kullanıcı (bölge müdürü) deneyimi
- Backend endpoint pattern (admin scope)
- Güvenlik ve audit gereksinimleri

Bu sayfa **Lumix dışındaki** ([Internal Admin Panel](./02-internal-admin-panel.md)) farklı bir paneldir; müşteri kurum içi yönetim için.

## 1. Customer Admin Panel nedir? (Sıfırdan)

Lumix bir SaaS. Müşteri olarak bir kurum sistemi kullanır. Bu kurumun **kendi içinde yöneticisi** vardır:

- Yeni şube (tenant) açacak
- Yeni öğretmen/personel ekleyecek, rol verecek
- Bir kullanıcının yetkisini değiştirecek
- Faturayı görmek isteyecek
- Ayar değiştirecek (timezone, branding, modül aktiflik)

Bu kişi **müşterinin IT/operasyon yöneticisi**. Lumix ekibi değil; Lumix bir araç olarak veriyor, müşteri kullanıyor.

### Günlük hayattan analoji

Bir bina yönetim sistemi düşün:

- **Bina yönetim şirketi (Lumix)** — sistemin sağlayıcısı; bakım, güncelleme onlardan
- **Apartman yönetim kurulu (Customer Admin)** — daire sahiplerini ekler, çıkarır, aidat hesaplar, kararları girer
- **Daire sahipleri (end user)** — günlük kullanıcılar; kendi dairelerini, aidatlarını görür

Apartman yönetimi bina yönetim şirketine telefon etmeden çoğu işi kendi yapar; **kendi adminpaneli** vardır.

### Lumix terminolojisinde

```
Installation = "Ömer Okulları" (Lumix kuruluş)
  └─ Tenant 1 = Kadıköy Şubesi
  └─ Tenant 2 = Beşiktaş Şubesi
  └─ Tenant 3 = Üsküdar Şubesi

Customer Admin = Ömer Okulları içindeki "Bilgi İşlem Müdürü"
  → Tenant 1, 2, 3'ü görür ve yönetir
  → Tenant içindeki kullanıcıları yönetir
  → Lisans bilgisini görür ama değiştiremez (Lumix tarafı)
```

## 2. Hangi problemi çözüyor?

Customer Admin Panel olmazsa:

- Her yeni kullanıcı ekleme Lumix support'a ticket → yavaş, ölçeklenmez
- Müşteri "Beşiktaş şubesi açtık" diye Lumix'i beklemek zorunda
- Rol/yetki değişimi her zaman ticket
- Müşteri kendi verisini görmek için Lumix'e bağımlı

Panel ile:

- **Self-service**: müşteri kendi yönetir
- **Tenant lifecycle**: aç, kapat, ayarla
- **Audit kendi gözünde**: kim ne yaptı (compliance gereksinimi)
- **Ölçeklenebilir destek modeli**: Lumix sadece "platform" sorunlarına bakar; "kullanıcı ekle" gibi rutin işler müşteride

## 3. Nasıl çözüyor? (Mekanizma)

### 3.1. Mimari karar — ayrı app mı, route subset mi?

**Lumix kararı: AYNI uygulama, route subset.**

```
apps/web/  (tek React app)
├── src/pages/
│   ├── dashboard/          # end user
│   ├── attendance/         # end user
│   ├── messages/           # end user
│   ├── billing-self/       # self-service billing (own invoices)
│   └── admin/              # CUSTOMER ADMIN ROUTES ↓
│       ├── tenants/        # tenant management
│       ├── users/          # user management
│       ├── permissions/    # role/scope assignment
│       ├── invoices/       # billing view (kurum)
│       ├── audit/          # audit log query
│       └── settings/       # tenant config
```

Niye ayrı app değil?

- Aynı RTK Query store + auth → kod paylaşımı çok
- Customer admin "ile" "end user" rol aynı kullanıcıda olabilir (öğretmen + müdür)
- UX: tek login, sol menüde "Yönetim" sekmesi
- Bundle: lazy-load ile admin route'lar ayrı chunk → end user yüklemiyorsa indirmez

### 3.2. Route gate — permission ile koruma

```tsx
// app/router/routes.tsx
{
  path: 'admin',
  element: <RequirePermission permission="customer-admin" />,
  children: [
    { path: 'tenants', lazy: () => import('@/pages/admin/tenants') },
    { path: 'users', lazy: () => import('@/pages/admin/users') },
    { path: 'permissions', lazy: () => import('@/pages/admin/permissions') },
    { path: 'invoices', lazy: () => import('@/pages/admin/invoices') },
    { path: 'audit', lazy: () => import('@/pages/admin/audit') },
    { path: 'settings', lazy: () => import('@/pages/admin/settings') },
  ],
}
```

`customer-admin` permission'ı olan kullanıcılar `/admin/*` rotalarına erişir; diğerleri `/forbidden`.

### 3.3. Tenant-aware filtering

Customer admin **birden fazla tenant** (şube) görmek isteyebilir.

UI'da üst köşede **Tenant Switcher**:

```
┌────────────────────────────────────────┐
│  Lumix · Ömer Okulları · [Kadıköy ▼]  │   ← Tenant switcher
├────────────────────────────────────────┤
│  Yönetim                               │
│  ├── Şubeler (tenant)                  │
│  ├── Kullanıcılar                      │
│  ├── ...                               │
└────────────────────────────────────────┘
```

Switcher değişince `tenantSlice.activeTenantId` güncellenir → RTK Query cache reset (bkz: [RTK Query](../frontend-architecture/33-rtk-query.md)) → admin tabloları yeni tenant için yüklenir.

**"Tüm Şubeler" seçeneği** — multi-tenant kullanıcı için: query `?tenantIds=t1,t2,t3` ile birden fazla tenant filtreleyebilir.

### 3.4. Backend endpoint pattern

Customer admin endpoint'leri **ayrı prefix** ile expose edilir:

```
GET    /api/v1/admin/tenants                  # listele
POST   /api/v1/admin/tenants                  # yeni tenant aç
PATCH  /api/v1/admin/tenants/{id}             # düzenle
DELETE /api/v1/admin/tenants/{id}             # soft-delete (kapat)

GET    /api/v1/admin/users?tenantId=...
POST   /api/v1/admin/users                    # yeni kullanıcı + invite email
PATCH  /api/v1/admin/users/{id}/roles
PATCH  /api/v1/admin/users/{id}/scopes
PATCH  /api/v1/admin/users/{id}/status        # disable/enable

GET    /api/v1/admin/invoices
GET    /api/v1/admin/audit-log?tenantId=...&from=...&to=...

GET    /api/v1/admin/settings
PATCH  /api/v1/admin/settings
```

Backend tarafı `@PreAuthorize("hasPermission('customer-admin')")` ile korur ve **installation seviyesinde scope** uygular (kullanıcı sadece kendi installation'ının kayıtlarını görür).

## 4. Biz nasıl kullanıyoruz? (Lumix)

### 4.1. Kararlar

| Konu | Karar |
|---|---|
| App | **Aynı `apps/web`**, route subset |
| Route prefix | **`/admin/*`** |
| Permission | **`customer-admin`** (role'e dahil; tenant scope cross-tenant olabilir) |
| Backend prefix | **`/api/v1/admin/*`** |
| UI kit | **Mantine** veya **Ant Design** (genel kararla aynı) |
| Table/grid | Generic `<DataTable />` shared/ui'da; sorting, pagination, filter |
| Form pattern | RHF + Zod (genel formla aynı) |
| Audit log | Read-only, exportable (CSV/XLSX) |
| Bulk actions | User listesinde "seç → topluca aktif/pasif" |
| Confirm UI | Destructive action'larda double confirm modal |

### 4.2. Ana ekranlar (modüller)

| Modül | Path | Özellikler |
|---|---|---|
| **Şubeler (Tenants)** | `/admin/tenants` | Liste, oluştur, düzenle, kapat (soft delete); per-tenant config |
| **Kullanıcılar** | `/admin/users` | Liste (filter: tenant, role, status), oluştur, invite gönder, rol/scope ata, disable |
| **Yetkiler (Permissions)** | `/admin/permissions` | Rol matrisi, user-permission override |
| **Faturalar** | `/admin/invoices` | Lumix'ten gelen faturalar (read-only); PDF indir |
| **Audit Log** | `/admin/audit` | Tablo, filter (kullanıcı, tarih, action type), export |
| **Ayarlar** | `/admin/settings` | Tenant timezone, locale, branding (logo), modül aktiflik (lisans izin verdiği kadar) |

### 4.3. RTK Query endpoint'leri (örnek)

```ts
// features/admin/api/adminApi.ts
import { lumixApi } from '@lumix/core/shared/api';

export const adminApi = lumixApi.injectEndpoints({
  endpoints: (build) => ({
    listTenants: build.query<Tenant[], void>({
      query: () => '/api/v1/admin/tenants',
      providesTags: (result) =>
        result
          ? [
              ...result.map(({ id }) => ({ type: 'Tenant' as const, id })),
              { type: 'Tenant', id: 'LIST' },
            ]
          : [{ type: 'Tenant', id: 'LIST' }],
    }),
    createTenant: build.mutation<Tenant, { name: string; timezone: string; locale: string }>({
      query: (body) => ({ url: '/api/v1/admin/tenants', method: 'POST', body }),
      invalidatesTags: [{ type: 'Tenant', id: 'LIST' }],
    }),
    listUsers: build.query<User[], { tenantId?: string; role?: string; status?: string }>({
      query: (params) => ({ url: '/api/v1/admin/users', params }),
      providesTags: (r, _e, arg) => [{ type: 'User', id: `LIST:${arg.tenantId ?? 'all'}` }],
    }),
    createUser: build.mutation<User, CreateUserBody>({
      query: (body) => ({ url: '/api/v1/admin/users', method: 'POST', body }),
      invalidatesTags: [{ type: 'User', id: 'LIST:all' }, { type: 'User', id: `LIST:${'tenantId'}` }],
    }),
    updateUserRoles: build.mutation<void, { userId: string; tenantId: string; roles: string[] }>({
      query: ({ userId, ...body }) => ({
        url: `/api/v1/admin/users/${userId}/roles`,
        method: 'PATCH',
        body,
      }),
      invalidatesTags: (_r, _e, arg) => [{ type: 'User', id: arg.userId }, 'Permission'],
    }),
    updateUserScopes: build.mutation<void, UpdateScopesBody>({
      query: ({ userId, ...body }) => ({
        url: `/api/v1/admin/users/${userId}/scopes`,
        method: 'PATCH',
        body,
      }),
      invalidatesTags: (_r, _e, arg) => [{ type: 'User', id: arg.userId }, 'Permission'],
    }),
    listInvoices: build.query<Invoice[], { from?: string; to?: string }>({
      query: (params) => ({ url: '/api/v1/admin/invoices', params }),
      providesTags: ['Invoice'],
    }),
    listAuditLog: build.query<AuditEntry[], AuditQuery>({
      query: (params) => ({ url: '/api/v1/admin/audit-log', params }),
    }),
    getSettings: build.query<TenantSettings, { tenantId: string }>({
      query: ({ tenantId }) => `/api/v1/admin/settings?tenantId=${tenantId}`,
      providesTags: ['Tenant'],
    }),
    updateSettings: build.mutation<void, { tenantId: string; settings: Partial<TenantSettings> }>({
      query: ({ tenantId, settings }) => ({
        url: `/api/v1/admin/settings?tenantId=${tenantId}`,
        method: 'PATCH',
        body: settings,
      }),
      invalidatesTags: ['Tenant'],
    }),
  }),
});

export const {
  useListTenantsQuery,
  useCreateTenantMutation,
  useListUsersQuery,
  useCreateUserMutation,
  useUpdateUserRolesMutation,
  useUpdateUserScopesMutation,
  useListInvoicesQuery,
  useListAuditLogQuery,
  useGetSettingsQuery,
  useUpdateSettingsMutation,
} = adminApi;
```

### 4.4. Sample sayfa: Kullanıcı listesi

```tsx
// pages/admin/users/UsersPage.tsx
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useListUsersQuery, useCreateUserMutation } from '@/features/admin/api/adminApi';
import { useAppSelector } from '@/app/store/hooks';
import { DataTable } from '@/shared/ui/DataTable';
import { Modal } from '@/shared/ui/Modal';
import { CreateUserForm } from '@/features/admin/ui/CreateUserForm';
import { PermissionGate } from '@/features/auth/ui/PermissionGate';

export function UsersPage() {
  const { t } = useTranslation('admin');
  const activeTenantId = useAppSelector((s) => s.tenant.activeTenantId);
  const [filters, setFilters] = useState({ role: undefined as string | undefined, status: undefined as string | undefined });
  const [createOpen, setCreateOpen] = useState(false);

  const { data, isLoading } = useListUsersQuery({
    tenantId: activeTenantId ?? undefined,
    ...filters,
  });

  return (
    <div>
      <h1>{t('users.title')}</h1>
      <PermissionGate permission="user:create">
        <button onClick={() => setCreateOpen(true)}>{t('users.create')}</button>
      </PermissionGate>

      <DataTable
        loading={isLoading}
        data={data ?? []}
        columns={[
          { key: 'email', label: t('users.col.email') },
          { key: 'fullName', label: t('users.col.fullName') },
          { key: 'roles', label: t('users.col.roles'), render: (u) => u.roles.join(', ') },
          { key: 'status', label: t('users.col.status') },
          {
            key: 'actions',
            label: '',
            render: (u) => <UserRowActions user={u} />,
          },
        ]}
      />

      <Modal open={createOpen} onClose={() => setCreateOpen(false)} title={t('users.createTitle')}>
        <CreateUserForm onSuccess={() => setCreateOpen(false)} />
      </Modal>
    </div>
  );
}
```

### 4.5. Multi-tenant kullanıcı için UX (bölge müdürü)

```tsx
// widgets/tenant-switcher/TenantSwitcher.tsx
import { Select } from '@mantine/core';
import { useAppDispatch, useAppSelector } from '@/app/store/hooks';
import { activeTenantSwitched } from '@lumix/core/tenant';
import { useListTenantsQuery } from '@/features/admin/api/adminApi';

export function TenantSwitcher() {
  const dispatch = useAppDispatch();
  const activeTenantId = useAppSelector((s) => s.tenant.activeTenantId);
  const tenantIds = useAppSelector((s) => s.tenant.tenantIds);
  const { data: allTenants } = useListTenantsQuery();
  const userTenants = allTenants?.filter((t) => tenantIds.includes(t.id)) ?? [];

  return (
    <Select
      value={activeTenantId ?? ''}
      onChange={(v) => v && dispatch(activeTenantSwitched(v))}
      data={[
        ...(tenantIds.length > 1 ? [{ value: 'all', label: 'Tüm Şubeler' }] : []),
        ...userTenants.map((t) => ({ value: t.id, label: t.name })),
      ]}
    />
  );
}
```

### 4.6. Audit log ekranı

Customer admin "kim ne yaptı" görmek ister (compliance + güvenlik):

```tsx
// pages/admin/audit/AuditPage.tsx
import { useState } from 'react';
import { useListAuditLogQuery } from '@/features/admin/api/adminApi';
import { DateRangePicker } from '@/shared/ui/DateRangePicker';

export function AuditPage() {
  const [range, setRange] = useState({ from: '', to: '' });
  const [userId, setUserId] = useState<string | undefined>();
  const { data, isLoading } = useListAuditLogQuery({ ...range, userId });

  return (
    <div>
      <h1>Audit Log</h1>
      <DateRangePicker value={range} onChange={setRange} />
      <UserPicker value={userId} onChange={setUserId} />
      <DataTable
        loading={isLoading}
        data={data ?? []}
        columns={[
          { key: 'timestamp', label: 'Zaman' },
          { key: 'actorName', label: 'Kullanıcı' },
          { key: 'action', label: 'Aksiyon' },
          { key: 'resource', label: 'Kaynak' },
          { key: 'tenantName', label: 'Şube' },
          { key: 'ip', label: 'IP' },
        ]}
      />
      <button onClick={exportAudit}>CSV indir</button>
    </div>
  );
}
```

## 5. Neden bu mimari? (Alternatifler)

| Alternatif | Neden elendi |
|---|---|
| **Ayrı app (`apps/customer-admin`)** | Code paylaşımı için ayrıca paket gerek; UX (login, settings) tekrar yazılır; bundle ekstra |
| **Backend admin paneli** (Spring Boot Thymeleaf vb.) | Tutarsız UX; React ekibinin elinde olmaz |
| **Generic admin lib (React Admin, Refine)** | Hızlı ama Lumix permission/scope/tenant modeline tam uymaz; özelleştirme yükü |
| **Aynı app + route subset** ✅ | Tek deployment, tek auth, tek UI kit, kod paylaşımı |

### Trade-off

- **Bundle**: admin route'ları lazy-load ile end user'a yüklenmez ama yine de aynı build artifact.
- **Permission karmaşası**: Aynı app içinde admin + end user route'ları → permission guard'ları her route'a tek tek koymak gerek.
- **Test**: Playwright E2E'de admin role + end user role iki ayrı test plan.

## 6. Pratik örnek — kullanıcıya rol ata

```tsx
// features/admin/ui/AssignRolesForm.tsx
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useUpdateUserRolesMutation } from '../api/adminApi';
import { useToast } from '@/shared/lib/toast';

const Schema = z.object({
  roles: z.array(z.enum(['admin', 'teacher', 'parent', 'student'])).min(1),
});
type Values = z.infer<typeof Schema>;

export function AssignRolesForm({ userId, tenantId, initialRoles }: Props) {
  const { handleSubmit, register, formState } = useForm<Values>({
    resolver: zodResolver(Schema),
    defaultValues: { roles: initialRoles },
  });
  const [update, { isLoading }] = useUpdateUserRolesMutation();
  const toast = useToast();

  const onSubmit = async (v: Values) => {
    try {
      await update({ userId, tenantId, roles: v.roles }).unwrap();
      toast.success('Roller güncellendi');
    } catch (e: any) {
      toast.error(e?.data?.detail ?? 'Güncellenemedi');
    }
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)}>
      <fieldset>
        <legend>Roller</legend>
        <label><input type="checkbox" value="admin" {...register('roles')} /> Admin</label>
        <label><input type="checkbox" value="teacher" {...register('roles')} /> Öğretmen</label>
        <label><input type="checkbox" value="parent" {...register('roles')} /> Veli</label>
        <label><input type="checkbox" value="student" {...register('roles')} /> Öğrenci</label>
      </fieldset>
      {formState.errors.roles && <span>{formState.errors.roles.message}</span>}
      <button disabled={isLoading}>Kaydet</button>
    </form>
  );
}
```

## 7. Tuzaklar

- **Customer admin'in cross-installation görmesi**: Olmamalı; backend'de installation seviyesinde RLS + JWT'de `installation_id` cross-check.
- **End user'a admin route render etmek**: `<RequirePermission>` ile route gate; sidebar item da hide.
- **Multi-tenant kullanıcı "Tüm Şubeler" seçince RTK Query cache patlamaları**: Tag id formatını `LIST:all` veya `LIST:${tenantId}` net ayır.
- **Tenant switch sonrası eski tenant data ekranda kalır**: `cacheResetMiddleware` ile `resetApiState`.
- **Bulk action'da idempotency atlama**: 1000 user disable işlemi → batch API + idempotency-key header.
- **Audit log'a frontend'den filter geçişi**: Backend filter'ı validate etmeli; client'a güvenme.
- **Customer admin Lumix lisans bilgisini değiştirme isteğine UI vermek**: Read-only göster; lisans yönetimi Internal Admin Panel'de.
- **Soft delete vs hard delete karışıklığı**: Kullanıcı/tenant "kapat" → soft delete (audit için kalır); hard delete sadece DSAR/anonymization.
- **Permission yanlış UI'da**: Bir kullanıcının yeni rolü hemen görünmüyor → `invalidatesTags: ['Permission']` + WebSocket invalidate.
- **Tenant create form'da name uniqueness**: Backend kontrol eder ama frontend pre-check (debounced) UX iyi.
- **Settings PATCH partial update'ı destekleyememek**: Tüm settings object'i göndermek hatalı; `Partial<TenantSettings>` ile sadece değişeni gönder.
- **Audit log export büyük data**: Backend streaming response (NDJSON / CSV) + frontend File API ile dosya yaz.

## 8. Diğer konularla ilişkisi

- [Internal Admin Panel](./02-internal-admin-panel.md) — Lumix ekibinin paneli (farklı kullanıcı, farklı app)
- [Rancher Cluster Management](./03-rancher-cluster-management.md) — DevOps multi-cluster yönetimi
- [Permission Cache](../frontend-architecture/09-frontend-permission-cache.md) — `customer-admin` permission
- [RTK Query](../frontend-architecture/33-rtk-query.md) — admin endpoint pattern
- [Installation/Tenant/Scope](../01-tenancy-and-domain-model/01-installation-tenant-scope.md) — multi-tenant modeli
- [Hibrit Authorization](../04-authentication-authorization/04-rbac-abac-hybrid.md) — rol/scope atama mantığı
- [Form Handling](../frontend-architecture/07-form-handling.md) — admin form patternleri

## 9. Daha derine

- React Admin: https://marmelab.com/react-admin/
- Refine: https://refine.dev/
- Audit log design: https://www.dnsstuff.com/audit-log-best-practices
- Search keywords:
  - `customer admin panel multi tenant react`
  - `admin route subset same app permission gate`
  - `tenant aware crud react redux toolkit`
  - `bulk user action idempotency frontend`
  - `audit log query filter export csv react`

## 10. Sözlük

- **Customer Admin** — Müşteri kurumun kendi içinden seçilen, kurumu Lumix'te yöneten kullanıcı.
- **Internal Admin** — Lumix ekibinden kişi; tüm installation'ları yönetir.
- **Tenant** — Installation içindeki bağımsız operasyonel birim (şube).
- **Tenant Switcher** — Aktif tenant'ı değiştiren UI bileşeni.
- **Multi-tenant kullanıcı** — Birden fazla tenant'ta yetkili kullanıcı (bölge müdürü).
- **Bulk action** — Birden fazla kayda aynı anda uygulanan işlem.
- **Soft delete** — Kaydı silinmiş işaretleyip DB'de tutma; audit için.
- **Audit log** — Kim, ne zaman, ne yaptı kaydı.
- **Permission matrix** — Rol × izin tablosu.
- **Self-service** — Kullanıcının destek ekibi olmadan kendi yapabilmesi.
