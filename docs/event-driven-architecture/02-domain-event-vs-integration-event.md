---
title: Domain Event vs Integration Event
description: Why internal domain events and external integration events should be modeled as separate artifacts in event-driven systems.
sidebar_position: 3
---

## Introduction

Domain events and integration events are often treated as the same thing. That shortcut creates coupling because the internal model and the public messaging contract start changing together.

The safer design is to separate internal domain truth from external communication.

## Core Difference

Domain event:

- belongs inside a bounded context,
- reflects internal business meaning,
- can change when the domain model changes,
- is usually produced by an aggregate or domain service.

Integration event:

- is published to other systems,
- acts as a stable public contract,
- evolves with backward compatibility,
- is designed for external consumers.

## Kafka Event as Public API

An event published to Kafka should be treated like a public API. Consumers build code, storage, metrics, alerts, and workflows around it.

Because of that, changing an integration event is not the same as renaming a field in an internal object. It may break downstream systems.

## Mapping Boundary

A common pattern is to map domain events to integration events inside the application or outbox layer.

```text
Order aggregate
  -> OrderPlaced domain event
  -> application/outbox mapper
  -> OrderPlacedIntegrationEvent
  -> Kafka
```

This mapping gives the team freedom to refactor the internal model while keeping the external contract stable.

## Versioning and Compatibility

Integration events should evolve carefully:

- add optional fields instead of renaming existing fields,
- avoid changing field meaning,
- use explicit `version` metadata,
- keep old consumers working during migration,
- document schema and semantic changes.

## Anti-Pattern

```text
aggregate emits OrderPlaced
  -> directly serialize domain event
  -> publish to Kafka
  -> consumers depend on internal fields
```

This creates an accidental public contract around internal state.

## Technical Glossary

- Integration Event
- Public Contract
- Bounded Context
- Backward Compatibility
- Event Contract
- External Consumer
- Internal Model
- Event Versioning

## Our Notes / Key Emphasis

- The external message should usually be an integration event, not a raw domain event.
- Events that leave the bounded context need a separate contract design.
- Publishing a domain event directly to Kafka leaks the internal model to external consumers.

## Research Keywords

- `domain event vs integration event`
- `integration event contract`
- `bounded context events`
- `event contract versioning`
- `internal events external events`

## Conclusion

Every integration event is an event. But not every domain event should become an integration event. Internal model events and external messaging contracts deserve separate design decisions.
