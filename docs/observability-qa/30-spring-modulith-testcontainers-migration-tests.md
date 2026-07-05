---
title: "Spring Modulith Tests, Testcontainers, and Migration Tests"
description: Testing module boundaries, PostgreSQL integration, and Flyway migration behavior with Spring Modulith and Testcontainers.
sidebar_position: 5
---

## Introduction

Module boundaries and database migrations are long-term maintainability controls. They are also easy to break accidentally unless they are tested automatically.

Spring Modulith can verify application module structure and run module-scoped integration tests. Testcontainers can provide real PostgreSQL infrastructure for integration and migration tests.

## Why It Matters

In modular systems, regressions are often architectural:

- a module starts depending on another module's internals,
- an event listener stops receiving the expected event,
- a migration works on an empty database but fails on existing data,
- repository code works with H2 but fails with PostgreSQL,
- Flyway ordering issues appear only in CI or production.

Automated module and migration tests catch these failures early.

## Core Concepts

- Spring Modulith: Spring project for structuring and verifying modular monolith applications.
- `@ApplicationModuleTest`: module-scoped integration test support.
- Application module: domain-oriented module boundary inside the application.
- Testcontainers: library for starting disposable real dependencies in tests.
- PostgreSQL container: real PostgreSQL instance used in integration tests.
- Flyway migration test: test that applies migrations against a real database.
- Boundary verification: test that module dependencies follow allowed rules.

## Problem in Real Product Development

Traditional integration tests often start the whole application for every scenario. That gives broad confidence but can hide module coupling and slow feedback.

The better approach is layered:

- verify module boundaries structurally,
- test module behavior in isolation where possible,
- use real PostgreSQL for persistence behavior,
- run migrations against realistic schema/data states.

## Approach / Design

### Module Test Skeleton: Attendance Module

```java
@ApplicationModuleTest
class AttendanceModuleTests {

  @Test
  void marksAttendanceAndPublishesDomainEvent(Scenario scenario) {
    scenario.stimulate(() -> attendanceUseCase.markAttendance(command()))
      .andWaitForEventOfType(AttendanceMarkedEvent.class)
      .matching(event -> event.tenantId().equals(command().tenantId()))
      .toArriveAndVerify(event -> assertThat(event.classroomId()).isNotNull());
  }
}
```

Test intent:

- module boots with its allowed dependencies,
- use case is callable through the module API,
- expected domain event is published,
- module internals remain encapsulated.

### Module Test Skeleton: Messaging Module

```java
@ApplicationModuleTest
class MessagingModuleTests {

  @MockitoBean
  NotificationPort notificationPort;

  @Test
  void sendsMessageWithinConversationBoundary(Scenario scenario) {
    scenario.stimulate(() -> messagingUseCase.sendMessage(command()))
      .andWaitForStateChange(() -> messageRepository.findById(command().messageId()))
      .andVerify(message -> assertThat(message).isPresent());
  }
}
```

Test intent:

- cross-module dependency is mocked where appropriate,
- persistence state change is observable,
- module contract is tested without booting unrelated modules.

### PostgreSQL Testcontainers Skeleton

```java
@Testcontainers
@SpringBootTest
class PostgreSqlIntegrationTests {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("app")
          .withUsername("test")
          .withPassword("test");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }
}
```

### Flyway Migration Test Direction

```text
1. Start PostgreSQL container.
2. Apply all Flyway migrations from empty database.
3. Insert representative tenant/user/domain data.
4. Run repository smoke tests.
5. For risky migrations, restore previous schema fixture.
6. Apply new migration.
7. Validate backfill, constraints, indexes, and rollback assumptions.
```

## Sector Standard / Best Practice

- Test module boundaries as architecture, not only behavior.
- Prefer real PostgreSQL for SQL, indexes, constraints, and migration tests.
- Avoid H2 as a substitute for PostgreSQL-specific behavior.
- Keep module tests focused on exposed use cases and events.
- Use Testcontainers in CI with stable image versions.
- Test migrations with representative data, not only empty schemas.
- Connect migration tests to zero-downtime rules.

## Pseudocode / Decision Flow / Lifecycle / Policy Example

```text
module_test_plan(module):
  verify_module_dependencies(module)
  boot_module_with_allowed_dependencies()
  stimulate_public_use_case()
  assert_state_change_or_domain_event()
  fail_if_internal_boundary_is_crossed()
```

Migration test flow:

```text
test_migration(version):
  start_postgres_container()
  apply_migrations_until(version - 1)
  seed_representative_data()
  apply_migration(version)
  assert_schema_contract()
  assert_data_parity()
```

## Our Notes / Team Decisions

- At least two modules should receive Modulith test skeletons first: attendance and messaging.
- PostgreSQL Testcontainers should be used for persistence and migration tests.
- Migration tests should support the safety expectations from [Flyway Migrations and Zero-Downtime Database Changes](../database-architecture/flyway-zero-downtime-migration-strategy).
- Module tests should support architectural boundaries described in [Modular Monolith and Spring Modulith](../engineering-notes/modular-monolith-and-spring-modulith).

## External References

- Spring Modulith: [Integration Testing Application Modules](https://docs.spring.io/spring-modulith/reference/testing.html)
- Testcontainers for Java: [Postgres Module](https://java.testcontainers.org/modules/databases/postgres/)

## Glossary

- Application module: domain boundary inside a modular application.
- `@ApplicationModuleTest`: Spring Modulith annotation for module-scoped tests.
- Testcontainers: test library for disposable containerized dependencies.
- Migration test: test that validates schema change behavior.
- Boundary verification: check that modules depend only on allowed APIs.

## Research Keywords

- `spring modulith testing testcontainers`
- `flyway migration tests integration tests`
- `ApplicationModuleTest Scenario API`
- `PostgreSQLContainer spring boot test`
- `modular monolith boundary verification`

## Conclusion

Module and migration tests protect the architecture from silent drift. Spring Modulith validates boundaries and module behavior, while Testcontainers makes database tests realistic enough to catch production-relevant failures.
