---
title: "Command vs Event Communication: Giving Orders or Announcing Facts"
description: How command and event messages affect service coupling, orchestration, choreography, and asynchronous autonomy.
sidebar_position: 5
---

## Introduction

The command/event distinction is one of the most important modeling decisions in event-driven systems. The wrong choice can quietly increase service coupling.

A command says: "Do this."

An event says: "This happened."

## Command Communication

A command asks a target system to perform behavior:

```text
Order Service -> Billing Service:
  CapturePayment(orderId)
```

This can be valid in orchestration flows where one component intentionally coordinates a process. But it also means the sender knows who should act and what action should be performed.

That creates stronger coupling.

## Event Communication

An event announces a completed business fact:

```text
Order Service publishes:
  OrderPlacedIntegrationEvent
```

Billing, Notification, Analytics, Warehouse, and CRM services can each decide whether and how to react.

```text
OrderPlacedIntegrationEvent
  -> Billing decides whether to start payment flow
  -> Notification decides whether to notify customer
  -> Analytics updates reporting
  -> Warehouse prepares fulfillment
```

The producer does not need to know all consumers.

## Orchestration vs Choreography

Orchestration:

- one coordinator drives the process,
- commands are often explicit,
- flow is easier to inspect centrally,
- coupling to participant behavior is higher.

Choreography:

- services react to events,
- no single service controls every step,
- autonomy is higher,
- flow can become harder to trace without good observability.

Both patterns are useful. The mistake is using commands when the business meaning is simply "this happened."

## OrderPlaced Example

Poor fit for a broad business event:

```text
Order Service sends commands:
  CreateInvoice
  SendOrderEmail
  UpdateAnalytics
  ReserveWarehouseStock
```

Better fit for event-driven collaboration:

```text
Order Service publishes:
  OrderPlacedIntegrationEvent
```

Consumers then own their own reactions.

## When Commands Make Sense

Commands can be appropriate when:

- there is a clear target owner,
- the sender intentionally requests a behavior,
- the workflow is orchestrated,
- success/failure must be coordinated by a process manager or saga,
- the receiver has an explicit command API.

## When Events Make Sense

Events are usually better when:

- the producer only knows that a business fact occurred,
- multiple consumers may react independently,
- consumers should remain autonomous,
- the producer should not know downstream workflows,
- the system prefers choreography over central control.

## Technical Glossary

- Command
- Event
- Orchestration
- Choreography
- Producer
- Consumer
- Service Coupling
- Autonomy
- Asynchronous Communication

## Our Notes / Key Emphasis

- "Produce commands and distribute them to services" should be handled carefully.
- That approach can be valid in some orchestration scenarios.
- For broad business facts such as `OrderPlaced`, the better default is usually publishing an integration event.

## Research Keywords

- `command vs event`
- `event choreography vs orchestration`
- `service coupling event driven architecture`
- `asynchronous communication patterns`
- `spring cloud stream kafka event command`

## Conclusion

Use commands when you intentionally ask another system to do something and accept the coupling that comes with that choice. Use integration events when you want to announce a business fact and let other systems react autonomously.
