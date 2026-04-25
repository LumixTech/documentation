---
title: Event-Driven Architecture Glossary
description: Shared terms for DDD-oriented event modeling, integration events, message contracts, and asynchronous communication.
sidebar_position: 1
---

## Introduction

This glossary defines the shared vocabulary used by the Event-Driven Architecture documentation set. The goal is to separate internal domain meaning from external messaging contracts.

## Glossary

### Domain Event

A meaningful business fact that has occurred inside a bounded context.

### Integration Event

A stable, backward-compatible event contract published to external systems or other bounded contexts.

### Command

A message that asks another component or system to perform a specific behavior.

### Event

A message that announces a business fact that already happened.

### Entity Leakage

Exposing a domain or persistence entity directly through an event payload.

### Semantic Coupling

A dependency where consumers become tied to the producer's internal meanings, state names, or lifecycle assumptions.

### Event Contract

The schema and semantic agreement that consumers rely on when consuming an event.

### Payload Bloat

The growth of an event payload caused by adding fields for every consumer-specific need.

### Idempotency

The ability to process the same event more than once while preserving the same final outcome.

### Backward Compatibility

The ability to evolve an event schema without breaking existing consumers.

## Research Keywords

- `domain event ddd`
- `integration event contract`
- `event contract versioning`
- `event driven architecture coupling`
- `command vs event`

## Conclusion

Clear terminology prevents accidental coupling. Domain events, integration events, and commands should be named and modeled as different tools.
