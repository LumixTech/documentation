---
title: Tenant-based Row-Level Security (RLS)
description: Database-level tenant isolation using tenant_id-scoped RLS policy design.
sidebar_position: 3
---

## Introduction

In multi-tenant systems, application-layer filtering alone is not a complete security boundary. `RLS policy` adds database-enforced tenant isolation to reduce data leakage risk.

## Why It Matters

Without DB-level controls, a missed `WHERE tenant_id = ...` in one query can expose cross-tenant data. RLS provides a second enforcement layer that remains active even if application code is imperfect.

## Core Concepts

- `tenant_id`: tenant boundary key in shared tables.
- RLS: PostgreSQL row-level policy enforcement.
- `USING`: read visibility condition.
- `WITH CHECK`: write/insert/update condition.
- Session context: per-connection tenant value used by policy expressions.

## Problem in Real Product Development

As codebases evolve, query complexity and service count grow. Depending solely on service-layer discipline increases risk of accidental tenant boundary violations.

## Approach / Design

### Policy Strategy

- Ensure tenant-scoped tables include `tenant_id`.
- Enable RLS explicitly on target tables.
- Define read policy with `USING` and write policy with `WITH CHECK`.
- Set connection/session tenant context before query execution.

### SQL Pattern Example

```sql
ALTER TABLE invoices ENABLE ROW LEVEL SECURITY;

CREATE POLICY invoices_tenant_read
  ON invoices
  FOR SELECT
  USING (tenant_id = current_setting('app.tenant_id')::uuid);

CREATE POLICY invoices_tenant_write
  ON invoices
  FOR INSERT, UPDATE
  WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);
```

## Sector Standard / Best Practice

- Keep policy names and conventions consistent across tables.
- Fail closed when tenant context is missing.
- Limit bypass roles and monitor their usage.
- Test policies with positive and negative tenant scenarios.

## Pseudocode / Decision Flow / Lifecycle / Policy Example

```text
on REQUEST_START(auth_context):
  set_db_setting('app.tenant_id', auth_context.tenant_id)

on SELECT_OR_MUTATION(table, query):
  if table has RLS policy:
    db evaluates USING / WITH CHECK automatically

  if policy fails:
    deny
```

## Our Notes / Team Decisions

- RLS policies will be written `tenant_id` based.
- Tenant isolation is a database concern in addition to application authorization.
- For permission semantics, RLS works with [Hybrid Authorization Model with RBAC and ABAC](../security-compliance/hybrid-rbac-abac-authorization) rather than replacing it.

## Glossary

- `tenant_id`: tenant boundary identifier.
- `RLS policy`: database-level row access rule.
- `USING`: policy expression controlling read visibility.
- `WITH CHECK`: policy expression controlling writes.
- Fail closed: deny access when context is incomplete or invalid.

## Research Keywords

- `postgresql row level security tenant isolation`
- `postgresql using with check policy examples`
- `current_setting tenant context rls`
- `application auth plus database rls`
- `multi tenant db security defense in depth`

## Conclusion

Tenant isolation should not depend on perfect application filtering. `RLS policy` anchored on `tenant_id` provides reliable defense-in-depth and significantly lowers cross-tenant leakage risk.

