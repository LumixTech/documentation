---
title: Hexagonal Validation Separation (Adapter vs Core)
description: "Validation boundary design in Hexagonal Architecture: input validation at adapters and business invariants in core."
sidebar_position: 3
---

## Introduction

One of the most common backend architecture mistakes is mixing all validation rules in one place. In practice, this causes duplicated checks across REST, Kafka, and batch flows.

This document explains how to separate validation responsibilities in Hexagonal Architecture.

## Why It Matters

If validation boundaries are unclear:

- business logic leaks into controllers and consumers,
- the same rule is implemented multiple times,
- behavior diverges by entry point,
- domain consistency becomes fragile.

A clean split prevents rule drift and keeps behavior consistent across adapters.

## Core Concepts

- Input validation: format and contract checks for incoming data.
- Business invariant: domain rule that must always hold true.
- Adapter: external entry point (`REST`, `Kafka`, `Batch`).
- Core: domain + application use cases + ports.
- Defensive design: critical business rules rechecked in core even when adapters validate.

## Problem in Real Product Development

A frequent anti-pattern is each adapter implementing its own rule set:

- REST controller validates one set,
- Kafka consumer validates another,
- service layer has a third variation.

This creates inconsistent outcomes and hidden production bugs.

## Approach / Design

### Responsibility Split

- Adapter responsibility:
  - "Can this request payload be accepted by the system boundary?"
  - nullability, schema, format, enum/value range, parseability

- Core responsibility:
  - "Is this operation valid according to business rules?"
  - invariant checks such as status, ownership, lifecycle constraints

### Flow

```text
REST/Kafka/Batch Adapter
  -> input validation
  -> command mapping
  -> core use case
  -> invariant checks
  -> side effects and persistence
```

### Example (REST + Core)

```java
@PostMapping("/orders")
public ResponseEntity<Void> create(@Valid @RequestBody CreateOrderRequest request) {
  CreateOrderCommand command = mapper.toCommand(request);
  createOrderUseCase.handle(command);
  return ResponseEntity.accepted().build();
}

public void handle(CreateOrderCommand command) {
  Customer customer = customerRepository.findById(command.customerId());
  if (!customer.isActive()) {
    throw new BusinessException("Inactive customer cannot place order");
  }
}
```

## Sector Standard / Best Practice

- Keep transport-level validation in adapters.
- Keep domain invariants in core use cases and aggregates.
- Ensure all adapters call the same core use case path.
- Avoid adapter-specific business rule branching.
- Document validation ownership per rule category.

## Pseudocode / Decision Flow / Lifecycle / Policy Example

```text
process_request(input, adapter_type):
  if not adapter_contract_valid(input):
    reject(reason='input_invalid')

  command = map_to_command(input)

  if not core_business_invariants_pass(command):
    reject(reason='business_invariant_failed')

  execute_use_case(command)
  return success
```

## Our Notes / Team Decisions

- `Kafka` is treated as adapter, same as `REST`.
- Core is not only "service"; it includes domain + application + ports.
- Validation must be explicitly split into adapter-level and core-level checks.
- Critical invariants remain in core as the final guardrail.

## Glossary

- Input validation: structural/contract validation at system boundary.
- Business invariant: rule that must remain true for domain integrity.
- Adapter: boundary component receiving external input.
- Core: business logic center independent from transport/infrastructure.
- Command: normalized input object for use case execution.

## Research Keywords

- `input validation vs business validation`
- `hexagonal architecture validation separation`
- `domain invariant examples`
- `where to validate in clean architecture`
- `adapter vs domain validation`

## Conclusion

Validation quality is not about adding more checks; it is about placing checks at the right boundary. Adapters protect input contracts, and core protects business truth.
