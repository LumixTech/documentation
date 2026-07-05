---
title: Frontend URL Path Strategy for Visible and Invisible IDs
description: Config-driven URL behavior using root Redux state and smart navigation rules.
sidebar_position: 1
---

## Introduction

URL design affects security posture, user experience, shareability, and long-term maintainability. Whether IDs are visible in paths should be a system-level decision, not scattered component logic.

## Why It Matters

Hard-coded route behavior creates inconsistency:

- links from different pages encode different URL styles,
- feature toggles become difficult,
- migration between visible and invisible IDs becomes risky.

A centralized strategy allows controlled evolution.

## Core Concepts

- Visible ID route: URL includes entity identifiers.
- Invisible ID route: URL hides internal identifiers or resolves via alternate keys.
- Root state policy: routing behavior controlled from Redux/root reducer/base state.
- `smart navigation`: shared navigation utility/component applying route policy.
- Canonical route generation: one source of truth for URL building.

## Problem in Real Product Development

As products scale, route calls happen across many screens. If each screen manually chooses path style, behavior diverges and deep-link compatibility breaks.

## Approach / Design

### Policy Placement

Store URL visibility policy centrally, for example:

- `routing.idVisibilityMode = 'visible' | 'hidden'`
- environment or tenant-based overrides
- per-domain exceptions where justified

### `smart navigation` Responsibilities

- read current route policy from state selectors,
- generate canonical destination path,
- preserve query params and history rules,
- support backward-compatible redirects when policy changes.

### Internal Linking Consideration

Backend and data policies should remain consistent with URL strategy. See [Tenant-based Row-Level Security (RLS)](../database-architecture/tenant-based-rls-policy-design) for data isolation boundaries.

## Sector Standard / Best Practice

- Use selectors to isolate components from raw state shape.
- Keep route generation in one utility/module.
- Prefer config-driven behavior over one-off conditionals.
- Track route policy transitions with analytics and `audit log` where required.

## Pseudocode / Decision Flow / Lifecycle / Policy Example

```text
smart_navigation_go(entity_type, entity):
  mode = select_id_visibility_mode(root_state)

  if mode == 'visible':
    path = '/'+entity_type+'/'+entity.id
  else:
    path = '/'+entity_type+'/'+entity.public_key

  navigate(path)
```

## Our Notes / Team Decisions

- ID visibility in URL paths will be controlled by system parameters.
- Policy will be sourced from Redux/root reducer/base state.
- In-page routes and navigations should respect this state consistently.
- A `smart navigation` component/module is the preferred architectural path.

## Glossary

- `smart navigation`: centralized route decision and navigation handler.
- Canonical route: preferred URL representation for a resource.
- Root state: global Redux state entry point.
- Deep link: URL pointing directly to an internal app state.

## Research Keywords

- `redux driven route policy`
- `config based url generation react`
- `feature flag routing strategy`
- `canonical url strategy single page app`
- `smart navigation component design`

## Conclusion

URL path behavior should be an architectural policy, not a local UI choice. Centralizing this decision in state-driven `smart navigation` gives predictable behavior, easier migrations, and cleaner long-term frontend design.

