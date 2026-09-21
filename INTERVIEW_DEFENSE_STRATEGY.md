# Interview Defense Strategy

Talking points for defending the design choices in this project, mapped to the four areas requested.

---

## 1. Internal execution path: `@Cacheable` proxy → `RedisCache` → Lettuce

1. **Proxy creation.** `@EnableCaching` triggers Spring to post-process any bean containing
   `@Cacheable`/`@CachePut`/`@CacheEvict` and wrap it in a CGLIB (class-based) or JDK dynamic
   proxy, exactly like `@Transactional`. This is why **self-invocation doesn't work** —
   calling `this.getProductAnnotated(id)` from another method in the same class bypasses the
   proxy entirely and skips caching. `InventoryController` calls through the injected bean, so
   it always goes through the proxy.
2. **Interception.** `CacheInterceptor` (an `AbstractCacheInvoker`) intercepts the call, builds
   a `CacheOperationContext` from the annotation's `cacheNames`, SpEL `key`/`condition`/`unless`
   expressions, evaluates the key (here: `#productId`), and asks the configured `CacheManager`
   for the named `Cache`.
3. **`CacheManager` → `RedisCache` lookup.** Our `@Primary` bean is a `RedisCacheManager`; its
   `getCache("product-details")` returns a `RedisCache` configured with the specific
   `RedisCacheConfiguration` we registered for that name (10 min TTL, jittered) — this is why
   per-cache-name TTLs work: each logical cache gets its own `RedisCacheConfiguration`, not one
   global setting.
4. **`RedisCache` → `RedisCacheWriter` → Lettuce.** `RedisCache.get()`/`put()` delegate to our
   `JitteringRedisCacheWriter`, which decorates the standard `RedisCacheWriter` — serializes the
   key with `StringRedisSerializer`, the value with `GenericJackson2JsonRedisSerializer`, and
   issues a `GET`/`SET ... PX <jittered-ttl-ms>` via the `RedisConnection` obtained from the
   `LettuceConnectionFactory`. Lettuce itself is a **netty-based, asynchronous, thread-safe**
   driver — a single shared `StatefulRedisConnection` can be multiplexed across all Spring app
   threads, unlike Jedis which traditionally needed a connection pool per thread. The `lettuce.pool`
   block in `application.yml` is technically a `GenericObjectPool` around Lettuce connections used
   by `LettuceConnectionFactory` in non-pooled-by-default scenarios — worth knowing that Lettuce
   doesn't *require* pooling the way Jedis does, and over-pooling Lettuce connections is a common
   over-engineering mistake.
5. **On hit:** the deserialized value is returned directly; the target method body never runs.
   **On miss:** the target method executes, the interceptor calls `RedisCache.put()` with the
   result (respecting `unless`), then returns it to the caller.

---

## 2. Redis eviction policies — `volatile-lru`, `allkeys-lru`, `allkeys-lfu`

| Policy | Behavior when `maxmemory` is hit | Fits this project when... |
|---|---|---|
| `noeviction` | Rejects writes with an error | Never for a cache — only for a system-of-record Redis use case |
| `allkeys-lru` | Evicts the least-recently-used key across **all** keys, regardless of TTL | **Chosen default here.** Every key we write (`product-details`, `stock:hash:*`, `trending:products`) is either cache data or reconstructable, so it's safe to let Redis evict *anything* under memory pressure, not just keys with a TTL. Good general-purpose choice for a pure-cache Redis instance.
| `volatile-lru` | Evicts LRU **only among keys that have a TTL set** | Use instead of `allkeys-lru` if the *same* Redis instance also holds some keys that must never be evicted (e.g. a session store or a queue) sharing the instance with the cache — not our case, but a common trap: mixing cache and non-cache data on one Redis without `volatile-lru` risks evicting data that should be permanent.
| `allkeys-lfu` / `volatile-lfu` | Evicts by **access frequency** rather than recency | Better than LRU for `product-details` specifically if the traffic pattern is "small set of perennially popular SKUs get hammered constantly, long tail gets occasional bursts" — LRU can be fooled by a single burst of long-tail scans evicting your genuinely hot keys; LFU resists that. Worth proposing as an **upgrade** once you have real production access-pattern data, with the caveat that LFU's counters have their own decay tuning (`lfu-log-factor`, `lfu-decay-time`) that needs monitoring.

**The actual interview answer:** start with `allkeys-lru` + `maxmemory` sized to your working
set with headroom, instrument `evicted_keys` and `keyspace_hits`/`keyspace_misses` via
`INFO stats` (exposed here through Actuator's Redis health indicator + Micrometer), and
graduate to `allkeys-lfu` only if data shows LRU is evicting keys that get re-requested
shortly after — i.e., don't guess, measure, then switch.

---

## 3. Single-threaded event loop vs. Redis 6+ multi-threaded I/O

- Redis's **command execution** has always been, and remains, **single-threaded** — this is
  the property that makes `INCR`, `HINCRBY`, and Lua scripts **atomic** without any
  application-side locking: while Redis is executing one command (or one Lua script start-to-
  finish), no other client's command can interleave. That's exactly why
  `InventoryService.decrementStockAtomic()` uses `HINCRBY` instead of "GET, subtract in Java,
  SET" — the latter is a classic read-modify-write race under concurrent order processing;
  `HINCRBY` is race-free by construction because it never leaves Redis.
- What Redis 6+ actually parallelized is **network I/O** — reading raw bytes off the socket and
  writing the response back — via `io-threads` in `redis.conf`. This reduces the CPU cost of
  serving high connection/request counts, but it does **not** change the atomicity guarantee:
  command *execution* against the keyspace is still serialized on the single main thread.
- **Why this matters for our stampede-lock design:** you might ask "why do we need a Redisson
  `RLock` at all if Redis is single-threaded and atomic?" — because our critical section
  (query the DB, which can take tens to hundreds of milliseconds) happens **outside** Redis
  entirely, in the JVM. Redis's single-threaded atomicity protects individual Redis *commands*;
  it says nothing about coordinating a multi-step, cross-service critical section like
  "check cache, miss, hit Postgres, write back" — that's exactly the gap `RLock` (backed by
  `SET NX PX` + a Lua-scripted watchdog-renewal, itself atomic Redis operations) closes.

---

## 4. RDB vs AOF vs Cluster/Sentinel for HA

- **RDB (snapshotting):** point-in-time binary dumps at an interval (`save 900 1` etc.).
  Fast restarts, compact files, but you lose everything written since the last snapshot on a
  crash. For a **pure cache** like this project's `product-details`/`stock:hash:*` data —
  where Postgres/JPA is the actual source of truth and Redis is disposable — **losing a few
  minutes of cache on crash is a non-event**: the next read just falls through to the DB via
  our L1→L2→DB path and repopulates. This is the strongest argument for **RDB-only, no AOF**
  on the caching Redis instance in this architecture specifically.
- **AOF (append-only file):** logs every write command; replay on restart. Much better
  durability (`appendfsync everysec` loses ≤1s of writes; `always` loses none but tanks
  throughput), at the cost of larger files and slower restarts (replaying the log). Justified
  when Redis holds data with **no other source of truth** — e.g. if we later used Redis as the
  primary store for `trending:products` scores with no DB backup of view/purchase events, AOF
  would be the right call for that specific dataset, potentially via a **second, differently-
  configured Redis instance** rather than forcing one persistence policy on everything.
- **Sentinel:** automated failover for a single primary + replica(s) topology — Sentinels
  monitor the primary, agree via quorum that it's down, and promote a replica. Simpler
  operationally than Cluster, and sufficient here since our whole dataset comfortably fits on
  one node (no need for horizontal sharding) — the actual requirement is **availability**, not
  **capacity**. `RedissonConfig` uses `useSingleServer()` for local dev/demo; swapping to
  `useSentinelServers()` (pointing at the Sentinel quorum, not the primary directly) is a
  config-only change, no application code changes, because Redisson's `RLock`/`RBloomFilter`/
  `RScoredSortedSet` APIs are topology-agnostic.
- **Cluster:** shards keyspace across multiple primaries via hash slots, each optionally with
  its own replica(s) for HA. Justified once the working set genuinely exceeds one node's
  memory/throughput ceiling — **not** justified purely for HA when Sentinel already solves
  failover, and it adds real complexity (multi-key operations must hash to the same slot,
  which affects our `MGET`-style batch product lookups and would require hash tags like
  `{product}:id1`, `{product}:id2` to keep related keys co-located).
- **Recommendation for this project as specified:** Sentinel + RDB (no AOF, or AOF with
  `everysec` if leadership wants belt-and-suspenders) is the right default. Move to Cluster
  only when metrics show a single primary is memory- or throughput-constrained, not
  preemptively.
