---
title: "TanStack Query: Auth, Cache, Invalidation ve Optimistic UI"
description: Auth-aware query, cache key, invalidation, optimistic update, message list ve attendance screen için server state stratejisi.
sidebar_position: 3
---

## Giriş

TanStack Query server state yönetir: server'da yaşayan, async fetch edilen, stale olabilen ve başka kullanıcı veya process tarafından değiştirilebilen data.

Bu client UI state'ten farklıdır. Authentication context, query key, invalidation, optimistic update ve error handling için ortak strateji gerekir.

## Neden Önemli

Server state stratejisi yoksa:

- loading ve error davranışı duplicate olur,
- mutation sonrası stale data görünür kalır,
- optimistic UI tutarsızlaşır,
- auth refresh davranışı dağılır,
- cache key tenant veya filter bazında çakışır,
- message ve attendance ekranları farklı gerçeklik gösterir.

TanStack Query yardımcı olur; ama takımın convention tanımlaması gerekir.

## Temel Kavramlar

- Query: server state okuyan read operation.
- Mutation: server state değiştiren write operation.
- Query key: cache identity.
- Invalidation: değişiklik sonrası cached data'yı stale işaretleme.
- Optimistic update: server onayı öncesi geçici UI update.
- Stale time: data'nın fresh kabul edildiği süre.
- Cache time/gc time: kullanılmayan data'nın cache'te kalma süresi.
- Server state: server tarafından sahiplenilen ve client tarafından fetch edilen data.

## Gerçek Ürün Geliştirmede Problem

Risk TanStack Query'yi bir kere yanlış kullanmak değil; her ekranın kendi kuralını icat etmesidir:

```text
messages query key = ['messages']
attendance query key = ['attendance', classId]
admin query key = ['tenant', tenantId, 'users']
```

Query key içinde tenant, filter, route context ve gerekli auth state yoksa data leak, cache collision veya stale data oluşabilir.

## Yaklaşım / Tasarım

### Query Key Factory Yönü

Ad hoc array yerine key factory kullan:

```typescript
export const queryKeys = {
  messages: {
    conversations: (tenantId: string) => ['tenant', tenantId, 'messages', 'conversations'],
    list: (tenantId: string, conversationId: string) =>
      ['tenant', tenantId, 'messages', 'list', conversationId],
  },
  attendance: {
    classroom: (tenantId: string, classroomId: string, date: string) =>
      ['tenant', tenantId, 'attendance', classroomId, date],
  },
};
```

### Message List Query ve Mutation Planı

Queries:

- `tenant-id` ve user context'e göre conversation list,
- `conversation_id` bazlı message list,
- gerektiği yerde attachment metadata.

Mutations:

- send message,
- destekleniyorsa edit message,
- destekleniyorsa delete message,
- mark conversation as read.

Invalidation:

```text
send_message success:
  invalidate messages.list(tenant_id, conversation_id)
  invalidate messages.conversations(tenant_id)
  gerekirse mutation response ile cache update et
```

Optimistic UI:

```text
on_mutate send_message:
  cancel messages.list
  status='sending' olan temporary message ekle
  error durumunda rollback yap
  success durumunda temporary message'ı server message ile değiştir
```

### Attendance Screen Query ve Mutation Planı

Queries:

- current classroom list,
- student list,
- classroom/date bazlı attendance state,
- gösteriliyorsa attendance summary.

Mutations:

- mark single student,
- submit batch attendance,
- izinliyse revise attendance.

Invalidation:

```text
submit_attendance success:
  invalidate attendance.classroom(tenant_id, classroom_id, date)
  invalidate attendance.summary(tenant_id, date)
```

Optimistic UI:

- conflict olasılığı düşükse checkbox state için uygundur,
- validation failure'da rollback yapmalıdır,
- submit sonrası server-confirmed state göstermelidir.

## Sektör Standardı / Best Practice

- Query key factory kullan.
- Key içine `tenant-id` ve ilgili filter'ları ekle.
- Auth refresh'i API client/interceptor layer'da tut.
- Mutation sonrası ilgili query'leri invalidate et.
- Mutation response tam fresh data içeriyorsa cache'i response ile güncelle.
- Optimistic update'i rollback açık olduğunda kullan.
- Logout ve tenant switch sırasında query cache'i reset/clear et.
- Client UI state ile server state'i ayır.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
on_logout():
  auth_store.clear()
  query_client.cancelQueries()
  query_client.clear()
  navigate_to_login()
```

Mutation lifecycle:

```text
send_message_mutation:
  onMutate:
    previous cache snapshot al
    optimistic message yaz
  onError:
    snapshot restore et
  onSuccess:
    optimistic message'ı server message ile değiştir
  onSettled:
    conversation list ve message list invalidate et
```

## Bizim Notlarımız / Takım Kararları

- Message list ve attendance screen için açık query/mutation planı gerekir.
- Query key'ler tenant-aware olmalıdır.
- Auth refresh screen component altında değil API katmanında çözülmelidir.
- Optimistic UI rollback ve server reconciliation içermelidir.
- Frontend boundary'ler [Frontend FSD ve Feature Boundaries](./frontend-fsd-feature-boundaries) ile uyumlu olmalıdır.

## Harici Referanslar

- TanStack Query: [React Overview](https://tanstack.com/query/latest/docs/framework/react/overview)

## Sözlük

- Query: cached server read operation.
- Mutation: server write operation.
- Query key: cache identity.
- Invalidation: cached data'yı stale işaretleme.
- Optimistic update: server success öncesi geçici client update.
- Server state: server-owned ve client tarafından fetch edilen data.

## Araştırma Keywordleri

- `tanstack query auth refresh token cache invalidation`
- `optimistic updates react query`
- `query key factory tenant id`
- `message list query invalidation`
- `attendance screen mutation plan`

## Sonuç

TanStack Query, server state ortak mimari konu olarak ele alındığında güçlüdür. Tenant-aware query key, predictable invalidation ve dikkatli optimistic update UI'ı hızlı ama güvenilir tutar.
