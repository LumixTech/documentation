---
title: "Spring WebSocket and STOMP Architecture: Scaling in Distributed Systems"
description: Engineering notes for Spring WebSocket, STOMP, in-memory broker limits, broker relay, RabbitMQ, user-specific messaging, and distributed scaling.
sidebar_position: 7
---

## Introduction

WebSocket architecture is different from the classic HTTP request-response model. It is built on long-lived, bidirectional, stateful connections.

In HTTP, the server usually receives a request, processes it, returns a response, and finishes the request lifecycle. In WebSocket systems, the server keeps a live connection with the client. The client can send messages to the server, and the server can push messages to the client without a new client request.

This is powerful for chat, notifications, live dashboards, trading screens, multiplayer games, and collaborative editing.

But in distributed systems, it has an important cost:

> A WebSocket connection is not only a network connection. It is distributed system state.

A WebSocket system that works on a single pod will face new problems in production: multiple pods, autoscaling, pod crashes, reconnects, user presence, and message fan-out.

## Core Concepts

### HTTP Request-Response

In HTTP, the client sends a request, the server returns a response, and the operation ends.

```text
Client  --->  HTTP Request   --->  Server
Client  <---  HTTP Response  <---  Server
```

This model is usually compatible with stateless design. A load balancer can distribute requests across pods:

```text
Request 1 -> Pod A
Request 2 -> Pod B
Request 3 -> Pod C
```

### WebSocket Full-Duplex Connection

A WebSocket connection is long-lived and bidirectional.

```text
Client  <====================>  Server
          Full-duplex channel
```

After the connection is established, messages continue to flow over the same TCP/WebSocket connection.

### Connection Ownership

The pod that accepts the WebSocket connection owns that connection.

```text
Client A -> Pod-1
Client B -> Pod-2
Client C -> Pod-3
```

Client A's live socket is on Pod-1. Pod-2 cannot directly write to Client A's socket.

### STOMP

STOMP is a messaging protocol that can run over WebSocket.

Example frame types:

```text
CONNECT
SUBSCRIBE
SEND
MESSAGE
DISCONNECT
```

WebSocket provides the low-level connection. STOMP adds destination, subscription, and message semantics on top of that connection.

### In-Memory Broker

In Spring, this enables the simple broker:

```java
registry.enableSimpleBroker("/topic", "/queue");
```

This broker runs inside the application pod's JVM memory. Subscription registry, session information, and routing information are local.

### Broker Relay

In Spring, this relays messages to an external broker:

```java
registry.enableStompBrokerRelay("/topic", "/queue");
```

With this model, Spring does not behave as the real broker. It relays STOMP messages to an external broker such as RabbitMQ or ActiveMQ.

## Problem

In a single pod, WebSocket looks simple:

```text
Client A -> Pod-1
Client B -> Pod-1
Client C -> Pod-1
```

All clients are connected to the same pod, so the local in-memory broker knows all subscriptions.

With multiple pods, state is split:

```text
Client A -> Pod-1
Client B -> Pod-2
Client C -> Pod-3
```

Each pod only knows the WebSocket sessions and subscriptions in its own memory.

Assume all three clients subscribed to `/topic/orders`:

```text
Client A -> Pod-1 -> /topic/orders
Client B -> Pod-2 -> /topic/orders
Client C -> Pod-3 -> /topic/orders
```

If this code runs on Pod-1:

```java
simpMessagingTemplate.convertAndSend("/topic/orders", orderCreatedEvent);
```

and `enableSimpleBroker("/topic")` is used, the message goes only to Pod-1's local broker.

Result:

```text
Client A -> receives the message
Client B -> does not receive the message
Client C -> does not receive the message
```

Client B's subscription is in Pod-2 memory. Client C's subscription is in Pod-3 memory.

The core problem is:

> Local truth is not global truth.

A pod's local state does not represent the whole system.

## Wrong Approaches

### Thinking Sticky Sessions Are Enough

Sticky sessions route the same user to the same pod:

```text
User-123 -> always Pod-1
```

But sticky sessions do not solve these problems:

```text
1. How does an event created on another pod reach User-123?
2. What happens when the pod dies?
3. Where does global subscription information live?
4. How is broadcast solved?
5. How do pods coordinate during autoscaling?
```

Sticky sessions stabilize connection routing. They do not solve distributed messaging.

### Pod-to-Pod HTTP Broadcast

Another approach is to make the pod where the event occurs call the other pods over HTTP:

```text
Pod-2 -> HTTP call to Pod-1
Pod-2 -> HTTP call to Pod-3
```

This is fragile because Pod-2 must know:

```text
How many pods exist right now?
Which pods are healthy?
Was a new pod added?
Should failed pod calls be retried?
How are duplicate messages prevented?
```

This puts cluster coordination responsibilities inside application pods.

## Correct Approach: Broker / Pub-Sub / Broker Relay

A healthier model avoids direct pod awareness.

```text
Pod-1 ----\
Pod-2 -----+---- RabbitMQ / ActiveMQ
Pod-3 ----/
```

When an event occurs on Pod-2, it is published to the shared broker. Other pods receive the message through the broker and forward it to their local WebSocket sessions.

```text
Backend event on Pod-2
        |
        v
Pod-2 publishes event to broker
        |
        v
Broker distributes event
        |
        +--> Pod-1 sends to local clients
        +--> Pod-2 sends to local clients
        +--> Pod-3 sends to local clients
```

Important distinction:

> The broker provides global message routing.
> The Spring pod still owns local WebSocket connections.

RabbitMQ does not directly write to Client B's socket. RabbitMQ delivers the message to Pod-2. Pod-2 writes to its own WebSocket connection.

## Spring STOMP Flow

Typical Spring configuration:

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws");
    }
}
```

Prefix separation matters:

```text
/app    -> Spring application methods
/topic  -> broker-based broadcast messages
/queue  -> usually user-specific messages
```

Example client flow:

```text
Client SEND /app/orders/create
        |
        v
Spring @MessageMapping
        |
        v
Application logic
        |
        v
Broker sends MESSAGE to /topic/orders
        |
        v
Subscribed clients receive message
```

Backend code:

```java
@MessageMapping("/orders/create")
public void createOrder(CreateOrderCommand command) {
    OrderCreatedEvent event = orderService.create(command);
    messagingTemplate.convertAndSend("/topic/orders", event);
}
```

## In-Memory Broker Limit

When `enableSimpleBroker` is used, broker state lives inside the local JVM.

Each pod has its own local broker:

```text
Pod-1:
- Client A session
- Client A subscriptions

Pod-2:
- Client B session
- Client B subscriptions

Pod-3:
- Client C session
- Client C subscriptions
```

A message produced on Pod-1 reaches only subscribers known by Pod-1.

In production, this can appear as:

```text
Some users receive notifications, others do not.
Refreshing sometimes appears to fix the issue.
Live admin dashboard data looks incomplete.
Messages seem randomly lost.
```

The behavior is not random. The message is distributed according to the local broker state of the pod where it was produced.

## Distributed Messaging with Broker Relay

When broker relay is used, Spring relays messages to an external broker.

```java
@Override
public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.enableStompBrokerRelay("/topic", "/queue")
            .setRelayHost("rabbitmq")
            .setRelayPort(61613)
            .setClientLogin("guest")
            .setClientPasscode("guest")
            .setSystemLogin("guest")
            .setSystemPasscode("guest");

    registry.setApplicationDestinationPrefixes("/app");
}
```

Flow:

```text
Client A -> Pod-1
Client B -> Pod-2
Client C -> Pod-3

A, B, C subscribe /topic/orders

Pod-1:
simpMessagingTemplate.convertAndSend("/topic/orders", payload)
        |
        v
RabbitMQ
        |
        +--> Pod-1 -> Client A
        +--> Pod-2 -> Client B
        +--> Pod-3 -> Client C
```

RabbitMQ owns global routing and fan-out.

Spring pods are responsible for:

```text
Providing WebSocket endpoints
Authentication
Authorization
Principal mapping
Domain logic
Writing messages to local sockets
```

RabbitMQ is responsible for:

```text
Message routing
Fan-out
Cross-pod message distribution
Broker-level delivery
```

## RabbitMQ STOMP vs Web-STOMP

Two RabbitMQ concepts are often confused.

### RabbitMQ STOMP Plugin

Allows Spring pods to connect to RabbitMQ with the STOMP protocol.

```text
Spring Pod -> RabbitMQ STOMP Port
```

### RabbitMQ Web-STOMP Plugin

Allows browser clients to connect directly to RabbitMQ over WebSocket with STOMP.

```text
Browser -> RabbitMQ Web-STOMP
```

In enterprise Spring architectures, the browser usually should not connect directly to RabbitMQ.

The preferred model is often:

```text
Browser -> Spring WebSocket Endpoint -> RabbitMQ
```

Spring remains in the middle to provide:

```text
Authentication
Authorization
Domain validation
Principal mapping
Security boundary
Application command handling
```

Direct browser-to-broker connections can increase the security surface.

## User-Specific Messaging Problem

Spring sends user-specific messages with:

```java
simpMessagingTemplate.convertAndSendToUser(
    "userB",
    "/queue/notifications",
    payload
);
```

Client subscription:

```javascript
stompClient.subscribe("/user/queue/notifications", handler);
```

On a single pod, this is simple because Spring knows the local mapping:

```text
username -> sessionId -> WebSocket connection
```

In a multi-pod system, if userB is connected to Pod-2 and `convertAndSendToUser` runs on Pod-1, Pod-1 may not have userB's session mapping in local memory.

Problem:

```text
user -> session mapping is in Pod-2 memory
subscription registry is in Pod-2 memory
actual socket connection is on Pod-2
```

Pod-1 only knows `username = userB`. It cannot locally translate that username into a live socket.

User-specific messaging therefore requires extra care in distributed systems.

## Does Broker Relay Make the System Stateless?

No.

Broker relay moves an important part of messaging state outside the pod, but WebSocket connection state still lives on the Spring pod.

Two state types must be separated:

```text
1. Connection State
   Which client is connected to which pod?
   Is the socket open?
   Are heartbeats arriving?
   Did the client reconnect?

2. Messaging State
   Who subscribed to which destination?
   Which message should be routed where?
   Which consumer should the broker deliver to?
```

Broker relay improves the second area. Connection ownership still remains on the pod.

If Pod-2 dies:

```text
Client B's WebSocket connection is closed.
RabbitMQ can route messages.
But it cannot deliver to a dead socket.
Client B must reconnect.
```

## Scaling Metrics

WebSocket scaling is not measured only by request count.

Important metrics:

```text
Active WebSocket connection count
Memory usage per connection
Subscription count
Destination fan-out ratio
Heartbeat traffic
Socket buffer usage
Slow consumer count
Reconnect rate
File descriptor usage per pod
Broker latency
Broker queue depth
```

HTTP scaling is mostly a request workload distribution problem.

WebSocket scaling is a connection ownership and state coordination problem.

## Production Flow

A production-ready flow often looks like this:

```text
1. Client connects to the Spring WebSocket endpoint.
2. Client sends a STOMP CONNECT frame.
3. Spring performs authentication and principal mapping.
4. Client SUBSCRIBEs to relevant destinations.
5. Spring moves subscription messaging through broker relay.
6. A backend event occurs.
7. The event is published to a /topic or /queue destination.
8. RabbitMQ / ActiveMQ distributes the message to relevant pods.
9. Each pod writes the message to its local WebSocket sessions.
10. If the client disconnects, reconnect, resubscribe, and missed-event strategies apply.
```

## Architecture Decision Notes

### Simple Broker

Simple broker is acceptable for local development, demos, and single-instance scenarios.

It is not sufficient when multiple pods, autoscaling, and global broadcast are required.

### Broker Relay

Broker relay should be preferred in multi-pod architectures.

Consider it when the system needs:

```text
Global topic broadcast
Cross-pod message distribution
User-specific messaging
Autoscaling
Notification system
Live dashboard
Admin broadcast
Presence-aware event delivery
```

### Sticky Sessions

Sticky sessions are not a complete solution. They only stabilize connection routing.

### External Broker

An external broker moves the messaging problem to the right layer, but it adds operational responsibilities:

```text
Broker monitoring
High availability
Queue policy
Heartbeat tuning
Reconnect handling
Backpressure management
Security configuration
```

## Technical Glossary

- WebSocket: protocol that provides a long-lived, bidirectional connection between client and server.
- Full-Duplex: ability for both sides to send messages independently over the same connection.
- STOMP: messaging protocol that adds destination, subscription, and message semantics over WebSocket.
- Destination: logical address where STOMP messages are sent, such as `/topic/orders`.
- Topic: broadcast destination where one message is distributed to multiple subscribers.
- Queue: destination type often used for point-to-point or user-specific messaging.
- In-Memory Broker: simple broker inside the Spring application that keeps subscription and routing state in local memory.
- Broker Relay: architecture where Spring relays messages to an external broker.
- RabbitMQ: message broker that can be used with STOMP or Web-STOMP plugins.
- Web-STOMP: RabbitMQ plugin that lets browser clients speak STOMP over WebSocket directly with RabbitMQ.
- Sticky Session: load balancer behavior that routes the same user to the same backend instance.
- Connection State: state about which client is connected to which pod.
- Subscription Registry: registry of which session subscribed to which destination.
- User Presence: system-wide tracking of whether a user is online, offline, or idle.
- Fan-out: distributing one message to multiple clients or subscribers.
- Backpressure: pressure caused when message production is faster than message consumption.
- Heartbeat: periodic signal used to detect whether a connection is still alive.

## Research Keywords

- `spring websocket stomp broker relay`
- `spring enableSimpleBroker vs enableStompBrokerRelay`
- `spring websocket rabbitmq stomp broker relay`
- `rabbitmq stomp plugin spring websocket`
- `rabbitmq web stomp vs stomp plugin`
- `spring simpMessagingTemplate convertAndSend distributed`
- `spring convertAndSendToUser multiple instances`
- `websocket scaling kubernetes spring boot`
- `websocket sticky session limitations`
- `distributed websocket architecture`
- `websocket presence management`
- `websocket heartbeat reconnect strategy`
- `stomp broker relay rabbitmq scaling`
- `spring websocket user destination multiple nodes`
- `websocket connection state distributed systems`
- `pub sub for websocket scaling`

## Conclusion

The most important distinction in Spring WebSocket and STOMP architecture is:

> STOMP is the protocol.
> Broker architecture determines how messages actually move in a distributed system.

`enableSimpleBroker` is useful for a single pod, but it does not provide global message distribution in a multi-pod environment because subscription and session state remain local.

With `enableStompBrokerRelay`, an external broker such as RabbitMQ or ActiveMQ moves messaging state outside the pod and enables cross-pod message distribution.

However, broker relay does not make the system fully stateless. WebSocket connection state still lives on the Spring pod. Reconnect, heartbeat, presence management, missed messages, and pod crash scenarios must be designed separately.

Production-ready WebSocket architecture should start from this fact:

> WebSocket scaling is not request scaling.
> WebSocket scaling is a connection ownership, distributed message routing, and state coordination problem.
