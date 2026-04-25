---
title: "TanStack Query: Auth, Cache, Invalidation, and Optimistic UI"
description: Server state strategy for auth-aware queries, cache keys, invalidation, optimistic updates, message lists, and attendance screens.
sidebar_position: 3
---

## Introduction

TanStack Query manages server state: data that lives on the server, is fetched asynchronously, can become stale, and may be changed by other users or processes.

This is different from client UI state. Authentication context, query keys, invalidation, optimistic updates, and error handling need a shared strategy so screens stay predictable.

## Why It Matters

Without a server state strategy:

- loading and error behavior is duplicated,
- stale data remains visible after mutations,
- optimistic UI becomes inconsistent,
- auth refresh behavior is scattered,
- cache keys collide across tenants or filters,
- message and attendance screens show different truth.

TanStack Query helps, but the team still needs conventions.

## Core Concepts

- Query: read operation that fetches server state.
- Mutation: write operation that changes server state.
- Query key: structured cache identity.
- Invalidation: marking cached data stale after changes.
- Optimistic update: temporary UI update before server confirmation.
- Stale time: duration data is considered fresh.
- Cache time/gc time: duration unused data remains cached.
- Server state: remotely owned data that may change outside the client.

## Problem in Real Product Development

The risk is not using TanStack Query incorrectly once. The risk is each screen inventing its own rules:

```text
messages query key = ['messages']
attendance query key = ['attendance', classId]
admin query key = ['tenant', tenantId, 'users']
```

If query keys do not include tenant, filters, route context, and auth state where needed, data can leak, collide, or remain stale.

## Approach / Design

### Query Key Factory Direction

Use key factories instead of ad hoc arrays:

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

### Message List Query and Mutation Plan

Queries:

- conversation list by `tenant-id` and user context,
- message list by `conversation_id`,
- attachment metadata by message/file IDs where needed.

Mutations:

- send message,
- edit message if supported,
- delete message if supported,
- mark conversation as read.

Invalidation:

```text
send_message success:
  invalidate messages.list(tenant_id, conversation_id)
  invalidate messages.conversations(tenant_id)
  optionally update cache from mutation response
```

Optimistic UI:

```text
on_mutate send_message:
  cancel messages.list
  add temporary message with status='sending'
  rollback on error
  replace temporary message on success
```

### Attendance Screen Query and Mutation Plan

Queries:

- current classroom list,
- student list,
- attendance state by classroom/date,
- attendance summary if shown.

Mutations:

- mark single student,
- submit batch attendance,
- revise attendance if allowed.

Invalidation:

```text
submit_attendance success:
  invalidate attendance.classroom(tenant_id, classroom_id, date)
  invalidate attendance.summary(tenant_id, date)
```

Optimistic UI:

- safe for local checkbox status when conflicts are unlikely,
- must rollback on validation failure,
- should show server-confirmed state after submit.

## Sector Standard / Best Practice

- Use query key factories.
- Include `tenant-id` and relevant filters in keys.
- Keep auth refresh in API client/interceptor layer.
- Invalidate related queries after mutations.
- Prefer mutation response updates when response contains complete fresh data.
- Use optimistic updates only where rollback is clear.
- Reset or clear query cache on logout and tenant switch.
- Keep client UI state separate from server state.

## Pseudocode / Decision Flow / Lifecycle / Policy Example

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
    snapshot previous cache
    write optimistic message
  onError:
    restore snapshot
  onSuccess:
    replace optimistic message with server message
  onSettled:
    invalidate conversation list and message list
```

## Our Notes / Team Decisions

- Message list and attendance screen need explicit query/mutation plans.
- Query keys must be tenant-aware.
- Auth refresh should be handled below screen components.
- Optimistic UI must include rollback and server reconciliation.
- Frontend boundaries should align with [Frontend FSD and Feature Boundaries](./frontend-fsd-feature-boundaries).

## External References

- TanStack Query: [React Overview](https://tanstack.com/query/latest/docs/framework/react/overview)

## Glossary

- Query: cached server read operation.
- Mutation: server write operation.
- Query key: cache identity.
- Invalidation: marking cached data stale so it refetches.
- Optimistic update: temporary client update before server success.
- Server state: data owned by the server and fetched by the client.

## Research Keywords

- `tanstack query auth refresh token cache invalidation`
- `optimistic updates react query`
- `query key factory tenant id`
- `message list query invalidation`
- `attendance screen mutation plan`

## Conclusion

TanStack Query works best when the team treats server state as a shared architecture concern. Tenant-aware query keys, predictable invalidation, and careful optimistic updates keep the UI fast without making it untrustworthy.
