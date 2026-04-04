---
title: Read / Write Replica Rules
description: Rule-based query routing strategy between read replica and write primary with consistency trade-off handling.
sidebar_position: 4
---

## Introduction

Using `read replica` infrastructure is not just a scaling tactic; it is a consistency decision. Query routing must be rule-based so teams know when to use `read replica` and when to force `write primary`.

## Why It Matters

If routing is implicit:

- users may not see recent updates due to replication lag,
- critical business flows can behave unpredictably,
- debugging becomes difficult because data freshness differs by path.

## Core Concepts

- `write primary`: authoritative node for writes and strict freshness reads.
- `read replica`: replicated node optimized for read load.
- Replication lag: delay between primary commit and replica visibility.
- Read-after-write consistency: guarantee that subsequent read sees recent write.

## Problem in Real Product Development

A blanket "all SELECT goes to replica" rule breaks quickly. Flows like auth, payment, permission changes, and onboarding steps often require immediate consistency and should read from `write primary`.

## Approach / Design

### Routing Rule Categories

- Always `write primary`
  - all writes
  - reads in same transaction as writes
  - security-sensitive reads (auth/session/permission state)
  - post-write confirmation reads

- Eligible for `read replica`
  - analytics dashboards
  - non-critical list views tolerating slight staleness
  - background reporting workloads

- Conditional
  - feature-level overrides based on SLA and freshness requirements

### Operational Controls

- expose lag metrics and route decision logs,
- define fallback to `write primary` during replica instability,
- include health-check gates in routing middleware.

## Sector Standard / Best Practice

- Treat consistency requirements as explicit product requirements.
- Encode routing decisions centrally, not ad hoc in query callsites.
- Keep a documented decision table by use case.
- Test failover and lag scenarios regularly.

## Pseudocode / Decision Flow / Lifecycle / Policy Example

```text
route_query(query, context):
  if query.is_write:
    return write_primary

  if context.requires_read_after_write:
    return write_primary

  if context.domain in ['auth', 'permission', 'payment_confirmation']:
    return write_primary

  if replica_lag_exceeds_threshold():
    return write_primary

  return read_replica
```

## Our Notes / Team Decisions

- We will use explicit rule-based thinking for `read replica` and `write primary` usage.
- Security and authorization-adjacent reads remain on primary by default.
- Product-level freshness expectations will define replica eligibility, not infrastructure preference alone.

## Glossary

- `read replica`: replicated read node used for scale-out reads.
- `write primary`: authoritative write node and strict-consistency read source.
- Replication lag: delay in replication propagation.
- Read-after-write: requirement to see newly committed data immediately.

## Research Keywords

- `read replica routing rules`
- `read after write consistency patterns`
- `postgresql replication lag mitigation`
- `primary replica query router design`
- `eventual consistency product tradeoffs`

## Conclusion

Replica usage should be policy-driven, not implicit. Clear routing rules protect correctness while still enabling scale, especially when combined with domain-aware consistency decisions.
