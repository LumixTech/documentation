---
title: Hybrid Authorization Model with RBAC and ABAC
description: Authorization design using role_permission, user_permission, common_permission, and allow/deny precedence.
sidebar_position: 3
---

## Introduction

Role-based access control is necessary but not sufficient for most real products. As product complexity grows, exceptions emerge: temporary overrides, ownership checks, tenant-scoped rules, and context-sensitive restrictions.

This document defines a hybrid model that combines RBAC and ABAC with explicit precedence.

## Why It Matters

A role-only model tends to fail in three ways:

- it cannot represent specific exceptions without role explosion,
- it pushes complex conditions into endpoint code,
- it becomes hard to audit and reason about authorization decisions.

The hybrid model keeps permissions manageable and policy decisions explainable.

## Core Concepts

- `role_permission`: permission assigned through user roles.
- `user_permission`: direct user-level override permission.
- `common_permission`: baseline permission available across selected contexts.
- ABAC attributes: contextual data such as `tenant_id`, ownership, department, environment, risk level.
- `allow` and `deny`: explicit policy effects with deterministic precedence.

## Problem in Real Product Development

Common production scenarios:

- A support role can view tickets, but only in its assigned tenant.
- A manager role can approve payments, except in elevated-risk states.
- A user gets temporary explicit `allow` for a migration period.

Pure RBAC struggles here because every exception creates another role variant.

## Approach / Design

### Policy Evaluation Layers

1. Resolve user identity and tenant context.
2. Aggregate `common_permission` set.
3. Merge `role_permission` by assigned roles.
4. Apply `user_permission` overrides.
5. Evaluate ABAC conditions.
6. Apply precedence rules for `allow` and `deny`.

### Recommended Precedence

- Explicit `deny` overrides explicit `allow`.
- `user_permission` overrides `role_permission` only when policy owner decides this is valid for that permission domain.
- ABAC conditions can invalidate any provisional `allow` when contextual constraints fail.

### Endpoint and Use-case Thinking

Authorization should be designed from use cases, not only from endpoints:

- endpoint guard: coarse check
- service/use-case guard: domain-level decision
- data-level check: ownership and tenant boundaries

For tenant data boundaries, pair this model with [Tenant-based Row-Level Security (RLS)](../database-architecture/tenant-based-rls-policy-design).

## Sector Standard / Best Practice

- Keep policy vocabulary small and explicit.
- Separate permission assignment from policy evaluation engine.
- Make authorization decisions auditable with reason codes.
- Avoid embedding policy logic ad hoc in controllers.

## Pseudocode / Decision Flow / Lifecycle / Policy Example

```text
evaluate_authorization(user, action, resource, context):
  permissions = {}

  permissions += common_permission_for(action)
  permissions += role_permission_for(user.roles, action)
  permissions += user_permission_for(user.id, action)

  if has_explicit_deny(permissions, resource):
    return deny(reason='explicit_deny')

  if not has_any_allow(permissions, resource):
    return deny(reason='no_allow_rule')

  if not abac_constraints_pass(user, resource, context):
    return deny(reason='abac_constraint_failed')

  return allow(reason='permission_and_context_match')
```

## Our Notes / Team Decisions

- We are adopting a hybrid model by design: `user_permission` + `role_permission` + `common_permission` with explicit `allow`/`deny` behavior.
- Permission design must map from `role -> permission -> endpoint/use-case`, then validated against contextual attributes.
- RBAC-only design is intentionally avoided because it is not resilient for long-term product evolution.

## Glossary

- RBAC: role-based permission assignment.
- ABAC: attribute-based contextual policy evaluation.
- `role_permission`: role-derived permission record.
- `user_permission`: direct per-user permission record.
- `common_permission`: shared baseline permission.
- `allow`/`deny`: explicit authorization effects.

## Research Keywords

- `hybrid rbac abac authorization architecture`
- `allow deny precedence authorization policy`
- `user permission override role permission`
- `policy decision point policy enforcement point`
- `method security contextual authorization`

## Conclusion

A hybrid authorization model gives both structure and flexibility. RBAC provides maintainable baseline control, ABAC enforces real context, and explicit `allow`/`deny` precedence keeps decisions deterministic and auditable.

