---
title: "Frontend FSD ve Feature Boundaries"
description: Mevcut ekranları app, pages, widgets, features, entities ve shared katmanlarına ayırmak için Feature-Sliced Design yaklaşımı.
sidebar_position: 2
---

## Giriş

Frontend uygulamaları da backend gibi boundary'lere ihtiyaç duyar. Sınır yoksa UI kodu zamanla shared component, duplicated state, route-specific shortcut ve hidden coupling ağına dönüşür.

Feature-Sliced Design (FSD), frontend kodunu layer, slice ve segment kavramlarıyla düzenlemek için pratik bir vocabulary sağlar.

## Neden Önemli

Ekranlar büyüdükçe frontend complexity şu alanlarda görünür:

- page component'leri çok büyür,
- feature'lar birbirini doğrudan import eder,
- shared component product-specific davranış kazanır,
- API çağrıları UI component'lerine dağılır,
- form ve table logic duplicate olur,
- role-based UI rule'ları tutarsızlaşır.

Feature boundary, her product capability'nin anlaşılır ve değiştirilebilir kalmasını sağlar.

## Temel Kavramlar

- `app`: application initialization, providers, routing, global state setup.
- `pages`: route-level screen; widget ve feature'lardan compose edilir.
- `widgets`: birden fazla feature/entity birleştiren büyük page block.
- `features`: user action ve business interaction.
- `entities`: domain object ve ilgili UI/model helper'ları.
- `shared`: reusable UI, utility, config ve infrastructure code.
- Slice: feature veya entity boundary.
- Segment: `ui`, `model`, `api`, `lib` gibi internal grouping.

## Gerçek Ürün Geliştirmede Problem

Boundary yoksa message list attendance state'e, payment form chat utility'ye, shared UI tenant-specific kurala bağımlı hale gelebilir. Bu refactor'ı riskli yapar çünkü değişikliğin reach'i bilinmez.

Problem yalnızca file naming değil; dependency direction problemidir.

## Yaklaşım / Tasarım

### Mevcut Ekranları Map Etme Yönü

| Ürün alanı | FSD placement |
| --- | --- |
| App provider, router, query client | `app` |
| Dashboard route | `pages/dashboard` |
| Attendance screen | `pages/attendance`, `widgets/attendance-board`, `features/mark-attendance`, `entities/student` |
| Message list | `pages/messages`, `widgets/conversation-panel`, `features/send-message`, `entities/message` |
| Payment screen | `pages/payments`, `features/create-payment`, `features/refund-payment`, `entities/payment` |
| Admin user management | `pages/admin-users`, `features/update-role`, `features/assign-permission`, `entities/user` |
| Shared button, modal, date helper | `shared/ui`, `shared/lib` |

### Dependency Rule

Üst layer alt layer'a bağımlı olabilir:

```text
app
pages
widgets
features
entities
shared
```

Alt layer üst layer'ı import etmemelidir:

```text
shared -> features  // yasak
entities -> pages   // yasak
features -> widgets // yasak
```

### Folder Skeleton

```text
src/
  app/
    providers/
    router/
    store/
  pages/
    attendance/
    messages/
    payments/
  widgets/
    attendance-board/
    conversation-panel/
  features/
    mark-attendance/
    send-message/
    update-role/
  entities/
    student/
    message/
    payment/
    user/
  shared/
    api/
    ui/
    lib/
    config/
```

## Sektör Standardı / Best Practice

- Route composition `pages` içinde kalsın.
- User action `features` içinde kalsın.
- Domain object representation `entities` içinde kalsın.
- Generic reusable parçalar `shared` içinde kalsın.
- Sibling feature'lar birbirini import etmesin.
- Slice'lar için public API (`index.ts`) kullan.
- Server state stratejisini [TanStack Query: Auth, Cache, Invalidation ve Optimistic UI](./tanstack-query-auth-cache-invalidation-optimistic-ui) ile hizala.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
place_frontend_code(code):
  if code app/provider initialize ediyorsa:
    return app
  if code route screen ise:
    return pages
  if code composed page block ise:
    return widgets
  if code user action ise:
    return features
  if code domain object temsil ediyorsa:
    return entities
  if code product-agnostic utility veya UI ise:
    return shared
```

## Bizim Notlarımız / Takım Kararları

- Mevcut ekranlar `app`, `pages`, `widgets`, `features`, `entities` ve `shared` katmanlarına ayrılmalıdır.
- Frontend module boundary'leri backend boundary'leri kadar ciddiye alınmalıdır.
- Shared UI sessizce product-specific hale gelmemelidir.
- Role-based UI davranışı authorization ve E2E stratejisiyle uyumlu olmalıdır.

## Harici Referanslar

- Feature-Sliced Design: [Welcome](https://feature-sliced.design/)

## Sözlük

- FSD: layer, slice ve segment temelli frontend architecture yaklaşımı.
- Layer: `pages` veya `features` gibi architectural tier.
- Slice: business-oriented module boundary.
- Segment: `ui`, `model`, `api` gibi internal folder rolü.
- Public API: slice'ın kontrollü export yüzeyi.

## Araştırma Keywordleri

- `feature sliced design frontend architecture`
- `fsd module boundaries shared ui entities features`
- `frontend feature boundaries react`
- `frontend public api slice architecture`
- `frontend folder structure scalable`

## Sonuç

FSD frontend için ortak boundary dili sağlar. Ekranları layer'lara map edip dependency direction enforce ederek UI codebase'in her feature'ı gizli bağımlılığa dönüştürmeden büyümesi sağlanır.
