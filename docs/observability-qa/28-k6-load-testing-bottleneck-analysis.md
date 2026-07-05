---
title: "k6 Load Testing and Bottleneck Analysis"
description: Load testing strategy with k6 scenarios, thresholds, ramping arrival rate, and an 08:30 attendance peak test design.
sidebar_position: 3
---

## Introduction

Load testing validates whether the system can handle realistic traffic before users discover the bottleneck in production.

For this product, the 08:30 attendance peak is a critical scenario. Many teachers may open the application, load classroom data, mark attendance, and submit changes within a short window.

## Why It Matters

Peak traffic is not the same as average traffic.

A system can look healthy all day and still fail when:

- many users perform the same workflow at the same time,
- cache entries expire together,
- database connection pools saturate,
- write locks accumulate,
- notification jobs start competing with request traffic,
- authentication refresh traffic spikes.

k6 helps model scenarios, thresholds, and ramping arrival rates so capacity decisions are evidence-based.

## Core Concepts

- Virtual user: simulated user executing a script.
- Scenario: workload model defining how traffic is generated.
- Arrival rate: number of iterations started per time unit.
- Ramping arrival rate: traffic model that changes arrival rate over time.
- Threshold: pass/fail condition for metrics.
- Check: functional assertion inside the test.
- Bottleneck: constrained component that limits throughput or latency.
- Saturation: state where resource demand exceeds useful capacity.

## Problem in Real Product Development

Simple load tests often miss the real risk:

```text
GET /health 1000 times
```

This does not validate the attendance workflow. The real scenario includes authentication, classroom list reads, student list reads, attendance writes, validation, database transactions, outbox writes, and possibly notifications.

## Approach / Design

### 08:30 Attendance Peak Scenario

Assumptions should be configurable, not hard-coded:

- number of active teachers,
- ramp-up window,
- attendance submit rate,
- average students per class,
- authentication token reuse,
- target environment base URL.

Example traffic shape:

```text
08:25 - 08:29 warm-up browsing
08:30 - 08:35 high attendance submission rate
08:35 - 08:40 trailing corrections and reloads
```

### Candidate k6 Script Skeleton

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    attendance_peak: {
      executor: 'ramping-arrival-rate',
      timeUnit: '1m',
      preAllocatedVUs: 100,
      maxVUs: 500,
      stages: [
        { duration: '4m', target: 60 },
        { duration: '5m', target: 240 },
        { duration: '3m', target: 120 },
        { duration: '2m', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<800', 'p(99)<1500'],
    'http_req_duration{endpoint:attendance_submit}': ['p(95)<1200'],
  },
};

const BASE_URL = __ENV.BASE_URL;

export default function () {
  const token = loginOrUseSeededToken();

  const headers = {
    Authorization: `Bearer ${token}`,
    'X-Correlation-Id': crypto.randomUUID(),
  };

  const classroom = http.get(`${BASE_URL}/api/classrooms/current`, { headers });
  check(classroom, { 'classroom loaded': (r) => r.status === 200 });

  const attendancePayload = buildAttendancePayload();
  const submit = http.post(
    `${BASE_URL}/api/attendance/mark`,
    JSON.stringify(attendancePayload),
    {
      headers: { ...headers, 'Content-Type': 'application/json' },
      tags: { endpoint: 'attendance_submit' },
    }
  );

  check(submit, { 'attendance submitted': (r) => r.status === 200 || r.status === 201 });
  sleep(1);
}
```

The helper functions should be implemented with project-specific seeded users and classroom fixtures.

### Bottleneck Analysis Checklist

During the test, observe:

- API p95/p99 latency,
- HTTP failure rate,
- database CPU and locks,
- database connection pool saturation,
- slow queries,
- Redis latency and hit rate if used,
- message broker lag,
- JVM heap and GC pressure,
- pod CPU throttling,
- autoscaling behavior.

## Sector Standard / Best Practice

- Test complete workflows, not only isolated endpoints.
- Use thresholds as release gates.
- Separate smoke, load, stress, and soak tests.
- Use realistic data volume and tenant distribution.
- Tag requests by endpoint/use case.
- Correlate k6 results with metrics and traces.
- Run tests against production-like infrastructure, not only local machines.

## Pseudocode / Decision Flow / Lifecycle / Policy Example

```text
run_load_test(scenario):
  prepare_seed_data()
  start_observability_dashboard()
  execute_k6_scenario()
  collect_k6_summary()
  inspect_traces_for_slowest_requests()
  inspect_db_for_locks_and_slow_queries()

  if thresholds_failed:
    create_bottleneck_report()
    block_release_until_fixed()
  else:
    record_capacity_baseline()
```

## Our Notes / Team Decisions

- The 08:30 attendance peak is a high-priority load scenario.
- k6 scenarios should model real user flows, including reads and writes.
- Thresholds should be explicit so the test can pass or fail objectively.
- Bottleneck analysis should use the observability rules from [Observability: Logs, Metrics, Traces, and Correlation IDs](./observability-logs-metrics-traces-correlation-id).

## External References

- Grafana k6: [Documentation](https://grafana.com/docs/k6/latest/)

## Glossary

- Virtual user: simulated user in a load test.
- Scenario: traffic model executed by k6.
- Threshold: metric-based pass/fail rule.
- Ramping arrival rate: executor that changes request arrival rate over time.
- Bottleneck: limiting component under load.
- Saturation: overloaded resource state.

## Research Keywords

- `k6 load testing thresholds scenarios`
- `ramping arrival rate bottleneck analysis`
- `k6 attendance peak load test`
- `load testing database connection pool`
- `k6 p95 p99 latency thresholds`

## Conclusion

Load testing is most useful when it models real business peaks. The 08:30 attendance scenario should become a repeatable release confidence test connected to metrics, traces, and bottleneck analysis.
