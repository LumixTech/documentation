---
title: Shared Terminology Glossary
description: Standardized terminology convention for security, database, frontend, and compliance documentation.
sidebar_position: 1
---

## Introduction

This glossary defines shared terminology used across the documentation portal. The goal is consistency: the same concept should be named the same way in every page.

## Why It Matters

Terminology drift causes technical misunderstanding, policy misconfiguration, and onboarding friction. A single vocabulary improves communication across engineering, product, and compliance.

## Core Concepts

- Standardized naming avoids ambiguous interpretation.
- Domain terms should map to architecture artifacts (tables, policies, services, workflows).
- Each term should be stable enough for long-term documentation reuse.

## Problem in Real Product Development

Teams often alternate between similar terms (`role permission`, `role-permission`, `role_permission`) and accidentally create semantic confusion in implementation and reviews.

## Approach / Design

Use the following terms consistently:

- `access token`
- `refresh token`
- `session`
- `device session`
- `role_permission`
- `user_permission`
- `common_permission`
- `allow`
- `deny`
- `tenant_id`
- `RLS policy`
- `audit log`
- `retention policy`
- `anonymization`
- `DSAR workflow`
- `smart navigation`
- `read replica`
- `write primary`

## Sector Standard / Best Practice

- Keep domain vocabulary close to implementation language.
- Avoid multiple synonyms for policy-critical terms.
- Update glossary first when introducing new architecture terminology.

## Pseudocode / Decision Flow / Lifecycle / Policy Example

```text
on NEW_TERM_PROPOSAL(term):
  if term duplicates existing concept:
    reuse_existing_term
  else:
    add_term_to_glossary_with_definition
    announce_change_to_team
```

## Our Notes / Team Decisions

- The terminology list above is the canonical reference for this project.
- If a new term is introduced, it should be added here before wide usage.
- Topic pages should prefer linking to this glossary when wording could be interpreted differently.

## Glossary

- `access token`: short-lived JWT for API request authentication, validated against Redis token/session state.
- `refresh token`: token used to issue new access tokens through a Redis-backed controlled lifecycle.
- `session`: persistent Redis-backed server-side authentication context.
- `device session`: per-device/session tracking unit.
- `role_permission`: permission inherited from a role.
- `user_permission`: permission granted directly to a user.
- `common_permission`: baseline/shared permission definition.
- `allow`: positive authorization decision.
- `deny`: negative authorization decision that blocks access.
- `tenant_id`: tenant boundary identifier.
- `RLS policy`: row-level security rule at database layer.
- `audit log`: immutable or tamper-evident action history.
- `retention policy`: rule defining how long data is stored.
- `anonymization`: irreversible de-identification process.
- `DSAR workflow`: lifecycle for subject data rights requests.
- `smart navigation`: centralized route decision logic.
- `read replica`: read-oriented replicated database node.
- `write primary`: authoritative write database node.

## Research Keywords

- `engineering terminology governance`
- `domain language consistency architecture`
- `ubiquitous language in software teams`
- `documentation taxonomy best practices`
- `shared glossary for distributed teams`

## Conclusion

A shared glossary is a lightweight but high-leverage architecture control. Consistent terminology reduces mistakes, improves onboarding, and keeps documentation maintainable as the product grows.
