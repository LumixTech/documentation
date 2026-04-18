---
title: Spring Security Authentication Flow with JWT and Refresh Token
description: Stateful authentication architecture with Redis-backed access token, refresh token, and device session lifecycle control.
sidebar_position: 1
---

## Introduction

The authentication flow is the main security gate of the product. If this layer is weak, every downstream authorization, tenant isolation, and audit control becomes unreliable.

This design uses a stateful token/session model with Redis:

- `access token`: short-lived JWT, stateful tracking in Redis
- `refresh token`: stateful, revocable, and rotation-managed in Redis
- `session` and `device session`: stateful lifecycle records in Redis

For the companion lifecycle document, see [Session, Device, and Refresh Token Lifecycle](./session-device-refresh-lifecycle).

## Why It Matters

A JWT signature check alone is not enough for production operations:

- `logout-all` cannot be enforced reliably without central token/session state.
- Token theft response is weak if active token lineage is not tracked.
- Device-level trust and revocation need server-side state and fast lookups.

Redis-backed state gives deterministic revocation and operational control while preserving low-latency auth checks.

## Core Concepts

- `access token`: JWT sent on API calls, includes `jti`, `session_id`, and short TTL.
- `refresh token`: exchanged for a new `access token`, hashed and tracked in Redis.
- `session`: server-side authenticated context bound to user + device.
- `device session`: per-device session boundary for risk isolation.
- Token rotation: every refresh call issues a new refresh token and deactivates the previous one.
- Token revocation: server-side invalidation before natural expiration.

## Problem in Real Product Development

In production, users log in from multiple devices, rotate networks, and occasionally lose devices. Security incidents require immediate revocation and forensic traceability.

If `access token` is handled as stateless-only:

- security team cannot force-kill risky sessions immediately,
- support team cannot explain which active token/session performed which action,
- compliance investigations lose confidence in revocation timing.

## Approach / Design

### Endpoint Lifecycle

- `POST /auth/login`
- `POST /auth/refresh`
- `POST /auth/logout`
- `POST /auth/logout-all`

### Design Principles

- Keep JWT cryptographic validation (`iss`, `aud`, signature, exp) in the request path.
- Add Redis state validation for token/session status (`active`, `revoked`, `expired`).
- Bind all tokens to `device session` records.
- Enforce refresh token rotation and replay detection.
- Revoke access token + refresh token + session together on logout workflows.

### Integration Notes for Spring Security

- Use `SecurityFilterChain` for route-level access control.
- Validate JWT claims first, then validate Redis state (`jti`, `session_id`) before granting access.
- Keep refresh lifecycle logic in application services with explicit Redis status transitions.

## Sector Standard / Best Practice

Common enterprise practice is:

- short access token TTL,
- Redis-backed token/session state with explicit statuses,
- refresh token hashing and rotation,
- explicit logout and revoke semantics,
- auditability for session-affecting events.

Spring Security usage generally separates concerns:

- request authentication/authorization in the filter chain,
- token/session lifecycle orchestration in application services.

## Pseudocode / Decision Flow / Lifecycle / Policy Example

```text
on LOGIN(credentials, device_info):
  user = authenticate(credentials)
  if user invalid:
    deny

  session = create_session(user_id=user.id, device=device_info)
  redis_set("session:" + session.id, status='active', user_id=user.id, tenant_id=user.tenant_id)

  access_token = sign_access_token(sub=user.id, tenant_id=user.tenant_id, session_id=session.id, jti=random_id())
  refresh_token = issue_refresh_token(session_id=session.id)

  redis_set("token:access:" + access_token.jti, session_id=session.id, status='active', ttl=access_token.ttl)
  redis_set("token:refresh:" + hash(refresh_token), session_id=session.id, status='active', ttl=refresh_token.ttl)

  return access_token, refresh_token

on AUTHENTICATED_REQUEST(access_token):
  claims = verify_jwt(access_token)
  token_state = redis_get("token:access:" + claims.jti)
  session_state = redis_get("session:" + claims.session_id)

  if token_state missing or token_state.status != 'active':
    deny
  if session_state missing or session_state.status != 'active':
    deny

  allow

on REFRESH(refresh_token):
  token_record = redis_get("token:refresh:" + hash(refresh_token))
  if token_record missing or token_record.status != 'active':
    deny

  rotate_refresh_token(token_record)
  new_access_token = sign_access_token(sub=token_record.user_id, tenant_id=token_record.tenant_id, session_id=token_record.session_id, jti=random_id())
  new_refresh_token = issue_refresh_token(session_id=token_record.session_id)

  redis_set("token:access:" + new_access_token.jti, session_id=token_record.session_id, status='active', ttl=new_access_token.ttl)
  redis_set("token:refresh:" + hash(new_refresh_token), session_id=token_record.session_id, status='active', ttl=new_refresh_token.ttl)

  return new_access_token, new_refresh_token

on LOGOUT(session_id):
  revoke_session(session_id)
  revoke_access_tokens_by_session(session_id)
  revoke_refresh_tokens_by_session(session_id)
  return success

on LOGOUT_ALL(user_id):
  sessions = find_sessions(user_id)
  revoke_sessions(sessions)
  revoke_all_user_access_tokens(user_id)
  revoke_all_user_refresh_tokens(user_id)
  return success
```

## Our Notes / Team Decisions

- We use a fully stateful auth model for runtime control: `access token`, `refresh token`, and `session` are tracked in Redis.
- `logout-all` is a first-class business requirement, not an optional admin tool.
- Revocation must be explainable through explicit token/session state transitions.
- Security behavior should remain consistent with [Hybrid Authorization Model: RBAC + ABAC](./hybrid-rbac-abac-authorization).

## Glossary

- `access token`: short-lived JWT used on API requests and validated against Redis token/session state.
- `refresh token`: long-lived token used to obtain a new access token via controlled rotation.
- `session`: server-managed authenticated context.
- `device session`: session record for a specific device/client.
- `jti`: unique token identifier used for Redis state lookup.
- Rotation: invalidating an old refresh token when issuing a new one.
- Revocation: server-side invalidation before expiration.

## Research Keywords

- `spring security jwt redis token state`
- `stateful access token redis session validation`
- `refresh token rotation replay detection`
- `logout all devices authentication design`
- `jwt jti revocation redis`

## Conclusion

A production-grade auth system must combine JWT integrity checks with stateful control. By keeping token and session runtime state in Redis, we gain fast revocation, better incident response, and clear operational visibility.
