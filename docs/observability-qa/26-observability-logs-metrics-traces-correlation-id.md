---
title: "Observability: Logs, Metrics, Traces, and Correlation IDs"
description: Observability rules for structured logs, metrics, traces, correlation-id, tenant-id, and request context propagation.
sidebar_position: 1
---

## Introduction

Observability is the ability to understand what a production system is doing from its emitted signals. Logs, metrics, and traces each answer different questions. Together, they let the team move from "something is wrong" to "this is where and why it is wrong."

For a multi-tenant product, observability must also carry request context consistently. At minimum, every request should preserve `correlation-id` and `tenant-id` through logs, metrics, traces, async jobs, and downstream calls.

## Why It Matters

When production incidents happen, missing context is expensive:

- logs show errors but not the request path,
- metrics show latency but not the affected tenant,
- traces show spans but cannot be connected to application logs,
- async jobs lose the original request identity,
- support cannot answer whether one user, one tenant, or all tenants are affected.

Observability is a product reliability requirement, not only a platform feature.

## Core Concepts

- Log: event-level record of something that happened.
- Metric: numeric measurement over time.
- Trace: end-to-end representation of a request or workflow across components.
- Span: one operation inside a trace.
- Correlation ID: stable identifier used to connect logs, traces, and application events.
- `tenant-id`: tenant boundary context for multi-tenant operations.
- Structured logging: logs emitted as parseable fields instead of only text.
- Context propagation: carrying identifiers across process, thread, queue, and service boundaries.

## Problem in Real Product Development

The common failure mode is local observability:

```text
Service A logs request_id
Service B logs trace_id
Worker logs nothing
Database metrics have no tenant dimension
```

Each component looks observable alone, but the workflow is not observable as a whole.

For tenant-aware products, this is especially damaging because the team must quickly answer:

- Is this a tenant-specific issue?
- Is one module slow or the whole request path slow?
- Did an async event continue after the API response?
- Which user action triggered the background failure?

## Approach / Design

### Required Request Context

Every inbound request should have:

- `correlation-id`
- `tenant-id` when authenticated or resolved
- `user-id` when authenticated
- `request-path`
- `request-method`
- `client-ip` according to privacy policy
- `device-session-id` where relevant

### Propagation Rule

```text
Inbound HTTP
  -> resolve or create correlation-id
  -> resolve tenant-id from auth/context
  -> add to logging MDC/context
  -> add to trace attributes
  -> pass to downstream HTTP headers
  -> include in queue message metadata
  -> include in audit log for critical actions
```

Recommended header names:

```text
X-Correlation-Id
X-Tenant-Id
traceparent
```

`X-Tenant-Id` must not be trusted blindly from the client. It should be validated against authenticated user/session context.

### Signal Responsibilities

| Signal | Best For | Not Best For |
| --- | --- | --- |
| Logs | explaining discrete events and errors | aggregate trend analysis |
| Metrics | alerting and trend detection | detailed root cause |
| Traces | cross-component latency and causality | long-term high-cardinality analytics |
| Audit logs | compliance and sensitive action history | debug-level diagnostics |

## Sector Standard / Best Practice

- Emit structured logs with stable field names.
- Use OpenTelemetry-compatible context propagation.
- Keep cardinality under control in metrics.
- Always connect logs to traces using trace/correlation identifiers.
- Do not place raw personal data or secrets in logs.
- Propagate request context into async jobs and scheduled workflows.
- Use `tenant-id` as a controlled context field, not a free-form client input.

## Pseudocode / Decision Flow / Lifecycle / Policy Example

```text
handle_inbound_request(request):
  correlation_id = request.header('X-Correlation-Id') or generate_uuid()
  tenant_id = resolve_tenant_from_authenticated_context(request)

  context.set('correlation-id', correlation_id)
  context.set('tenant-id', tenant_id)

  log.info('request.started', fields=context.safe_fields())
  trace.current_span.set_attribute('correlation-id', correlation_id)
  trace.current_span.set_attribute('tenant-id', tenant_id)

  response = next_handler(request)
  response.header('X-Correlation-Id', correlation_id)
  return response
```

Async propagation:

```text
publish_message(topic, payload):
  metadata = {
    correlation_id: context.get('correlation-id'),
    tenant_id: context.get('tenant-id'),
    traceparent: trace.current_context()
  }
  broker.publish(topic, payload, metadata)
```

## Our Notes / Team Decisions

- All requests must carry `correlation-id`.
- All tenant-scoped operations must carry validated `tenant-id`.
- Logs, metrics, traces, and audit entries should use consistent field names.
- Async workflows must preserve context, not create disconnected observability islands.
- This document connects directly to [OpenTelemetry and Tracing Strategy](./opentelemetry-tracing-strategy).

## External References

- OpenTelemetry: [Documentation](https://opentelemetry.io/docs/)

## Glossary

- Correlation ID: identifier used to connect related events across components.
- `tenant-id`: tenant context used for isolation and diagnostics.
- Trace: distributed request or workflow path.
- Span: timed operation within a trace.
- Structured log: log entry with parseable fields.
- Context propagation: passing diagnostic context across boundaries.

## Research Keywords

- `observability logs metrics traces correlation id`
- `tenant id structured logging`
- `opentelemetry context propagation`
- `trace log correlation`
- `multi tenant observability strategy`

## Conclusion

Reliable production debugging requires connected signals. A consistent `correlation-id` and validated `tenant-id` policy turns scattered logs, metrics, and traces into an explainable request story.
