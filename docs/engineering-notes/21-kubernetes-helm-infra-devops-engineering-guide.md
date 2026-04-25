---
title: "Kubernetes and Helm: Infra/DevOps Engineering Guide"
description: Engineering guide for Kubernetes orchestration, object model, networking, deployment lifecycle, production debugging, Helm, and infra/devops workflows.
sidebar_position: 8
---

## Introduction

This guide explains Kubernetes from an engineering perspective rather than as a set of commands to memorize.

The goal is to understand production behavior:

- how traffic flows,
- how deployments work,
- how failures are debugged,
- how infrastructure, DevOps, and application design relate to each other.

## Why Kubernetes Exists

Docker solves one part of the problem:

```text
Run the application inside a container
```

Production systems need more:

```text
How many instances should run?
What happens if one dies?
Where should traffic go?
How should updates be rolled out?
```

Kubernetes solves this through orchestration:

```text
Desired State -> you define it
Actual State -> Kubernetes observes it
Reconciliation -> Kubernetes closes the gap
```

Critical idea:

```yaml
replicas: 4
```

means:

```text
There should always be 4 pods running
```

## Kubernetes Object Model

The common ownership chain is:

```text
Deployment
   |
   v
ReplicaSet
   |
   v
Pod
   |
   v
Container
```

### Pod

A pod is the smallest runtime unit in Kubernetes.

Characteristics:

- ephemeral,
- has its own IP,
- contains one or more containers,
- containers inside the same pod share lifecycle and network namespace.

### ReplicaSet

A ReplicaSet keeps the desired number of pods running.

If a pod dies, the ReplicaSet creates another pod to restore the desired count.

### Deployment

A Deployment manages rollout behavior.

It can:

- create ReplicaSets,
- perform rolling updates,
- roll out a new version,
- reduce old version instances,
- roll back when needed.

### Label and Selector

Labels identify objects:

```yaml
labels:
  app: payment-api
```

Selectors find matching objects:

```yaml
selector:
  app: payment-api
```

If labels and selectors do not match:

```text
Service -> cannot find Pods -> traffic does not reach the application
```

## Networking and Traffic Flow

The standard external traffic path is:

```text
User
 -> Ingress
 -> Service
 -> Pod
 -> Container
```

### Service

A Service provides a stable endpoint for pods.

Pod IPs change. Service identity stays stable.

Example:

```yaml
port: 80
targetPort: 8080
```

Meaning:

```text
Service receives traffic on port 80
Service forwards traffic to container port 8080
```

Critical mistake:

```text
Service selector != Pod label
```

Result:

```text
Traffic does not reach any pod
```

### Ingress

Ingress handles host and path routing.

Example:

```text
api.example.com/payments -> payment-service
```

Ingress usually sits in front of Services and routes external HTTP(S) traffic into the cluster.

## Configuration and Secrets

If configuration is baked into the Docker image:

```text
Every config change requires an image rebuild
```

Kubernetes separates configuration from images:

```text
ConfigMap -> normal configuration
Secret -> sensitive data
```

Important detail:

```text
Changing a ConfigMap or Secret does not always automatically restart pods
```

Often a rollout restart is needed:

```bash
kubectl rollout restart deployment payment-api
```

## Deployment Lifecycle

### Rolling Update

A rolling update moves from one version to another gradually:

```text
v1 -> v2
```

Common strategy parameters:

```yaml
maxSurge: 1
maxUnavailable: 0
```

Meaning:

- `maxSurge: 1`: Kubernetes may create one extra pod above the desired replica count during rollout.
- `maxUnavailable: 0`: Kubernetes should keep all desired replicas available during rollout.

### Probes

Readiness probe:

```text
Can this pod receive traffic?
```

Liveness probe:

```text
Is this pod alive?
```

Startup probe:

```text
Has the application finished starting?
```

Critical fact:

```text
Running != Ready
```

A pod can be running but not ready to serve traffic.

## Production Debugging

Start with these commands:

```bash
kubectl get pods
kubectl describe pod <pod-name>
kubectl logs <pod-name>
kubectl get svc
kubectl get endpoints
```

Common failure states:

### CrashLoopBackOff

```text
The application starts, crashes, and Kubernetes restarts it repeatedly
```

### ImagePullBackOff

```text
Kubernetes cannot pull the container image
```

### Pending

```text
The pod cannot be scheduled
```

Common causes include insufficient resources, node selectors, taints, or PVC issues.

### OOMKilled

```text
The container exceeded its memory limit
```

The most important debug chain is:

```text
Ingress
 -> Service
 -> Endpoint
 -> Pod
 -> Container
```

When traffic does not work, inspect that chain in order.

## Helm

### What Helm Is

Helm is:

```text
Kubernetes package manager + template engine
```

### Why Helm Is Needed

Avoid duplicating YAML per environment:

```text
deployment-dev.yaml
deployment-prod.yaml
deployment-staging.yaml
```

Prefer:

```text
One template + environment-specific values
```

### Chart Structure

```text
chart/
 ├── Chart.yaml
 ├── values.yaml
 ├── templates/
```

### values.yaml

```yaml
replicaCount: 4
image:
  tag: 1.4.7
```

### Template

```yaml
replicas: {{ .Values.replicaCount }}
```

Render flow:

```text
Helm -> renders YAML -> Kubernetes applies it
```

### Chart vs Release

```text
Chart = deployment recipe
Release = running instance of that recipe
```

Common commands:

```bash
helm install
helm upgrade
helm rollback
```

Production best practice:

```text
Do not use latest tag
Use immutable image tags
```

## Infra and DevOps Perspective

Typical pipeline:

```text
Code
 -> CI
 -> Docker image
 -> Registry
 -> Helm deploy
 -> Kubernetes
```

### Resource Management

Applications should define resource requests and limits:

```yaml
resources:
  requests:
    cpu: "250m"
    memory: "512Mi"
  limits:
    cpu: "500m"
    memory: "1Gi"
```

Requests help Kubernetes schedule pods. Limits define the maximum resource usage allowed.

### Namespace

Namespaces separate environments or teams:

```text
dev / staging / prod
```

### Observability

Production systems need:

```text
Logs
Metrics
Tracing
```

Without observability, Kubernetes only makes failures distributed and harder to inspect.

### GitOps

GitOps model:

```text
Git = source of truth
Cluster = synced to Git
```

Tools such as Argo CD or Flux watch Git and reconcile cluster state.

## Modular Monolith vs Microservices

### Modular Monolith

```text
One application
One port
One deployment
```

Communication happens inside the process:

```text
Not HTTP
-> method call / interface / internal event
```

Kubernetes shape:

```text
1 Deployment
1 Service
1 Ingress
```

### Microservices

```text
Each service has a separate Deployment
Each service has a separate port
Service-to-service communication is needed
```

Kubernetes gives primitives for running both styles, but it does not remove distributed system complexity.

## Critical Mental Model

This is the central production lesson:

```text
Pod is running -> system is working
```

is not a valid conclusion.

The better question is:

```text
Is the traffic chain correct?
```

Always verify:

```text
Ingress -> Service -> Endpoint -> Pod -> Container
```

## Technical Glossary

- Deployment: Kubernetes object that manages rollout and ReplicaSets.
- ReplicaSet: object that keeps a desired number of pod replicas running.
- Pod: smallest Kubernetes runtime unit.
- Container: packaged application process running inside a pod.
- Service: stable network abstraction over pods.
- Endpoint: actual pod IP and port targets behind a Service.
- Ingress: HTTP(S) routing entry point into the cluster.
- ConfigMap: non-sensitive configuration object.
- Secret: sensitive configuration object.
- Readiness Probe: check that decides whether a pod can receive traffic.
- Liveness Probe: check that decides whether a pod should be restarted.
- Startup Probe: check that protects slow-starting applications from premature restarts.
- Helm Chart: reusable Kubernetes deployment package.
- Helm Release: installed running instance of a chart.
- Namespace: logical isolation boundary in Kubernetes.
- GitOps: operating model where Git defines desired cluster state.

## Summary

```text
Deployment -> lifecycle
ReplicaSet -> replica guarantee
Pod -> runtime
Service -> network abstraction
Ingress -> external traffic
ConfigMap -> configuration
Secret -> sensitive data
Helm -> deployment management
```

## Research Keywords

- `kubernetes traffic flow ingress service pod`
- `kubernetes readiness vs liveness probe`
- `kubernetes debugging crashloopbackoff`
- `helm chart best practices`
- `kubernetes deployment rolling update strategy`
- `kubernetes service selector labels`
- `kubernetes production debugging checklist`
- `modular monolith vs microservices communication`

## Conclusion

Kubernetes is not just a place where containers run. It is a reconciliation system that continuously tries to make actual cluster state match declared desired state.

Production Kubernetes engineering requires understanding object ownership, traffic flow, rollout behavior, resource limits, observability, and debugging chains. The most useful habit is to reason from desired state to actual state, then follow traffic from Ingress to Container.
