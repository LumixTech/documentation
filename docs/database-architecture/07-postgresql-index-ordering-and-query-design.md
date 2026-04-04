---
title: PostgreSQL Composite Index Ordering
description: Why composite B-tree index column order matters, with tenant_id-first query design guidance.
sidebar_position: 1
---

## Introduction

Composite indexes are not just a performance tweak; they are part of data access architecture. In PostgreSQL B-tree indexes, column order changes how effectively queries can use the index.

## Why It Matters

Wrong index ordering leads to:

- larger scan ranges,
- unstable performance under load,
- wasted storage and maintenance cost for low-value indexes.

In multi-tenant systems, this can become a tenant isolation and scalability issue.

## Core Concepts

- B-tree index: ordered tree structure optimized for range and equality lookups.
- Composite index: index with multiple columns.
- Leftmost-prefix behavior: planner can best use predicates starting from leading indexed columns.
- Selectivity and cardinality: affect planner choice between index scan and sequential scan.

## Problem in Real Product Development

A common anti-pattern is creating `INDEX (user_id, tenant_id)` while most queries filter by `tenant_id` first. This mismatch reduces index usefulness and can increase latency variance.

## Approach / Design

### Guiding Example

If query patterns are tenant-scoped first, prefer:

```sql
CREATE INDEX idx_orders_tenant_user ON orders (tenant_id, user_id);
```

Reasoning:

- Database narrows by `tenant_id` first.
- Then narrows by `user_id` inside tenant scope.

Phone book analogy:

- A phone book sorted by surname then name is fast when you search surname first.
- If you search only by name, that ordering helps less.

### Query Pattern Alignment

Design indexes from observed query shapes:

- most common `WHERE` predicates
- join conditions
- sort requirements
- tenant boundary assumptions

## Sector Standard / Best Practice

- Model indexes from production query patterns, not from entity field order.
- Validate with `EXPLAIN (ANALYZE, BUFFERS)`.
- Re-check index effectiveness after feature growth.
- Avoid over-indexing; every index increases write overhead.

## Pseudocode / Decision Flow / Lifecycle / Policy Example

```text
for each critical_query_pattern:
  predicates = extract_where_predicates(critical_query_pattern)
  lead_column = highest_frequency_tenant_scope_or_high_selectivity(predicates)

  proposed_index = build_composite_index(lead_column, next_most_selective(predicates))
  plan = explain_analyze(critical_query_pattern, proposed_index)

  if plan improves latency and buffer profile:
    accept_index
  else:
    revise_index_order
```

## Our Notes / Team Decisions

- Our filtering strategy starts with `tenant_id`; index design should reflect this.
- The default composite pattern for tenant-scoped entities should begin with `tenant_id` when query patterns support it.
- This direction aligns with tenant isolation controls described in [Tenant-based Row-Level Security (RLS)](./tenant-based-rls-policy-design).

## Glossary

- Composite index: multi-column index.
- Leftmost-prefix: leading-column-first usage behavior in composite indexes.
- Selectivity: how well a predicate narrows rows.
- Planner: PostgreSQL component choosing execution plans.
- Index scan: retrieving rows via index traversal.

## Research Keywords

- `postgresql composite index column order`
- `postgresql leftmost prefix btree`
- `tenant_id first index strategy`
- `explain analyze index validation`
- `index write overhead tradeoffs`

## Conclusion

Composite index ordering is an architectural decision, not a cosmetic one. For tenant-scoped workloads, leading with `tenant_id` gives clearer query behavior and better long-term performance predictability.

