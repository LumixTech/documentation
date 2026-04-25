---
title: "Integration Event Payload Design: Minimal, Stable, and Meaningful Data"
description: How to design integration event payloads with metadata, idempotency, traceability, replay, and backward compatibility in mind.
sidebar_position: 6
---

## Introduction

An integration event should not grow around every consumer request. It should represent the stable business fact the producer intentionally exposes to the outside world.

The design question is not "What does the current consumer want?" The better question is: "What business truth do we want to publish as a durable contract?"

## Metadata Fields

Integration events usually need metadata for operational safety:

```json
{
  "eventId": "evt-123",
  "eventType": "OrderPlaced",
  "version": 1,
  "occurredAt": "2026-04-25T10:15:30Z",
  "traceId": "trace-abc",
  "source": "order-service"
}
```

These fields support:

- idempotency,
- tracing,
- replay,
- debugging,
- schema evolution,
- auditability.

## Business Payload

The payload should include the fields needed to understand the published business fact.

Example:

```json
{
  "orderId": "ord-456",
  "customerId": "cus-789",
  "orderLines": [
    {
      "productId": "prd-1",
      "quantity": 2,
      "unitPrice": "49.95"
    }
  ],
  "totalAmount": "99.90",
  "currency": "TRY"
}
```

This is not a full `OrderEntity`. It is a public message about an order being placed.

## Idempotency

Consumers should be able to process the same event more than once safely.

Common strategy:

```text
if eventId already_processed:
  skip
else:
  process_event
  mark eventId as processed
```

At-least-once delivery makes duplicate processing normal. Idempotency is not optional in serious asynchronous systems.

## Replay and Debugging

Events may be replayed after a consumer fix, a projection rebuild, or a DLQ recovery. Payloads should therefore contain enough stable context to be processed later.

This does not mean the payload should contain everything. It means it should contain the durable information needed to interpret the fact without relying on unstable internal state.

## Consumer-Specific Data

Avoid turning one event into a bag of fields for every consumer:

```text
OrderPlaced event:
  + notificationTemplateName
  + analyticsCampaignCode
  + warehousePickPriority
  + crmSegmentSnapshot
```

This creates unclear ownership and makes the producer responsible for downstream views. Prefer separate events, consumer-side lookups, read APIs, or dedicated projections when a consumer needs a specialized view.

## Versioning Rules

- Add optional fields when possible.
- Do not change the meaning of existing fields.
- Keep `eventType` and `version` explicit.
- Document required and optional fields.
- Support old and new consumers during migration.

## Technical Glossary

- Event Metadata
- Event ID
- Event Type
- Version
- Idempotency
- Replay
- Traceability
- Payload Design
- Consumer-specific View

## Our Notes / Key Emphasis

- It is correct to include the data integration consumers need.
- That does not mean the event should be shaped only around the current consumer's request.
- The payload should represent the stable business fact the producer wants to expose externally.

## Research Keywords

- `integration event payload best practices`
- `event metadata idempotency`
- `event versioning backward compatibility`
- `event payload minimal design`
- `event driven architecture payload bloat`

## Conclusion

An integration event should not be a bag message carrying everything every consumer might want. It should expose a stable business fact with enough metadata and payload to support reliable asynchronous processing.
