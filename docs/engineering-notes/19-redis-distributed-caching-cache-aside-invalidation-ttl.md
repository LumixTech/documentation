---
title: "Redis Distributed Caching: Cache-Aside, Invalidation, and TTL Trade-offs"
description: Engineering notes for Redis distributed caching, cache-aside flow, cache hit/miss behavior, invalidation complexity, and TTL consistency decisions.
sidebar_position: 6
---

## Introduction

Caching is one of the strongest tools for improving performance in distributed systems. But adding cache does not only make the system faster; it also adds complexity.

The classic warning is:

> "There are only two hard things in Computer Science: cache invalidation and naming things."

This note answers four engineering questions:

- Why does cache exist?
- How does Cache-Aside work?
- Why is cache invalidation difficult?
- When should TTL be preferred?

## Why Cache Exists

The goal is simple:

```text
Serve frequently read and expensive data with lower latency
```

Without cache:

```text
Database = Single Source of Truth
```

With Redis cache:

```text
Client -> App -> Redis -> DB
```

Now the system has multiple copies of the same data:

```text
DB copy
Cache copy
Potentially response/projection copies
```

This creates the core problem:

```text
Consistency
```

## Cache-Aside Strategy

Cache-Aside is also called lazy loading.

Flow:

```text
1. Request arrives
2. Application checks Redis
3. If data exists -> return from cache
4. If data does not exist -> read from DB
5. Store DB result in Redis
6. Return response
```

Spring example:

```java
@Cacheable(value = "users", key = "#id")
public User getUserById(Long id) {
    return userRepository.findById(id).orElseThrow();
}
```

The application owns the cache policy. Redis executes the storage and expiry behavior.

## Cache Hit vs Cache Miss

### Cache Hit

```text
Client -> App -> Redis -> Response
```

Result:

- low latency,
- no database load,
- lower application work.

### Cache Miss

```text
Client -> App -> Redis
               |
               v
              DB
               |
               v
            write Redis
               |
               v
            Response
```

Result:

- higher latency,
- database load,
- serialization and network cost,
- potential burst pressure if many requests miss at once.

## The Cost of Cache

Cache is not free performance.

It introduces:

- memory cost,
- serialization cost,
- network cost,
- invalidation complexity,
- stale data risk,
- operational monitoring needs.

The critical question is:

> Is this data really worth caching?

## Cache Invalidation Problem

Example:

```text
Redis:
users::42 -> email=ali@example.com

Database:
users(42) -> email=ali.new@example.com
```

If Redis is not updated or evicted, the system returns fast but wrong data.

That is the heart of invalidation: performance and correctness can pull in opposite directions.

## Entity Cache vs List Cache

### Entity Cache

```text
products::123
```

This cache key represents one entity.

### List / Query Cache

```text
homepageFeaturedProducts
categoryProducts::electronics::page=1
sellerProducts::sellerId=55
discountedProducts
```

These keys represent views or query results. They may contain snapshots of many products.

## Common Mistake

Assumption:

```text
Deleting only products::123 is enough
```

This is wrong when list caches contain the old product data.

Example:

```text
homepageFeaturedProducts -> old price
categoryProducts -> old price
sellerProducts::sellerId=55 -> old product name
```

The entity cache may be correct while view caches remain stale.

## Correct Mental Model

Cache invalidation should not start only from this question:

```text
Which entity changed?
```

The better question is:

```text
Which cached views include this changed entity?
```

Invalidation is often a view-level design problem, not only an entity-level cleanup task.

## TTL Strategy

TTL means Time-To-Live.

It automatically expires cached data after a defined duration:

```text
TTL = 30 seconds
```

Maximum stale data window:

```text
30 seconds
```

TTL is a valid strategy, but it is a consistency decision. It means the system accepts stale data for a bounded amount of time.

## TTL vs Manual Invalidation

| Property | TTL | CacheEvict / Manual Invalidation |
| --- | --- | --- |
| Simplicity | High | Lower |
| Immediate correctness | Lower | Higher |
| Operational effort | Lower | Higher |
| Control | Limited | Higher |
| Stale data risk | Bounded by TTL | Depends on invalidation correctness |

## When TTL Is Appropriate

TTL is appropriate when stale data is acceptable for a short period.

Examples:

- product listing with a 30-second tolerance,
- dashboard summaries,
- analytics,
- feeds,
- search/filter result pages,
- public homepage blocks.

## When TTL Is Wrong

TTL is dangerous when strong consistency is required.

Examples:

- payment state,
- account balance,
- authorization decisions,
- security-sensitive user state,
- fraud or risk decisions,
- inventory operations that must be exact.

In these cases, stale data can become a business or security incident.

## Spring vs Redis Responsibility

The right split is:

```text
Policy -> Application (Spring)
Execution -> Redis
```

Spring configuration example:

```java
RedisCacheConfiguration.defaultCacheConfig()
    .entryTtl(Duration.ofSeconds(30));
```

The application decides which cache exists and how long it should live. Redis applies that expiry policy.

## Granular TTL Design

Every cache should not use the same TTL.

Example policy:

```text
products             -> 30 sec
productDetails       -> 5 min
homepageFeatured     -> 15 sec
categoryProducts     -> 60 sec
userSession          -> 30 min
exchangeRates        -> 5 sec
```

TTL should reflect:

- business criticality,
- update frequency,
- stale data tolerance,
- recomputation cost,
- consumer expectations.

## Trade-off Summary

```text
TTL -> simple, but eventual consistency
Manual eviction -> more accurate, but more complex
```

The engineering decision is not "Redis or no Redis." The real decision is:

```text
Latency or consistency?
```

Different domains answer this differently.

## Socratic Takeaways

### Lesson 1

```text
Cache is not only speed. Cache is a data copy.
```

### Lesson 2

```text
Invalidation should be designed at the view level, not only at the entity level.
```

### Lesson 3

```text
Not everything should be cached.
```

### Lesson 4

```text
TTL is not an escape hatch. It is a conscious consistency decision.
```

## Next Topics

This note naturally leads to:

- Write-Through vs Write-Behind,
- Cache Stampede,
- Distributed Lock with Redisson,
- cache race condition scenarios,
- cache key naming strategy,
- multi-level cache with Caffeine and Redis,
- event-driven invalidation with Kafka.

## Research Keywords

- `redis cache aside pattern spring boot`
- `cache invalidation best practices`
- `redis ttl tradeoffs`
- `spring cache redis ttl configuration`
- `distributed cache consistency`
- `cache stampede redis`

## Conclusion

Adding cache is easy. Correct invalidation is hard.

A good cache design explicitly chooses where it accepts eventual consistency, where it requires immediate correctness, and which cached views must be invalidated when source data changes.
