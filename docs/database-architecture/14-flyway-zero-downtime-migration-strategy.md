---
title: Flyway Migrations and Zero-Downtime Database Changes
description: Production-safe database change strategy with Flyway, backward-compatible schema design, and expand/contract rollout.
sidebar_position: 5
---

## Introduction

Database migrations are one of the highest-risk production changes. A schema update that is technically correct can still break live traffic if it is not rollout-safe.

This document defines a Flyway-centered, zero-downtime approach for backward-compatible database evolution.

## Why It Matters

If schema changes are deployed without compatibility planning:

- old application instances may fail against new schema,
- long locks can block critical write paths,
- rollback may become impossible without data loss,
- production incidents can impact all tenants at once.

Zero-downtime migration design reduces this risk by separating schema evolution from application cutover.

## Core Concepts

- Flyway migration: versioned and ordered schema changes (`V...__description.sql`).
- Backward-compatible schema change: change that works with both old and new app versions during rollout.
- Expand and contract: staged pattern where we first add compatible structures, then remove legacy structures later.
- Dual-read/dual-write: temporary app behavior that keeps old and new schema paths consistent.
- Backfill: controlled batch process that migrates historical data.
- Contract phase: cleanup step (drop old columns/tables) only after traffic fully moves.

## Problem in Real Product Development

Real deployments are rarely atomic. During rolling deploys, some pods run old code while others run new code. If migration assumes single-version runtime, actions like column rename or table split can break reads/writes mid-release.

## Approach / Design

### Migration Governance with Flyway

- Use one migration unit per irreversible schema step.
- Keep migrations idempotent where possible (`IF NOT EXISTS`, guarded updates).
- Review lock impact before production (`ALTER TABLE` type, index build strategy, table rewrite risk).
- Use timestamp-based versioning if multiple teams release in parallel.

Example naming:

- `V2026041801__expand_add_student_display_name.sql`
- `V2026041802__expand_create_student_profile_table.sql`
- `V2026041803__contract_drop_legacy_student_columns.sql`

### Expand and Contract Playbook

1. Expand
- Add new nullable columns/tables/indexes.
- Keep old paths intact.
- Deploy app with dual-read/dual-write.

2. Migrate
- Backfill historical records in batches.
- Monitor mismatch metrics.

3. Switch
- Move reads to new schema.
- Keep fallback only for controlled window.

4. Contract
- Remove old columns/tables only after verification.

### Two-Stage Scenario 1: Column Rename (Production-Safe)

Target: rename `students.full_name` to `students.display_name`.

Stage 1 (`expand + compatibility`)

```sql
-- V2026041801__expand_add_display_name.sql
ALTER TABLE students ADD COLUMN IF NOT EXISTS display_name text;

-- Backfill in batches from application job or controlled SQL window
UPDATE students
SET display_name = full_name
WHERE display_name IS NULL
  AND full_name IS NOT NULL;
```

Application behavior in Stage 1:

- write both `full_name` and `display_name`,
- read `COALESCE(display_name, full_name)`.

Stage 2 (`contract`)

```sql
-- V2026041804__contract_drop_full_name.sql
ALTER TABLE students DROP COLUMN IF EXISTS full_name;
```

Precondition for Stage 2:

- all application instances read/write only `display_name`,
- mismatch metrics are zero for agreed observation window.

### Two-Stage Scenario 2: Table Split (Production-Safe)

Target: split `students` table and move profile fields to `student_profiles`.

Stage 1 (`expand + dual-write + backfill`)

```sql
-- V2026041802__expand_create_student_profiles.sql
CREATE TABLE IF NOT EXISTS student_profiles (
  student_id uuid PRIMARY KEY REFERENCES students(id),
  bio text,
  avatar_url text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
```

Application behavior in Stage 1:

- keep reading from old columns with fallback to new table,
- dual-write old columns + `student_profiles`,
- backfill existing rows in batches.

Stage 2 (`contract`)

```sql
-- V2026041805__contract_drop_profile_columns_from_students.sql
ALTER TABLE students
  DROP COLUMN IF EXISTS bio,
  DROP COLUMN IF EXISTS avatar_url;
```

Precondition for Stage 2:

- all reads served from `student_profiles`,
- backfill completeness and data parity validated.

## Sector Standard / Best Practice

- Treat zero-downtime as release architecture, not only SQL syntax quality.
- Never combine expand and contract into the same production release.
- Prefer additive changes first (`ADD COLUMN`, `ADD TABLE`) over destructive changes.
- Use batched backfill jobs and throttle writes on large tables.
- For PostgreSQL, use low-lock strategies where possible (`CREATE INDEX CONCURRENTLY`, deferred validation patterns).

## Pseudocode / Decision Flow / Lifecycle / Policy Example

```text
deploy_schema_change(change_request):
  if not backward_compatible(change_request.expand_sql):
    reject

  apply_flyway_expand_migrations()
  deploy_app_dual_read_write()

  run_backfill_in_batches()
  if data_parity_not_ok():
    stop_and_investigate

  switch_reads_to_new_schema()
  observe_metrics(window='agreed_period')

  if stable_and_verified():
    apply_flyway_contract_migrations()
  else:
    keep_legacy_path_until_stable
```

## Our Notes / Team Decisions

- Flyway migrations are mandatory for schema version control in shared environments.
- Zero-downtime DB changes must follow backward-compatible expand/contract pattern.
- Column rename and table split must be planned as multi-release flows, not one-shot SQL edits.
- Contract steps are blocked until parity checks and monitoring gates pass.

## Glossary

- Flyway: migration tool that applies ordered schema versions.
- Zero-downtime migration: schema rollout that preserves service availability during deployment.
- Backward-compatible schema change: schema state compatible with old and new app versions.
- Expand and contract: phased migration pattern (add first, remove later).
- Backfill: process of migrating existing data to new schema shape.
- Dual-write: temporary writes to both old and new schema paths.

## Research Keywords

- `flyway zero downtime migration strategy`
- `expand contract database migration pattern`
- `backward compatible schema changes`
- `postgresql column rename without downtime`
- `table split migration dual write backfill`

## Conclusion

Safe production schema evolution requires both process and design discipline. Flyway gives versioned control, and expand/contract gives rollout safety. Together, they make high-impact DB changes predictable and reversible.
