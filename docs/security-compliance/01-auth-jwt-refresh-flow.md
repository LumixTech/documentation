---
title: Spring Security Authentication Flow with JWT and Refresh Token
description: Authentication architecture using stateless access token validation with stateful refresh token and device session control.
sidebar_position: 1
---

## Introduction

The authentication flow is the main security gate of the product. If this layer is weak, every downstream authorization, tenant isolation, and audit control becomes unreliable.

This design uses a hybrid model:

- `access token`: stateless and short-lived
- `refresh token`: stateful and revocable
- `session` and `device session`: stateful and lifecycle-managed

For the companion lifecycle document, see [Session, Device, and Refresh Token Lifecycle](./session-device-refresh-lifecycle).

## Why It Matters

A JWT-only approach looks simple but fails under real product operations:

- You cannot reliably do `logout-all` without server-side state.
- Token theft response is weak if refresh usage is not tracked.
- Device-level trust and revocation cannot be modeled with access token validation alone.

The hybrid design keeps API validation fast while preserving operational control.

## Core Concepts

- `access token`: sent on API calls, validated by Spring Security resource server logic, expires quickly.
- `refresh token`: exchanged for a new `access token`, stored and tracked server-side.
- `session`: server-side record of authenticated context.
- `device session`: session scoped to a client device/app instance.
- Token rotation: every refresh call issues a new refresh token and invalidates the previous one.
- Token revocation: server marks token/session invalid before natural expiration.

## Problem in Real Product Development

In production, users log in from multiple devices, rotate networks, and occasionally lose devices. Security incidents require immediate revocation and forensic traceability.

If we model everything as stateless JWT:

- security team cannot force-kill risky sessions quickly,
- support team cannot explain which session performed which action,
- compliance flows become harder to prove during investigation.

## Approach / Design

### Endpoint Lifecycle

- `POST /auth/login`
- `POST /auth/refresh`
- `POST /auth/logout`
- `POST /auth/logout-all`

### Design Principles

- Keep access verification stateless for request-time performance.
- Keep refresh/session stateful for lifecycle control.
- Bind refresh tokens to `device session` records.
- Enforce refresh token rotation and replay detection.

### Integration Notes for Spring Security

- Use `SecurityFilterChain` for route-level access control.
- Use method-level authorization annotations where endpoint rules need domain context.
- Treat refresh handling as an application workflow (token store + policy checks), not only as JWT signature verification.

## Sector Standard / Best Practice

Common enterprise practice is:

- short access token TTL,
- persistent refresh token tracking,
- explicit logout and revoke semantics,
- auditability for session-affecting events.

Spring Security sector usage generally separates concerns:

- request authentication and authorization in the filter chain,
- token/session lifecycle orchestration in application services.

## Pseudocode / Decision Flow / Lifecycle / Policy Example

```text
on LOGIN(credentials, device_info):
  user = authenticate(credentials)
  if user invalid:
    deny

  session = create_session(user_id=user.id, device=device_info)
  access_token = sign_access_token(sub=user.id, tenant_id=user.tenant_id, session_id=session.id)
  refresh_token = issue_refresh_token(session_id=session.id)

  store_refresh_token(session.id, hash(refresh_token), status='active')
  return access_token, refresh_token

on REFRESH(refresh_token):
  token_record = find_active_refresh_token(hash(refresh_token))
  if token_record missing:
    deny

  if token_record revoked or expired:
    deny

  rotate_refresh_token(token_record)
  new_access_token = sign_access_token(sub=token_record.user_id, tenant_id=token_record.tenant_id, session_id=token_record.session_id)
  new_refresh_token = issue_refresh_token(session_id=token_record.session_id)

  return new_access_token, new_refresh_token

on LOGOUT(session_id):
  revoke_session(session_id)
  revoke_all_refresh_tokens(session_id)
  return success

on LOGOUT_ALL(user_id):
  sessions = find_sessions(user_id)
  revoke_sessions(sessions)
  revoke_all_user_refresh_tokens(user_id)
  return success
```

## Our Notes / Team Decisions

- We explicitly use a hybrid model: `access token` stateless, `refresh token` stateful, `session` stateful.
- Refresh token lifecycle must be coupled to `device session` so revocation decisions remain explainable.
- `logout-all` is a first-class business requirement, not an optional admin tool.
- Security behavior should remain consistent with [Hybrid Authorization Model: RBAC + ABAC](./hybrid-rbac-abac-authorization).

## Glossary

- `access token`: short-lived token used on API requests.
- `refresh token`: long-lived token used to obtain a new access token.
- `session`: server-managed authenticated context.
- `device session`: session record for a specific device/client.
- Rotation: invalidating an old refresh token when issuing a new one.
- Revocation: server-side invalidation before expiration.

## Research Keywords

- `spring security jwt bearer token architecture`
- `spring security securityfilterchain resource server jwt`
- `refresh token rotation replay detection`
- `logout all devices authentication design`
- `stateless access token stateful refresh token`

## Conclusion

A production-grade auth system should not choose between stateless and stateful models; it should combine them intentionally. Stateless `access token` validation gives performance, while stateful refresh and session management gives control, incident response capability, and long-term maintainability.

