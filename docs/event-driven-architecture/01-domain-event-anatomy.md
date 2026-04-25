---
title: "Domain Event Anatomy: The System's Internal Truth"
description: How to model DDD domain events as meaningful internal business facts without leaking entities or external contracts.
sidebar_position: 2
---

## Introduction

Not every message in an event-driven architecture has the same meaning. A domain event represents something meaningful that happened inside the domain model.

The risky shortcut is thinking: "If something happened in the system, I publish an event." That usually produces noisy messages, unstable contracts, and accidental coupling.

## Core Concept

A domain event is phrased in the past tense because it records a completed business fact:

- `OrderPlaced`
- `PaymentAuthorized`
- `SeatReserved`
- `CustomerRegistered`

These names are not technical notifications. They describe facts that matter to the business language of the bounded context.

## Command vs Domain Event

A command asks for behavior:

```text
PlaceOrder
AuthorizePayment
ReserveSeat
```

A domain event states what already happened:

```text
OrderPlaced
PaymentAuthorized
SeatReserved
```

The distinction matters because commands can be rejected, but events should describe facts that are already true inside the model.

## Aggregate-Originated Events

In DDD, domain events usually originate inside an aggregate after the aggregate has protected its invariants.

```text
Order.place(customer_id, lines):
  validate_order_lines(lines)
  validate_customer_can_order(customer_id)

  status = PLACED
  record_event(OrderPlaced(order_id, customer_id, occurred_at))
```

The aggregate is responsible for deciding whether the business transition is valid. The event is a result of that valid transition, not a replacement for validation.

## Payload Shape

A domain event should not carry the whole entity. It should carry the minimum meaningful information needed to describe the business fact.

Poor shape:

```text
OrderPlaced(OrderEntity order)
```

Better shape:

```text
OrderPlaced(
  orderId,
  customerId,
  occurredAt
)
```

The event is not a disguised entity snapshot. It is a business statement.

## Sector Standard / Best Practice

- Name domain events in past tense.
- Emit them from meaningful aggregate state transitions.
- Keep payloads focused on business meaning.
- Avoid persistence objects, lazy relations, framework annotations, and internal state details.
- Treat domain events as internal model artifacts unless explicitly mapped to integration events.

## Common Mistake

Publishing domain events directly to Kafka turns internal domain decisions into external contracts. That makes future refactoring harder because external consumers start depending on private model details.

## Technical Glossary

- Domain Event
- Aggregate
- Command
- Business Invariant
- Domain Model
- Entity Leakage
- Internal Event
- OccurredAt

## Our Notes / Key Emphasis

- Sharing the whole entity inside an event is a design smell.
- A domain event should not be published directly to Kafka without an explicit external contract decision.
- Cross-service collaboration usually publishes integration events; distributing commands to services should be reserved for intentional orchestration.

## Research Keywords

- `domain event ddd`
- `domain event vs command`
- `aggregate domain event`
- `domain events best practices`
- `event driven architecture domain event`

## Conclusion

A domain event is a meaningful business fact that has occurred inside the domain. But that does not automatically make it the event contract that should be published to Kafka or consumed by external systems.
