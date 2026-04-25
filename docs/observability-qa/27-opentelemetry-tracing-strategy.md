---
title: "OpenTelemetry and Tracing Strategy"
description: Tracing strategy for critical use cases, span design, context propagation, and Spring Boot oriented observability.
sidebar_position: 2
---

## Introduction

Tracing explains how a request or workflow moves through the system. It is especially valuable when the flow crosses HTTP APIs, domain services, repositories, message brokers, scheduled jobs, and external integrations.

OpenTelemetry provides a vendor-neutral model for traces, metrics, logs, context propagation, instrumentation, and exporters.

This document defines how to think about spans for three critical product use cases.

## Why It Matters

Logs can tell us that something failed. Metrics can tell us that latency increased. Traces show the path, timing, and causality between operations.

Without tracing:

- async workflows are hard to connect to the original request,
- slow database queries hide inside broad API latency,
- external service delays look like application slowness,
- cross-module failures require manual log archaeology.

## Core Concepts

- OpenTelemetry: standard observability framework for collecting and exporting telemetry.
- Trace: full execution path of one request or workflow.
- Span: one timed operation inside a trace.
- Parent/child span: relationship that represents call hierarchy.
- Attribute: structured metadata attached to a span.
- Context propagation: carrying trace context across boundaries.
- Sampling: deciding which traces are retained.
- Instrumentation: automatic or manual code that emits telemetry.

## Problem in Real Product Development

Auto-instrumentation is useful, but it does not know product intent. It may show HTTP and database spans while missing business milestones such as:

- attendance marked,
- chat message persisted,
- payment authorization requested,
- notification event published,
- permission check denied.

The trace should show both technical operations and domain-relevant steps.

## Approach / Design

### Span Naming Rules

Use stable names that describe operations:

```text
HTTP POST /attendance/mark
AttendanceService.markAttendance
AttendanceRepository.saveBatch
NotificationPublisher.publishAttendanceChanged
```

Avoid high-cardinality names:

```text
bad: GET /students/8f4e6...
good: GET /students/{studentId}
```

### Required Span Attributes

- `correlation-id`
- `tenant-id`
- `user-id` where safe and allowed
- `module`
- `use_case`
- `outcome`
- `error.code` when failed
- `db.operation` for database spans
- `messaging.destination` for broker spans

### Critical Use Case 1: Attendance Peak

```text
Trace: mark_attendance
  Span: HTTP POST /attendance/mark
    Span: AuthContext.resolve
    Span: Authorization.check role_permission/user_permission
    Span: AttendanceService.validateWindow
    Span: AttendanceRepository.saveBatch
    Span: OutboxRepository.insert attendance_marked
    Span: Transaction.commit
    Span: MessageBroker.publish attendance_marked
```

Important attributes:

- `tenant-id`
- `classroom_id`
- `student_count`
- `attendance_window`
- `outcome`

### Critical Use Case 2: Chat Message Flow

```text
Trace: send_chat_message
  Span: WebSocket inbound /chat/send
    Span: ChatAuthorization.checkParticipant
    Span: ChatMessageService.persist
    Span: AttachmentService.validateReferences
    Span: OutboxRepository.insert message_created
    Span: WebSocket fanout
    Span: PushNotification.enqueue
```

Important attributes:

- `tenant-id`
- `conversation_id`
- `message_type`
- `has_attachment`
- `delivery_channel`

### Critical Use Case 3: Payment Flow

```text
Trace: payment_attempt
  Span: HTTP POST /payments
    Span: PaymentAuthorization.check
    Span: PaymentService.createAttempt
    Span: PaymentProvider.authorize
    Span: PaymentRepository.updateStatus
    Span: AuditLog.record payment_attempt
    Span: NotificationPublisher.publishPaymentUpdated
```

Important attributes:

- `tenant-id`
- `payment_id`
- `provider`
- `provider_status`
- `outcome`

## Sector Standard / Best Practice

- Use auto-instrumentation for framework-level spans.
- Add manual spans for business-critical operations.
- Keep span names stable and low-cardinality.
- Propagate trace context through HTTP headers, queues, and scheduled jobs.
- Connect logs to traces using trace ID and `correlation-id`.
- Use sampling carefully for high-volume endpoints; keep error traces.
- Avoid recording secrets or personal data in span attributes.

## Pseudocode / Decision Flow / Lifecycle / Policy Example

```text
trace_business_operation(use_case, context):
  span = tracer.start_span(use_case)
  span.set_attribute('tenant-id', context.tenant_id)
  span.set_attribute('correlation-id', context.correlation_id)
  span.set_attribute('module', context.module)

  try:
    result = execute_use_case()
    span.set_attribute('outcome', 'success')
    return result
  catch error:
    span.record_exception(error)
    span.set_attribute('outcome', 'error')
    raise error
  finally:
    span.end()
```

## Our Notes / Team Decisions

- Traces must cover async workflows and module-to-module flows.
- The three initial span maps are attendance peak, chat message flow, and payment flow.
- Manual spans should be added where product meaning would otherwise be invisible.
- Trace context rules should follow [Observability: Logs, Metrics, Traces, and Correlation IDs](./observability-logs-metrics-traces-correlation-id).

## External References

- OpenTelemetry: [Documentation](https://opentelemetry.io/docs/)

## Glossary

- Trace: end-to-end path of a workflow.
- Span: timed operation inside a trace.
- Attribute: structured metadata attached to telemetry.
- Sampling: retaining a subset of traces.
- Instrumentation: code or agent that emits telemetry.
- Context propagation: carrying trace context across boundaries.

## Research Keywords

- `opentelemetry tracing spans context propagation`
- `spring boot tracing strategy`
- `trace log correlation opentelemetry`
- `business spans distributed tracing`
- `async trace propagation message broker`

## Conclusion

Tracing should show both technical latency and business causality. OpenTelemetry gives the standard model, but the team must define meaningful spans for product-critical workflows.
