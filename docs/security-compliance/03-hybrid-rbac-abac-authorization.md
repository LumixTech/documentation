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

### Scope-First `canDo` Pattern (`school -> class -> student`)

For educational domain-style hierarchy, we can derive effective scope in this order:

1. assigned schools,
2. assigned classes,
3. assigned students.

Short-circuit rule:

- if school assignment exists, stop there and use school-level scope,
- if no school scope, evaluate class scope,
- if no class scope, fallback to student-level scope.

This avoids repeated authorization code in each endpoint and keeps one deterministic scope resolution path.

```java
@Component
@RequiredArgsConstructor
public class ScopeResolver {
  private final AssignmentRepository assignmentRepository;

  public Scope resolveScope(Long userId) {
    Set<Long> schoolIds = assignmentRepository.findSchoolIdsByUserId(userId);
    if (!schoolIds.isEmpty()) {
      return Scope.forSchools(schoolIds); // short-circuit: broadest scope
    }

    Set<Long> classIds = assignmentRepository.findClassIdsByUserId(userId);
    if (!classIds.isEmpty()) {
      return Scope.forClasses(classIds);
    }

    Set<Long> studentIds = assignmentRepository.findStudentIdsByUserId(userId);
    return Scope.forStudents(studentIds);
  }
}
```

### `@PreAuthorize` Integration for `getStudents`

Using `hasAuthority(...)` alone is usually not enough for this scenario because authority strings are static while scope is dynamic and user-specific. A custom bean method is a practical option:

```java
@Component("authz")
@RequiredArgsConstructor
public class AuthorizationFacade {
  private final ScopeResolver scopeResolver;
  private final StudentPolicy studentPolicy;

  public boolean canReadStudents(Authentication authentication, StudentQuery query) {
    Long userId = ((PrincipalUser) authentication.getPrincipal()).getUserId();
    Scope scope = scopeResolver.resolveScope(userId);
    return studentPolicy.canList(scope, query);
  }

  public boolean canDo(Authentication authentication, String action, Long targetStudentId) {
    Long userId = ((PrincipalUser) authentication.getPrincipal()).getUserId();
    Scope scope = scopeResolver.resolveScope(userId);
    return studentPolicy.canActOnStudent(scope, action, targetStudentId);
  }
}

@GetMapping("/students")
@PreAuthorize("@authz.canReadStudents(authentication, #query)")
public Page<StudentDto> getStudents(StudentQuery query) {
  return studentService.getStudents(query);
}
```

### Interceptor-Based Scope Filter Injection (Pagination-Friendly)

A request interceptor can enrich query filters with resolved scope before repository/service execution:

- resolve user scope once per request,
- inject `school_id`, `class_id`, or `student_id IN (...)` filter,
- run the same pagination path with scoped data only.

```java
@Component
@RequiredArgsConstructor
public class ScopeFilterInterceptor implements HandlerInterceptor {
  private final ScopeResolver scopeResolver;

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    Long userId = Security.currentUserId();
    Scope scope = scopeResolver.resolveScope(userId);
    ScopedFilter scopedFilter = ScopedFilter.fromRequest(request).apply(scope);
    request.setAttribute("scopedFilter", scopedFilter);
    return true;
  }
}
```

This pattern reduces code duplication and blocks unauthorized school/class/student access consistently.

## Sector Standard / Best Practice

- Keep policy vocabulary small and explicit.
- Separate permission assignment from policy evaluation engine.
- Make authorization decisions auditable with reason codes.
- Avoid embedding policy logic ad hoc in controllers.
- Prefer central scope resolution + reusable policy methods over copy-pasted per-endpoint checks.

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

resolve_scope(user_id):
  schools = assigned_schools(user_id)
  if schools not empty:
    return scope(type='school', ids=schools)

  classes = assigned_classes(user_id)
  if classes not empty:
    return scope(type='class', ids=classes)

  students = assigned_students(user_id)
  return scope(type='student', ids=students)

can_do(user, action, target_student):
  scope = resolve_scope(user.id)
  if scope.type == 'school':
    return target_student.school_id in scope.ids
  if scope.type == 'class':
    return target_student.class_id in scope.ids
  return target_student.id in scope.ids
```

## Our Notes / Team Decisions

- We are adopting a hybrid model by design: `user_permission` + `role_permission` + `common_permission` with explicit `allow`/`deny` behavior.
- Permission design must map from `role -> permission -> endpoint/use-case`, then validated against contextual attributes.
- RBAC-only design is intentionally avoided because it is not resilient for long-term product evolution.
- For student/school/class hierarchy, we prefer a centralized `canDo` + scope resolver pattern and interceptor-based filter injection.

## Glossary

- RBAC: role-based permission assignment.
- ABAC: attribute-based contextual policy evaluation.
- `role_permission`: role-derived permission record.
- `user_permission`: direct per-user permission record.
- `common_permission`: shared baseline permission.
- `allow`/`deny`: explicit authorization effects.
- `scope`: resolved data visibility boundary (`school`, `class`, `student`) for a request.

## Research Keywords

- `hybrid rbac abac authorization architecture`
- `allow deny precedence authorization policy`
- `user permission override role permission`
- `policy decision point policy enforcement point`
- `method security contextual authorization`

## Conclusion

A hybrid authorization model gives both structure and flexibility. RBAC provides maintainable baseline control, ABAC enforces real context, and explicit `allow`/`deny` precedence keeps decisions deterministic and auditable.
