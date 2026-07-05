---
title: "Frontend FSD and Feature Boundaries"
description: Feature-Sliced Design direction for organizing frontend screens into app, pages, widgets, features, entities, and shared layers.
sidebar_position: 2
---

## Introduction

Frontend applications need boundaries just like backend systems do. Without clear ownership, UI code gradually becomes a web of shared components, duplicated state, route-specific shortcuts, and hidden coupling.

Feature-Sliced Design (FSD) provides a practical vocabulary for organizing frontend code by layers, slices, and segments.

## Why It Matters

As screens grow, frontend complexity appears in predictable places:

- page components become too large,
- features import each other directly,
- shared components gain product-specific behavior,
- API calls are scattered across UI components,
- form and table logic is duplicated,
- role-based UI rules become inconsistent.

A feature boundary keeps each product capability understandable and replaceable.

## Core Concepts

- `app`: application initialization, providers, routing, global state setup.
- `pages`: route-level screens composed from widgets and features.
- `widgets`: large page blocks combining multiple features/entities.
- `features`: user actions and business interactions.
- `entities`: domain objects and their UI/model helpers.
- `shared`: reusable UI, utilities, config, and infrastructure code.
- Slice: feature or entity boundary.
- Segment: internal grouping such as `ui`, `model`, `api`, `lib`.

## Problem in Real Product Development

Without frontend boundaries, a message list can depend on attendance state, a payment form can import chat utilities, and shared UI can start containing tenant-specific rules. This makes refactoring risky because every change has unknown reach.

The problem is not file naming alone. The problem is dependency direction.

## Approach / Design

### Current Screen Mapping Direction

| Product area | FSD placement |
| --- | --- |
| App providers, router, query client | `app` |
| Dashboard route | `pages/dashboard` |
| Attendance screen | `pages/attendance`, `widgets/attendance-board`, `features/mark-attendance`, `entities/student` |
| Message list | `pages/messages`, `widgets/conversation-panel`, `features/send-message`, `entities/message` |
| Payment screen | `pages/payments`, `features/create-payment`, `features/refund-payment`, `entities/payment` |
| Admin user management | `pages/admin-users`, `features/update-role`, `features/assign-permission`, `entities/user` |
| Shared buttons, modals, date helpers | `shared/ui`, `shared/lib` |

### Dependency Rule

Higher layers may depend on lower layers:

```text
app
pages
widgets
features
entities
shared
```

But lower layers should not import higher layers:

```text
shared -> features  // not allowed
entities -> pages   // not allowed
features -> widgets // not allowed
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

## Sector Standard / Best Practice

- Keep route composition in `pages`.
- Keep user actions in `features`.
- Keep domain object representation in `entities`.
- Keep generic reusable pieces in `shared`.
- Avoid importing across sibling features.
- Use public APIs (`index.ts`) for slices to reduce deep imports.
- Keep server state strategy aligned with [TanStack Query: Auth, Cache, Invalidation, and Optimistic UI](./tanstack-query-auth-cache-invalidation-optimistic-ui).

## Pseudocode / Decision Flow / Lifecycle / Policy Example

```text
place_frontend_code(code):
  if code initializes app or providers:
    return app
  if code is a route screen:
    return pages
  if code is a composed page block:
    return widgets
  if code performs a user action:
    return features
  if code represents a domain object:
    return entities
  if code is product-agnostic utility or UI:
    return shared
```

## Our Notes / Team Decisions

- Existing screens should be mapped into `app`, `pages`, `widgets`, `features`, `entities`, and `shared`.
- Frontend module boundaries should be protected with the same seriousness as backend module boundaries.
- Shared UI must not silently become product-specific.
- Role-based UI behavior should remain consistent with authorization and E2E strategy.

## External References

- Feature-Sliced Design: [Welcome](https://feature-sliced.design/)

## Glossary

- FSD: frontend architecture methodology based on layers, slices, and segments.
- Layer: architectural tier such as `pages` or `features`.
- Slice: business-oriented module boundary.
- Segment: internal folder role such as `ui`, `model`, or `api`.
- Public API: controlled export surface of a slice.

## Research Keywords

- `feature sliced design frontend architecture`
- `fsd module boundaries shared ui entities features`
- `frontend feature boundaries react`
- `frontend public api slice architecture`
- `frontend folder structure scalable`

## Conclusion

FSD gives the frontend a shared language for boundaries. By mapping screens into layers and enforcing dependency direction, the UI codebase can grow without turning every feature into a hidden dependency of every other feature.
