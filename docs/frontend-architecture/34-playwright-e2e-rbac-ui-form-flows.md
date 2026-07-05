---
title: "Playwright E2E, RBAC UI Tests, and Form Flows"
description: Browser-level test scenarios for admin, teacher, and parent workflows with RBAC, forms, happy paths, and sad paths.
sidebar_position: 4
---

## Introduction

Playwright E2E tests validate critical user workflows in a real browser environment. They are especially valuable for role-based UI, navigation, forms, authentication, and cross-screen behavior.

E2E tests should not cover every rule. They should protect the workflows where user trust, permissions, and product value intersect.

## Why It Matters

Frontend confidence is not only component rendering.

Critical failures often appear when layers meet:

- a role can see a forbidden button,
- a form submits invalid data,
- auth refresh redirects at the wrong time,
- a teacher flow depends on admin-only data,
- a parent can access another tenant's route,
- validation errors are invisible or misleading.

Playwright helps verify these flows from the user's perspective.

## Core Concepts

- E2E test: browser test that exercises a full workflow.
- RBAC UI test: verifies role-based visibility and access behavior.
- Happy path: expected successful user flow.
- Sad path: failure or validation scenario.
- Fixture user: stable test account with known role and permissions.
- Page object: test abstraction for repeated UI interactions.
- Test isolation: each test starts from predictable state.

## Problem in Real Product Development

The temptation is to test everything through the browser. That makes CI slow and brittle.

The better approach:

- use unit/integration tests for rules,
- use contract tests for API shape,
- use Playwright for critical user journeys and role-visible behavior.

This aligns with [Test Pyramid: Unit, Integration, Contract, and E2E Strategy](../observability-qa/test-pyramid-unit-integration-contract-e2e).

## Approach / Design

### Scenario 1: Admin Permission Management

Goal:

```text
Admin can update a user's role/permission, and forbidden users cannot access the same controls.
```

Happy path:

```text
1. Login as admin.
2. Open user management.
3. Search target user.
4. Change role or permission.
5. Save.
6. See success state.
7. Reload and verify persisted value.
```

Sad path:

```text
1. Login as teacher or parent.
2. Attempt direct navigation to admin user management route.
3. Verify access denied or redirect.
4. Verify admin-only controls are not visible.
```

### Scenario 2: Teacher Attendance Flow

Goal:

```text
Teacher can mark attendance for assigned classroom and validation prevents incomplete invalid submit.
```

Happy path:

```text
1. Login as teacher.
2. Open attendance screen.
3. Select assigned classroom/date.
4. Mark students.
5. Submit attendance.
6. Verify submitted state and summary.
```

Sad path:

```text
1. Attempt to submit without required selections.
2. Verify validation message.
3. Attempt to access another teacher's classroom if route is known.
4. Verify forbidden or not found behavior.
```

### Scenario 3: Parent Message/Form Flow

Goal:

```text
Parent can view allowed messages and submit a valid form, while tenant/role boundaries remain protected.
```

Happy path:

```text
1. Login as parent.
2. Open messages.
3. Select allowed conversation.
4. Send message or submit required form.
5. Verify message/form appears in allowed context.
```

Sad path:

```text
1. Try direct URL for another conversation.
2. Verify access denied or safe empty state.
3. Submit invalid form.
4. Verify validation errors and no success toast.
```

## Sector Standard / Best Practice

- Use stable selectors based on role/name or test IDs where necessary.
- Seed test data deterministically.
- Keep E2E tests small and focused on critical workflows.
- Test both role visibility and direct-route authorization behavior.
- Avoid relying on test order.
- Run tests in CI with trace/video/screenshot on failure.
- Keep API setup shortcuts separate from user-facing assertions.

## Pseudocode / Decision Flow / Lifecycle / Policy Example

```text
e2e_role_flow(role, scenario):
  seed_user(role)
  login_as(role)
  navigate_to_scenario_page()
  assert_visible_controls_match_role(role)
  perform_happy_path()
  assert_persisted_result()
  perform_sad_path()
  assert_safe_failure()
```

Example Playwright skeleton:

```typescript
test('teacher marks attendance', async ({ page }) => {
  await loginAs(page, 'teacher');
  await page.goto('/attendance');

  await page.getByRole('combobox', { name: /classroom/i }).selectOption('class-a');
  await page.getByRole('checkbox', { name: /present/i }).first().check();
  await page.getByRole('button', { name: /submit/i }).click();

  await expect(page.getByText(/attendance submitted/i)).toBeVisible();
});
```

## Our Notes / Team Decisions

- Admin, teacher, and parent each need one critical E2E scenario.
- RBAC UI tests must verify both hidden controls and direct route protection.
- Form flows should include happy path and sad path.
- E2E scope should remain focused and supported by lower-level tests from the test pyramid.

## External References

- Playwright: [Documentation](https://playwright.dev/)

## Glossary

- E2E test: browser-level workflow test.
- RBAC UI test: role-based user interface verification.
- Fixture user: prepared test user with stable role and permissions.
- Happy path: expected successful flow.
- Sad path: expected failure or validation flow.
- Page object: reusable test abstraction around a page or workflow.

## Research Keywords

- `playwright e2e rbac ui tests forms auth flow`
- `sad path happy path playwright`
- `role based UI testing playwright`
- `playwright test data seeding`
- `playwright trace on failure ci`

## Conclusion

Playwright should protect the product's critical browser workflows, not replace the full test pyramid. Admin, teacher, and parent E2E scenarios give role-level confidence while lower layers keep rule coverage fast and precise.
