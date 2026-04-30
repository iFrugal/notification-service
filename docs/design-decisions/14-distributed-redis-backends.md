# Decision 14: Distributed Redis backends for idempotency, rate limit, and DLQ

## Status: DECIDED

## Context

DD-10 (idempotency), DD-12 (rate limit), and DD-13 (dead-letter queue)
all ship with **in-memory default implementations** and explicit
single-pod caveats. Each one says "Redis-backed implementation foreseen
for the multi-pod case" and shapes its SPI accordingly:

- DD-10's `IdempotencyStore` → in-memory `CaffeineIdempotencyStore`,
  Redis impl mentioned in §SPI.
- DD-12's `RateLimiter` → in-memory `Bucket4jRateLimiter`, Redis
  swap-in via `bucket4j-redis` mentioned in §"Why Bucket4j".
- DD-13's `DeadLetterStore` → in-memory `InMemoryDeadLetterStore`,
  Redis listed under "Out of scope".

For multi-pod deployments these in-memory stores are wrong:
- A duplicate `idempotencyKey` arriving at pod B during pod A's send
  isn't deduplicated.
- Rate-limit buckets are per-pod, so a global "100 req/s for tenant
  acme" actually allows 100×N where N = pod count.
- DLQ entries scatter across pods — operators have to query each one
  to find a failed send.

The three SPIs were already designed to allow Redis-backed beans to
swap in via `@ConditionalOnMissingBean`. This DD ships those
implementations as a **single new module**, `notification-redis`, so
operators can opt in to "distributed mode" with one dependency and one
property flag.

## Decision

Introduce `notification-redis`, a new Maven module containing
Redis-backed implementations of all three SPIs. The module is opt-in
(consumers don't pull it unless they want distributed state); each
backend within the module is independently toggleable via property
flag so operators can mix and match (e.g. distributed idempotency but
in-memory DLQ for development).

### Module shape

```
notification-redis/
├── pom.xml                          # depends on notification-core +
│                                    #   spring-boot-starter-data-redis +
│                                    #   bucket4j-redis (transitive lettuce)
├── src/main/java/.../redis/
│   ├── RedisIdempotencyStore.java   # implements DD-10 IdempotencyStore
│   ├── RedisRateLimiter.java        # implements DD-12 RateLimiter
│   ├── RedisDeadLetterStore.java    # implements DD-13 DeadLetterStore
│   └── RedisProperties.java         # nested config inside NotificationProperties
└── src/test/java/.../redis/         # Testcontainers-backed integration tests
```

Each implementation is annotated with:

```java
@ConditionalOnProperty(prefix = "notification.redis.<feature>",
                       name = "enabled", havingValue = "true")
@ConditionalOnMissingBean(<SpiInterface>.class)
@ConditionalOnClass(LettuceConnectionFactory.class)
```

Triple gating means:
1. The bean is only created when the operator explicitly enables the
   Redis backend for that feature.
2. A custom bean (perhaps a future Hazelcast or DynamoDB impl) wins
   automatically.
3. The class is only loaded if Spring Data Redis is on the classpath.

### Configuration

```yaml
notification:
  redis:
    # Connection — defers to Spring's spring.data.redis.* if not set
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
    password: ${REDIS_PASSWORD:}
    database: 0
    # Namespace prefix for ALL keys this service writes — lets multiple
    # services share a Redis instance without colliding.
    key-prefix: "notification-svc"

    idempotency:
      enabled: false
    rate-limit:
      enabled: false
    dead-letter:
      enabled: false
      max-entries: 1000   # LIST is LTRIM'd to this length

# Operators that just want "all Redis backends on" can use a single
# meta-flag (also off by default) — internally OR'd with each per-feature flag:
notification:
  redis:
    enabled: false
```

### Key namespacing

Every key the module writes is prefixed:

```
{notification.redis.key-prefix}:idempotency:{tenantId}:{callerId}:{idempotencyKey}
{notification.redis.key-prefix}:ratelimit:{tenantId}:{callerId}:{channel}
{notification.redis.key-prefix}:dlq                   (single LIST)
```

Default prefix is `notification-svc`. Operators sharing one Redis
across services should set distinct prefixes so a key collision
between e.g. `notification-svc:idempotency:acme:billing:order-1` and
some other service's keys is impossible.

### Library choices

**Spring Data Redis** for the connection layer rather than raw
Lettuce. Reasons:
- Auto-configuration of `LettuceConnectionFactory` from
  `spring.data.redis.*` properties (we still expose
  `notification.redis.*` aliases for clarity in this codebase).
- `RedisTemplate<String, byte[]>` covers idempotency + DLQ payloads
  with one bean rather than maintaining two custom serializers.
- Spring Data already on the dep tree if persistence-utils ever
  expands; cheap consistency.

**bucket4j-redis** with the Lettuce backend for rate limiting.
Reasons:
- Same Bucket4j API as `notification-core`'s `Bucket4jRateLimiter` —
  just a different `BucketProxyManager`. Behaviour is identical from
  the consumer's perspective; only the persistence boundary moves.
- Lettuce backend reuses the same `RedisClient` instance Spring Data
  configures, so we don't duplicate connection pools.
- Avoids pulling Redisson (which is large and has its own opinions).

### Per-feature implementation notes

#### `RedisIdempotencyStore`

Each `IdempotencyKey` maps to a single Redis key holding a JSON-
serialized `IdempotencyRecord`. The DD-10 contract:

| Operation | Redis command |
|-----------|---------------|
| `findExisting(key)` | `GET k` (returns `Optional.empty()` on nil) |
| `markInProgress(key, requestId)` | `SET k <json> NX EX <ttl>` — atomic claim. Returns false on collision. |
| `markComplete(key, response)` | `SET k <json> EX <ttl>` (overwrites; preserves notificationId from prior IN_PROGRESS via the JSON) |

`SET ... NX EX` is the standard atomic-claim idiom. TTL is set on the
key itself, so Redis evicts naturally — no separate sweeper needed.

#### `RedisRateLimiter`

Bucket4j's `LettuceBasedProxyManager` exposes `getProxy(byte[] key,
Supplier<BucketConfiguration> config)` which returns a `Bucket` that
transparently round-trips state through Redis. The override-resolution
logic (most-specific-wins) is identical to `Bucket4jRateLimiter`'s,
extracted into a shared helper so both implementations share it.

The bucket-cache concern from DD-12's review feedback (memory leak via
unbounded `(tenant, caller, channel)` map) doesn't apply here — Redis
is the persistence layer, the local map is just a thin handle cache,
and we keep the same Caffeine-bounded cache pattern as the in-memory
impl.

#### `RedisDeadLetterStore`

Single Redis LIST under `<prefix>:dlq`, with each entry a
JSON-serialized `DeadLetterEntry`. The DD-13 contract:

| Operation | Redis command(s) |
|-----------|------------------|
| `add(entry)` | `LPUSH dlq <json>` then `LTRIM dlq 0 (max-entries-1)` to bound |
| `snapshot()` | `LRANGE dlq 0 (limit-1)` returns most-recent-first |
| `size()` | `LLEN dlq` |

`LPUSH + LTRIM` is the canonical "bounded recent-N list" pattern in
Redis — it's atomic-enough (each command is atomic; the pair is
race-tolerant for our use case where slight overshoot doesn't matter).

### Testing

Each implementation has a Testcontainers-backed integration test:

- One `@Testcontainers` Redis container shared across the test class
  (Bitnami's `redis:7-alpine`, ~30 MB).
- Tests assert the same SPI contracts the in-memory tests cover —
  no new contract, just a different backend.
- `@DynamicPropertySource` wires the container's mapped port into
  Spring's connection-factory autoconfig.

Running these tests requires Docker on the build host. CI already
has Docker available for the existing release-time checks; the
Testcontainers tests slot in without infrastructure changes.

## Reasoning

### Why a single new module rather than three

The three implementations share:
- Connection / serialization / key-prefix concerns.
- Testcontainers fixture.
- Property layout (`notification.redis.*`).

Splitting into three modules (`notification-idempotency-redis`,
`notification-ratelimit-redis`, `notification-dlq-redis`) would
duplicate boilerplate and force operators to pull three deps to get
"distributed mode". One module with three independently-toggleable
beans is the right granularity for the most common use case.

### Why opt-in per backend (not all-or-nothing)

A common deployment shape is "production Redis for state that needs
sharing, in-memory for development convenience". An operator should
be able to flip on `notification.redis.idempotency.enabled=true`
without also flipping rate-limit and DLQ — they may have separate
Redis instances per concern, or want to migrate one at a time.

### Why Spring Data Redis instead of raw Lettuce

Spring Data Redis adds ~200 KB and a thin abstraction over Lettuce.
The abstraction earns its weight when:
- Tests use the `@DynamicPropertySource` connection-factory wiring
  rather than building a `RedisClient` by hand.
- Future enhancements (Sentinel, Cluster) plug in via existing
  `spring.data.redis.sentinel.*` / `spring.data.redis.cluster.*`
  properties.
- Connection-pool tuning is one well-documented surface, not three.

### Why JSON serialization for IdempotencyRecord and DeadLetterEntry

Considered: Java native serialization, Kryo, Protobuf.

JSON via Jackson:
- Already on the classpath (used by REST controllers).
- Human-readable when an operator runs `redis-cli GET <key>` to
  debug.
- Schema evolution is forgiving — new fields don't break old readers.

Costs:
- ~3-5× larger than binary formats. Acceptable: idempotency records
  are typically <1 KB, and DLQ holds ~1k entries by default.
- Slightly slower (~10 µs per key). Negligible relative to the
  network round-trip Redis adds.

### Why not also bundle a Redis-backed CallerRegistry?

DD-11's `CallerRegistry` is a **read-only operator config** —
populated from YAML at startup, never mutated by request flow. There's
nothing to share across pods. The in-memory impl is correct.

### Why bucket4j-redis specifically (not a custom Lua-script
limiter)

We could write a Lua-INCR-EXPIRE rate limiter in 50 lines. Bucket4j
already does it correctly with extensive battle testing — same API as
the in-memory impl, same semantics, same test coverage we already
have for `Bucket4jRateLimiter`. Reinventing this would be a
maintenance liability.

## Consequences

### Positive

- Multi-pod deployments get correct shared state for idempotency,
  rate limit, and DLQ.
- The existing in-memory defaults remain — single-pod / dev
  deployments are unaffected.
- Each backend toggleable independently lets operators migrate
  incrementally.
- All three implementations share one connection, one serializer,
  one Testcontainers fixture.

### Negative

- One more module to release. Mitigated: the module follows the
  same release process as everything else — `mvn deploy` from the
  reactor.
- Redis is now a deployment dependency for distributed mode.
  Operators that don't want it just don't enable it.
- Testcontainers requires Docker on the build host. CI already has
  it; the test profile can be skipped locally with
  `-DskipITs=true`.

### Migration path

| Phase | Action                                                      |
|-------|-------------------------------------------------------------|
| Now   | DD-14 ships, `notification-redis` available as opt-in dep   |
| +T1   | Operator pulls `notification-redis` + sets per-feature flags|
| +T2   | Run with both in-memory + Redis briefly (idempotency moved) |
| +T3   | Promote rate-limit + DLQ to Redis once confident            |

## Out of scope

- **Redis Cluster / Sentinel autoconfigure.** Spring Data Redis
  supports both via `spring.data.redis.sentinel.*` /
  `spring.data.redis.cluster.*` — operators can use those today
  without further changes here.
- **Replication-aware idempotency semantics.** Current impl uses
  `SET NX` which is correct for single-master Redis. A formal proof
  for failover scenarios is its own DD.
- **DLQ replay endpoint.** Tracked in `docs/PROGRESS.md` Phase 10+
  separately from the storage backend.
- **Cross-region active-active.** That's a much bigger architecture
  conversation than swapping a SPI implementation.

## Related Decisions

- [10-idempotency.md](./10-idempotency.md) — `IdempotencyStore` SPI.
- [12-rate-limiting.md](./12-rate-limiting.md) — `RateLimiter` SPI.
- [13-retries-and-dlq.md](./13-retries-and-dlq.md) — `DeadLetterStore` SPI.
