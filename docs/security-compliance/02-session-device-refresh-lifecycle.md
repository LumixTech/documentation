---
title: Session, Device, and Refresh Token Lifecycle
description: Lifecycle design for USER_SESSIONS and REFRESH_TOKENS with revoke, rotation, and logout-all behavior.
sidebar_position: 2
---

## Introduction

JWT signing is only one part of authentication. In real systems, lifecycle control is equally important: where sessions exist, which devices are active, and how refresh tokens are rotated or revoked.

This document defines lifecycle behavior for `USER_SESSIONS` and `REFRESH_TOKENS`.

## Why It Matters

Without lifecycle modeling:

- logout behavior becomes inconsistent,
- suspicious sessions cannot be isolated quickly,
- support and compliance teams cannot explain account activity clearly.

The product needs deterministic behavior for `logout`, `logout-all`, token reuse, and revocation.

## Core Concepts

- `USER_SESSIONS`: authoritative list of active/inactive session records.
- `REFRESH_TOKENS`: refresh token chain records bound to sessions.
- `device session`: link between user identity and client environment.
- Absolute timeout: maximum session lifetime regardless of activity.
- Idle timeout: session expires after inactivity window.
- Refresh token family: token lineage for rotation and replay detection.

## Problem in Real Product Development

Typical failure patterns:

- A stolen refresh token remains valid because old tokens are not invalidated.
- `logout` revokes the current access token but leaves refresh chain active.
- `logout-all` only updates cache/session cookie but not persistent token state.

These gaps create security ambiguity and difficult incident response.

## Approach / Design

### Data Model Responsibilities

- `USER_SESSIONS`
  - user_id
  - session_id
  - tenant_id
  - device fingerprint/metadata
  - status (`active`, `revoked`, `expired`)
  - timestamps and reason codes

- `REFRESH_TOKENS`
  - refresh_token_id
  - session_id
  - hashed token value
  - parent token id (for rotation chain)
  - status (`active`, `rotated`, `revoked`, `expired`)
  - issued_at / expires_at / revoked_at

### Lifecycle Rules

- Every login creates a new `device session` unless policy allows reuse.
- Every successful refresh rotates refresh token and closes previous token.
- `logout` revokes only the current session and related refresh chain.
- `logout-all` revokes all active sessions and all active refresh tokens for the user.

## Sector Standard / Best Practice

- Keep refresh tokens hashed at rest.
- Treat refresh token reuse as a high-risk signal.
- Separate session state and token state, but keep explicit references between them.
- Record reason fields for revocation decisions to support audit and incident analysis.

## Pseudocode / Decision Flow / Lifecycle / Policy Example

```text
on REFRESH_ATTEMPT(refresh_token, session_context):
  token = lookup_refresh_token(hash(refresh_token))

  if token not found:
    deny

  if token.status in ['revoked', 'expired']:
    deny

  if token.status == 'rotated':
    mark_session_high_risk(token.session_id)
    revoke_session(token.session_id)
    deny

  if session_context mismatches token.session_id policy:
    deny

  next_token = create_refresh_token(parent_id=token.id, session_id=token.session_id)
  mark_token_status(token.id, 'rotated')
  issue_new_access_token(token.session_id)
  return success

on LOGOUT_ALL(user_id):
  for session in active_sessions(user_id):
    revoke_session(session.id, reason='user_requested_logout_all')

  revoke_refresh_tokens_by_user(user_id)
  return success
```

## Our Notes / Team Decisions

- `USER_SESSIONS` and `REFRESH_TOKENS` are mandatory; JWT-only modeling is explicitly rejected.
- Device-awareness is required for user safety and support visibility.
- We prefer explicit status transitions over implicit deletion for forensic traceability.
- This lifecycle design is intentionally aligned with [Spring Security Authentication Flow with JWT and Refresh Token](./auth-jwt-refresh-flow).

## Glossary

- `session`: persistent authentication context in server-side data model.
- `device session`: session bound to a specific device/client context.
- Rotation: issuing a successor refresh token and deactivating predecessor.
- Revocation: administrative or user-triggered forced invalidation.
- Token family: chain of refresh tokens related by rotation lineage.

## Research Keywords

- `refresh token family rotation strategy`
- `device session lifecycle design`
- `logout all sessions token revocation`
- `hashed refresh token storage`
- `refresh token replay detection`

## Conclusion

Lifecycle control is the difference between a basic login system and a production authentication platform. By modeling `USER_SESSIONS` and `REFRESH_TOKENS` explicitly, we preserve security control, operational clarity, and user trust.

