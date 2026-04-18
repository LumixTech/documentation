---
title: Session, Device, and Refresh Token Lifecycle
description: Redis-centered lifecycle design for sessions, access tokens, and refresh tokens with revoke, rotation, and logout-all behavior.
sidebar_position: 2
---

## Introduction

JWT signing is only one part of authentication. In real systems, lifecycle control is equally important: where sessions exist, which devices are active, and how access/refresh tokens are rotated or revoked.

This document defines lifecycle behavior for Redis-backed `USER_SESSIONS`, `ACCESS_TOKENS`, and `REFRESH_TOKENS`.

## Why It Matters

Without lifecycle modeling:

- logout behavior becomes inconsistent,
- suspicious sessions cannot be isolated quickly,
- support and compliance teams cannot explain account activity clearly.

The product needs deterministic behavior for `logout`, `logout-all`, token reuse, and revocation.

## Core Concepts

- `USER_SESSIONS`: authoritative list of active/inactive device session records.
- `ACCESS_TOKENS`: short-lived token state records keyed by `jti`.
- `REFRESH_TOKENS`: refresh token chain records bound to sessions.
- `device session`: link between user identity and client environment.
- Absolute timeout: maximum session lifetime regardless of activity.
- Idle timeout: session expires after inactivity window.
- Refresh token family: token lineage for rotation and replay detection.

## Problem in Real Product Development

Typical failure patterns:

- A stolen refresh token remains valid because old tokens are not invalidated.
- `logout` revokes session only in UI cache but leaves token state active.
- `logout-all` is triggered, but not all device sessions and token keys are revoked.

These gaps create security ambiguity and difficult incident response.

## Approach / Design

### Redis Data Model Responsibilities

- `USER_SESSIONS`
  - key: `session:{session_id}`
  - fields: `user_id`, `tenant_id`, device metadata, `status`, reason codes, timestamps

- `ACCESS_TOKENS`
  - key: `token:access:{jti}`
  - fields: `session_id`, `user_id`, `status`
  - TTL aligned with access token expiration

- `REFRESH_TOKENS`
  - key: `token:refresh:{hash}`
  - fields: `session_id`, `user_id`, `status`, `parent_token_id`, timestamps
  - TTL aligned with refresh expiration

- `USER -> SESSIONS` index
  - key: `user:sessions:{user_id}`
  - value: active session ids for `logout-all` and incident response

### Lifecycle Rules

- Every login creates a new `device session` unless policy allows reuse.
- Every authenticated request validates both JWT and Redis state (`jti`, `session_id`, session status).
- Every successful refresh rotates refresh token and replaces access token state.
- `logout` revokes only the current session and related access/refresh state.
- `logout-all` revokes all active sessions and all active access/refresh tokens for the user.

## Sector Standard / Best Practice

- Keep refresh tokens hashed at rest.
- Use `jti` for access token state lookup and targeted revocation.
- Separate session state and token state, but keep explicit references between them.
- Use Redis TTL for natural expiry and explicit status transitions for forced revocation.
- Record reason fields for revocation decisions to support audit and incident analysis.

## Pseudocode / Decision Flow / Lifecycle / Policy Example

```text
on AUTH_REQUEST(access_token):
  claims = verify_jwt(access_token)

  token = redis_get("token:access:" + claims.jti)
  session = redis_get("session:" + claims.session_id)

  if token missing or token.status != 'active':
    deny
  if session missing or session.status != 'active':
    deny

  allow

on REFRESH_ATTEMPT(refresh_token, session_context):
  token = redis_get("token:refresh:" + hash(refresh_token))

  if token not found:
    deny

  if token.status in ['revoked', 'expired']:
    deny

  if token.status == 'rotated':
    mark_session_high_risk(token.session_id)
    revoke_session(token.session_id)
    revoke_access_tokens_by_session(token.session_id)
    deny

  if session_context mismatches token.session_id policy:
    deny

  next_refresh = create_refresh_token(parent_id=token.id, session_id=token.session_id)
  next_access = create_access_token(session_id=token.session_id, jti=random_id())

  mark_token_status(token.id, 'rotated')
  redis_set("token:refresh:" + hash(next_refresh), session_id=token.session_id, status='active')
  redis_set("token:access:" + next_access.jti, session_id=token.session_id, status='active', ttl=next_access.ttl)

  return next_access, next_refresh

on LOGOUT_ALL(user_id):
  for session in active_sessions(user_id):
    revoke_session(session.id, reason='user_requested_logout_all')
    revoke_access_tokens_by_session(session.id)
    revoke_refresh_tokens_by_session(session.id)

  return success
```

## Our Notes / Team Decisions

- Redis-backed `USER_SESSIONS`, `ACCESS_TOKENS`, and `REFRESH_TOKENS` are mandatory; stateless-only auth modeling is explicitly rejected.
- Device-awareness is required for user safety and support visibility.
- We prefer explicit status transitions over implicit deletion for forensic traceability.
- This lifecycle design is intentionally aligned with [Spring Security Authentication Flow with JWT and Refresh Token](./auth-jwt-refresh-flow).

## Glossary

- `session`: persistent authentication context in server-side data model.
- `device session`: session bound to a specific device/client context.
- `jti`: unique identifier for access token state tracking.
- Rotation: issuing a successor refresh token and deactivating predecessor.
- Revocation: administrative or user-triggered forced invalidation.
- Token family: chain of refresh tokens related by rotation lineage.

## Research Keywords

- `redis token session lifecycle design`
- `jwt jti revocation strategy`
- `refresh token family rotation strategy`
- `device session lifecycle design`
- `logout all sessions token revocation`

## Conclusion

Lifecycle control is the difference between a basic login system and a production authentication platform. By explicitly modeling Redis token/session state, we preserve security control, operational clarity, and user trust.
