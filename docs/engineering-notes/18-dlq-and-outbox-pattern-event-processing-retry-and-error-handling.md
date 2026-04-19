---
title: "DLQ and Outbox Pattern: Event Processing, Retry, and Error Handling"
description: "End-to-end architecture guidance for outbox relay, retry strategy, DLQ behavior, replay, and idempotent consumers."
sidebar_position: 5
---

## Introduction

In distributed systems, data flow does not complete in a single transaction. Because of that, these failures are expected:

- data is saved in DB but event publish fails,
- event is published but consumer cannot process it,
- consumer processes event but crashes before finishing side effects,
- same event is processed more than once.

Without proper design, this leads to silent data loss, duplicate operations, and state divergence across systems.

## Core Concepts

- Outbox Pattern: store business data and integration event in the same DB transaction.
- Relay (Publisher): background process that reads outbox records and publishes them to a broker.
- Retry: controlled re-attempts for temporary failures.
- Dead Letter Queue (DLQ): queue/topic for messages that cannot be processed successfully.
- Replay: sending DLQ messages back to the main flow after fix.
- Idempotency: processing the same event multiple times without changing final outcome.

## Problem Scenario

```text
Order created
-> Written to DB
-> Event should be published
-> Network failure
```

Result:

- order exists in DB,
- event is missing on the broker,
- read model is not updated,
- system becomes inconsistent.

## Approach / Design

### 1) Outbox Pattern

```text
BEGIN TX
  INSERT INTO orders
  INSERT INTO outbox_events
COMMIT
```

Benefit:

- business write and event write are atomic,
- event loss is prevented.

### 2) Outbox Relay

```text
outbox_events
   ↓
relay process
   ↓
Kafka publish
   ↓
mark as published
```

### 3) Consumer Flow

```text
Kafka topic
   ↓
Consumer
   ↓
Read DB update
```

## Industry Standard End-to-End Flow

```text
1. Command arrives
2. orders + outbox saved in same transaction
3. COMMIT

4. Relay reads event from outbox
   CASE:
   - Publish fails -> retry
   - Publish succeeds -> continue

5. Event reaches Kafka

6. Consumer receives event
   CASE:
   - Success -> commit
   - Temporary failure -> retry
   - Persistent failure -> DLQ

7. DLQ flow:
   - alert
   - investigation
   - fix
   - replay
```

## DLQ Detailed Behavior

### When does a message go to DLQ?

Only when:

> Consumer receives the message, processing fails, and retries are exhausted.

### Cases that do NOT go to DLQ

- consumer is down and not polling,
- broker/network is temporarily unavailable,
- message is still waiting in topic backlog.

### DLQ pipeline

```text
consume
   ↓
error
   ↓
retry (n times)
   ↓
fail
   ↓
DLQ
```

### What DLQ does NOT do

- does not rollback upstream business transaction,
- does not auto-cancel business operation,
- does not produce `event_cancelled` by itself.

DLQ only says: "This message could not be processed."

## Retry and Poison Message

Retry is for temporary failures:

- DB timeout,
- network glitch,
- lock contention.

Poison message means retry will keep failing:

- schema mismatch,
- missing required field,
- invalid data contract.

Poison messages should be routed to DLQ.

## Replay Mechanism

```text
DLQ
  ↓
manual / tool replay
  ↓
consumer processes again
```

Replay risk:

- same event may be processed again,
- duplicates may happen.

Therefore, idempotency is mandatory.

## Idempotency Strategies

### Event ID check

```text
if eventId already processed:
   skip
```

### Business key check

```text
if orderId already exists:
   do not recreate
```

### Upsert strategy

```sql
INSERT ... ON CONFLICT DO NOTHING
```

## Technical vs Business Failure

Technical failure examples:

- exception,
- timeout,
- parsing error.

Action: retry + DLQ.

Business failure examples:

- out of stock,
- payment rejected.

Action: compensation event, for example:

```text
OrderCancelled
PaymentReversed
```

## Critical Trade-offs

| Problem | Solution |
| --- | --- |
| Event loss | Outbox |
| Temporary failure | Retry |
| Poison message | DLQ |
| Duplicate processing | Idempotency |
| State mismatch | Replay |

## Conclusion

Outbox + DLQ gives three important guarantees:

- events are not silently lost,
- main flow is not blocked forever,
- failures are isolated and reprocessable.

At the same time, the operating model becomes:

> eventual consistency + retries + idempotent consumers.

## Glossary

- Outbox Pattern
- Relay
- Retry
- DLQ
- Replay
- Idempotency
- Poison Message
- Eventual Consistency
- At-least-once delivery

## Research Keywords

- `outbox pattern kafka spring boot`
- `dead letter queue best practices`
- `kafka retry dlq architecture`
- `idempotent consumer design`
- `event driven error handling`
- `poison message handling kafka`

## Our Notes / Emphasis

- Outbox prevents event publish loss.
- DLQ is not a business cancel mechanism.
- Retry always comes before DLQ.
- Replay should happen only after root-cause fix.
- Replay without idempotency is risky.
- Duplicate events are normal in at-least-once systems.
