---
title: "Test Pyramid: Unit, Integration, Contract, and E2E Strategy"
description: Test strategy for distributing product use cases across unit, integration, contract, and end-to-end test levels.
sidebar_position: 4
---

## Introduction

The test pyramid is a strategy for balancing confidence, speed, cost, and maintainability across different test levels.

Not every behavior should be tested with E2E tests. E2E tests are valuable, but they are slower, more brittle, and harder to diagnose. A healthy test strategy uses the right level for the risk being tested.

## Why It Matters

Poor test distribution creates two opposite problems:

- too many low-level tests that miss integration failures,
- too many E2E tests that make CI slow and flaky.

The goal is not maximum number of tests. The goal is fast, meaningful confidence.

## Core Concepts

- Unit test: verifies small logic in isolation.
- Integration test: verifies collaboration with real infrastructure or framework behavior.
- Contract test: verifies API/event expectations between producer and consumer.
- E2E test: verifies a full user workflow through the UI or external boundary.
- Test pyramid: principle of having more low-level tests and fewer high-level tests.
- Test slice: focused test scope for one layer or module.
- Happy path: expected successful workflow.
- Sad path: failure or validation workflow.

## Problem in Real Product Development

Teams often test by visibility:

```text
If users see it, test it in the browser.
```

That can be wasteful. For example:

- authorization matrix logic is better covered with unit and integration tests,
- database constraints need integration/migration tests,
- API compatibility needs contract tests,
- role-based UI navigation needs E2E tests.

Each use case should be assigned to the cheapest test level that catches the relevant risk.

## Approach / Design

### Use Case Distribution

| Use case | Unit | Integration | Contract | E2E |
| --- | --- | --- | --- | --- |
| Login token validation | token policy, expiration rules | Spring Security filter and DB/session state | auth API response shape | login happy/sad path |
| Attendance marking | attendance rules | repository, transaction, outbox | attendance API/event contract | teacher marks attendance |
| Chat message send | validation and permission rules | persistence and websocket/event integration | message event schema | teacher/parent sees message |
| Payment attempt | fee calculation rules | provider adapter with test double, audit log | provider callback contract | admin/payment workflow smoke |
| Permission update | allow/deny precedence | authorization service with DB state | admin API contract | admin role UI flow |
| DSAR workflow | state transition rules | anonymization and audit integration | DSAR API contract | admin approval flow if critical |

### Test Level Guidance

Use unit tests when:

- logic is deterministic,
- no framework or infrastructure behavior is required,
- edge cases are numerous.

Use integration tests when:

- database behavior matters,
- transactions matter,
- Spring security/module wiring matters,
- migrations and repositories must be validated.

Use contract tests when:

- another frontend/service consumes an API,
- event schemas must remain compatible,
- provider callbacks have strict shape requirements.

Use E2E tests when:

- role-based UI behavior matters,
- browser behavior matters,
- multiple system layers must be proven together.

## Sector Standard / Best Practice

- Keep unit tests fast and numerous.
- Use integration tests for real persistence and framework boundaries.
- Use contract tests to protect service/API/event compatibility.
- Keep E2E tests focused on critical workflows.
- Test both happy path and sad path for high-risk use cases.
- Avoid duplicating the same assertion at every level.
- Make CI stages reflect test cost and confidence.

## Pseudocode / Decision Flow / Lifecycle / Policy Example

```text
choose_test_level(use_case, risk):
  if risk is pure business rule:
    return unit_test

  if risk requires database/framework/module wiring:
    return integration_test

  if risk is producer-consumer compatibility:
    return contract_test

  if risk is user workflow across UI and backend:
    return e2e_test

  return integration_test
```

## Our Notes / Team Decisions

- Project use cases should be distributed across test levels instead of pushed into E2E by default.
- Critical flows need both happy path and sad path coverage.
- Authorization and tenant isolation should be tested below UI level as much as possible.
- E2E tests should cover admin, teacher, and parent workflows as described in [Playwright E2E, RBAC UI Tests, and Form Flows](../frontend-architecture/playwright-e2e-rbac-ui-form-flows).

## External References

- Martin Fowler: [The Practical Test Pyramid](https://martinfowler.com/articles/practical-test-pyramid.html)

## Glossary

- Unit test: isolated test for small logic.
- Integration test: test involving real framework or infrastructure boundaries.
- Contract test: compatibility test between producer and consumer.
- E2E test: full workflow test from user-facing boundary.
- Test pyramid: distribution strategy for different test granularities.

## Research Keywords

- `test pyramid unit integration contract e2e`
- `testing strategy spring boot frontend e2e`
- `contract testing api event schema`
- `test levels product use cases`
- `happy path sad path testing strategy`

## Conclusion

A good test pyramid is a decision framework. It helps the team put each product risk at the right test level so CI remains fast, failures remain diagnosable, and critical workflows remain protected.
