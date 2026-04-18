---
title: Modular Monolith and Spring Modulith
description: Architecture guidance for modular monolith design using Spring Modulith, DDD boundaries, and hexagonal layering.
sidebar_position: 4
---

## Introduction

Monoliths fail not because they are monoliths, but because boundaries are unclear. Teams often react by moving too early to microservices and inherit distributed complexity without solving domain coupling.

This document explains modular monolith discipline with Spring Modulith, aligned with DDD and hexagonal design.

## Why It Matters

Without modular boundaries:

- dependencies become implicit and circular,
- data ownership gets ambiguous,
- changes create large blast radius,
- microservice migration becomes risky and expensive.

Modular monolith architecture keeps deployment simple while enforcing internal separation.

## Core Concepts

- Modular monolith: single deployable, multiple bounded modules.
- Spring Modulith: tooling to define module boundaries and detect illegal dependencies.
- Bounded context: business capability boundary with its own model and language.
- API-first module contract: external module access only via public API package.
- Event-driven module collaboration: decoupled cross-module communication.

## Problem in Real Product Development

Typical monolith erosion pattern:

- one module directly reads another module's repositories,
- internal entities are reused across unrelated modules,
- cross-module joins bypass business ownership.

This gradually turns business rules into shared mutable logic.

## Approach / Design

### Module Boundary Template

```text
com.company.academic
  - api
  - internal.application
  - internal.domain
  - internal.infrastructure
```

The same structure is repeated for modules like `finance`, `core-iam`, and `communication`.

### Layer Responsibility

- `api`: public contract used by other modules.
- `internal.application`: use-case orchestration and transaction boundaries.
- `internal.domain`: entities, aggregates, value objects, domain rules.
- `internal.infrastructure`: database adapters, messaging adapters, external clients.

### Cross-Module Communication Rule

- no direct access to `internal.*` of another module,
- no shared repository/entity access across module boundaries,
- communicate through module API and domain events.

## Sector Standard / Best Practice

- Enforce architectural boundaries with tests, not conventions only.
- Keep data ownership explicit: each table has a clear owning module.
- Favor event-driven integration for low coupling between modules.
- Keep module APIs stable and versioned where needed.
- Design modular monolith first, split to microservices later only when necessary.

## Pseudocode / Decision Flow / Lifecycle / Policy Example

```text
on FEATURE_REQUEST(capability):
  owner_module = map_capability_to_module(capability)

  if request_touches_other_module_internal(owner_module):
    reject_and_refactor_to_api_or_event()

  implement_use_case_in(owner_module.internal.application)
  enforce_rules_in(owner_module.internal.domain)

  if cross_module_effect_exists:
    publish_domain_event()
```

## Our Notes / Team Decisions

- Preferred architecture combination: Spring Modulith + DDD + Hexagonal layering.
- Initial module boundaries: `core-iam`, `academic`, `finance`, `communication`.
- Internal encapsulation is mandatory: only `api` is externally consumable.
- Event-driven integration is preferred for cross-module side effects.
- Repository/entity sharing across modules is explicitly rejected.

## Glossary

- Modular monolith: modular architecture in one deployable unit.
- Spring Modulith: boundary verification and module-oriented structuring in Spring apps.
- Encapsulation: hiding internal implementation from other modules.
- Blast radius: scope of side effects caused by a change.
- Domain event: module-level business event used for integration.

## Research Keywords

- `spring modulith architecture patterns`
- `modular monolith vs microservices`
- `ddd bounded context in monolith`
- `hexagonal architecture in modular monolith`
- `event driven module integration`

## Conclusion

The real architectural win is not "microservices first" but "boundaries first." A modular monolith with Spring Modulith gives strong separation, manageable operations, and a safer path to future service decomposition.
