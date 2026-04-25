---
title: "PostgreSQL Backup, WAL, PITR, and Restore Drill"
description: Backup and restore runbook direction for PostgreSQL with WAL archiving, point-in-time recovery, 15 minute RPO, and 2 hour RTO.
sidebar_position: 6
---

## Introduction

Backups are not successful because they exist. They are successful only when they can be restored within the required recovery window.

For PostgreSQL, a production-grade backup strategy usually combines base backups, WAL archiving, point-in-time recovery, monitoring, and regular restore drills. This document outlines a technical runbook direction for a target of:

```text
RPO = 15 minutes
RTO = 2 hours
```

## Why It Matters

Data loss and long recovery time can be business-ending for core product data.

Without restore discipline:

- backups may be incomplete or corrupted,
- WAL archives may have gaps,
- recovery steps may be known by only one person,
- RPO/RTO promises may be imaginary,
- disaster recovery may fail during the exact moment it is needed.

The restore drill is the proof that the backup design works.

## Core Concepts

- Base backup: physical copy of the database cluster used as the recovery starting point.
- WAL: write-ahead log containing changes needed to replay database activity.
- WAL archiving: continuous shipping of WAL segments to durable storage.
- PITR: point-in-time recovery by restoring a base backup and replaying WAL until a target time.
- RPO: maximum acceptable data loss window.
- RTO: maximum acceptable recovery duration.
- Restore drill: scheduled practice recovery used to validate backups, documentation, and timing.
- Timeline: PostgreSQL recovery branch created after PITR.

## Problem in Real Product Development

Many teams say "we have backups" but cannot answer:

- When was the last restore tested?
- How many minutes of data can we lose?
- How long does recovery actually take?
- Are WAL files archived and monitored?
- Can we restore to an isolated environment without corrupting production?
- Who approves the target recovery time?

The operational problem is not only taking backups. It is designing a repeatable recovery workflow.

## Approach / Design

### Backup Components

| Component | Purpose | Target |
| --- | --- | --- |
| Full/base backup | Recovery starting point | At least daily, plus before risky changes |
| WAL archive | Low RPO change capture | Continuous, monitored |
| Backup manifest/checksum | Integrity validation | Every backup |
| Restore environment | Safe validation target | Isolated network/account |
| Runbook | Human execution path | Versioned and rehearsed |

### 15 Minute RPO Direction

To support 15 minute RPO:

- archive WAL continuously,
- alert when WAL archiving fails or lags,
- monitor last archived WAL timestamp,
- keep enough WAL retention to cover restore windows,
- ensure object storage or backup storage is durable and access-controlled.

The RPO is breached if WAL archive lag exceeds 15 minutes.

### 2 Hour RTO Direction

To support 2 hour RTO:

- keep restore infrastructure pre-defined through IaC,
- know where the latest valid base backup is,
- automate restore commands where possible,
- test restore duration with realistic data volume,
- document DNS/application cutover steps separately from database restore.

The RTO is breached if the team cannot bring an application-compatible database online within 2 hours.

## Sector Standard / Best Practice

- Use physical base backups plus WAL archiving for PITR.
- Encrypt backups and restrict restore permissions.
- Store backups outside the primary database host.
- Monitor both backup success and WAL archive freshness.
- Test restores regularly in an isolated environment.
- Keep restore runbooks short, executable, and versioned.
- Record restore drill results as engineering evidence.
- Define ownership for backup, restore, approval, and communication.

## Pseudocode / Decision Flow / Lifecycle / Policy Example

### Restore Drill Runbook Draft

```text
restore_drill(target_time):
  announce_drill_window()
  select_latest_base_backup_before(target_time)
  provision_isolated_restore_instance()
  download_base_backup()
  verify_backup_manifest_and_checksums()

  configure_restore_command_for_wal_archive()
  set_recovery_target_time(target_time)
  start_postgresql_recovery()

  wait_until_recovery_completes()
  run_integrity_checks()
  run_application_smoke_tests()
  record_actual_rpo_rto()
  destroy_or quarantine_restore_environment()
```

### Incident Restore Decision Flow

```text
if data_corruption_detected:
  freeze_writes_if_required()
  identify_safe_recovery_target_time()
  get incident commander approval
  restore to isolated environment
  validate business-critical data
  cut over only after approval
```

## Our Notes / Team Decisions

- Backup design must include restore proof. Taking backups without drills is not enough.
- Target planning should assume 15 minute RPO and 2 hour RTO.
- Restore drills should be scheduled and documented as an operational requirement.
- Backup and restore events should be connected to operational audit evidence.
- PITR planning should be considered together with [Flyway Migrations and Zero-Downtime Database Changes](./flyway-zero-downtime-migration-strategy).

## External References

- PostgreSQL: [Continuous Archiving and Point-in-Time Recovery](https://www.postgresql.org/docs/current/continuous-archiving.html)
- PostgreSQL: [pg_basebackup](https://www.postgresql.org/docs/current/app-pgbasebackup.html)

## Glossary

- WAL: write-ahead log used by PostgreSQL to record changes.
- PITR: restore process to a specific point in time.
- RPO: maximum acceptable data loss.
- RTO: maximum acceptable recovery duration.
- Base backup: physical backup used as a recovery base.
- Restore drill: controlled practice recovery.

## Research Keywords

- `postgresql wal archiving pitr restore drill`
- `backup strategy rpo rto postgresql`
- `pg_basebackup restore runbook`
- `postgresql recovery_target_time`
- `wal archive monitoring`

## Conclusion

A PostgreSQL backup strategy is only credible when restore is practiced. Base backups, WAL archiving, PITR, monitoring, and restore drills together make RPO/RTO targets measurable instead of aspirational.
