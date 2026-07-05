---
title: Audit Log Design
description: Audit architecture answering who did what, when, and under which context, with AUDIT_LOGS schema guidance.
sidebar_position: 5
---

## Introduction

An `audit log` is not a generic application log. It is a compliance and trust artifact that must answer a precise question: who did what, when, and in which context.

This is critical for domains like payment, health, counseling/PDR, and permission changes.

## Why It Matters

Without robust audit design:

- incident investigations become speculation,
- privileged changes cannot be traced reliably,
- compliance response becomes slow and risky.

Audit quality directly affects legal defensibility and operational confidence.

## Core Concepts

- Append-only records to prevent silent history rewrite.
- Actor-action-target model.
- Immutable or tamper-evident storage strategy.
- Correlation and request identifiers for traceability.
- Redaction rules for sensitive payloads.

## Problem in Real Product Development

Teams often log too little or too much:

- Too little: cannot reconstruct incident context.
- Too much: sensitive data leakage and storage inflation.

Another frequent issue is inconsistent semantics across services, which makes cross-service investigations painful.

## Approach / Design

### `AUDIT_LOGS` Table Direction

Recommended columns:

- audit_id
- occurred_at
- actor_user_id (nullable for system actor)
- actor_type (`user`, `system`, `service`)
- tenant_id
- action_type
- target_type
- target_id
- outcome (`allow`, `deny`, `error`)
- reason_code
- request_id / correlation_id
- ip_address
- device_session_id
- before_snapshot (optional, minimized)
- after_snapshot (optional, minimized)
- metadata (JSON)

### Critical Action List (Minimum)

- authentication events: login, refresh, logout, logout-all
- permission changes (`role_permission`, `user_permission`, `common_permission`)
- high-risk domain actions (payment approvals, health/counseling profile access)
- data export and DSAR actions
- tenant administration changes

## Sector Standard / Best Practice

- Keep `audit log` separate from debug/application logs.
- Use stable action taxonomy and reason codes.
- Record policy decision outcomes, including `deny`.
- Restrict direct mutation rights on audit storage.
- Apply retention policy to audit data according to legal obligations.

## Pseudocode / Decision Flow / Lifecycle / Policy Example

```text
record_audit(event):
  normalized = {
    occurred_at: now_utc(),
    actor_user_id: event.actor_user_id,
    actor_type: event.actor_type,
    tenant_id: event.tenant_id,
    action_type: event.action_type,
    target_type: event.target_type,
    target_id: event.target_id,
    outcome: event.outcome,
    reason_code: event.reason_code,
    request_id: event.request_id,
    device_session_id: event.device_session_id,
    metadata: redact_sensitive(event.metadata)
  }

  write_append_only('AUDIT_LOGS', normalized)
```

## Our Notes / Team Decisions

- `AUDIT_LOGS` is required for sensitive domains including payment, health, counseling/PDR, and permission administration.
- We will log both successful and denied actions where security decisions matter.
- Permission change events must include previous and new values for `allow`/`deny` semantics where legally permissible.
- Audit design must integrate with [Retention, Anonymization, and DSAR Flow](./retention-anonymization-dsar).

## Glossary

- `audit log`: tamper-resistant record of critical actions.
- Append-only: records are inserted, not edited in place.
- Correlation ID: identifier linking related events across systems.
- Reason code: normalized explanation for outcome.
- Snapshot: controlled before/after representation of changed data.

## Research Keywords

- `audit log schema design who did what when`
- `append only audit table best practices`
- `tamper evident logging architecture`
- `sensitive field redaction in audit logs`
- `authorization decision logging allow deny`

## Conclusion

A strong audit strategy creates both compliance value and operational clarity. By standardizing `AUDIT_LOGS`, critical action taxonomy, and append-only behavior, we make security decisions observable and investigations actionable.

