# Inventory & Catalog Service — Multi-Tier Redis Caching Reference

Spring Boot 3.3 / Java 21 reference implementation of an e-commerce inventory/catalog service,
built to demonstrate a production-grade L1 (Caffeine) + L2 (Redis) caching architecture with
stampede protection, penetration protection, and graceful degradation on Redis outage.

## Run locally

```bash
docker run -d --name redis -p 6379:6379 redis:7-alpine
mvn spring-boot:run
```

App starts on `http://localhost:8080`. H2 in-memory DB, so no additional setup needed.

## Run tests

```bash
mvn test
```

The integration test (`L1L2CacheIntegrationTest`) spins up a real `redis:7-alpine` container via
Testcontainers — Docker must be available to the test runner.

## Project layout

```
src/main/java/com/publicissapient/inventory/
├── config/
│   ├── CacheConfig.java        # RedisCacheManager (custom serializers, per-cache TTL+jitter),
│   │                           # Caffeine L1 manager, CacheErrorHandler, RedisTemplate
│   ├── RedissonConfig.java     # RLock / RBloomFilter / RScoredSortedSet client
│   └── PubSubConfig.java       # RedisMessageListenerContainer wiring
├── model/Product.java
├── repository/ProductRepository.java
├── pubsub/
│   ├── CacheInvalidationMessage.java
│   ├── CacheInvalidationPublisher.java
│   └── RedisPubSubListener.java
├── service/
│   ├── L1L2CacheService.java   # L1 -> L2 -> DB -> backfill, stampede lock, bloom filter
│   └── InventoryService.java   # @Cacheable/@CachePut/@CacheEvict, Redis Hash, ZSET trending
├── controller/InventoryController.java
└── exception/GlobalExceptionHandler.java
```

## Key endpoints

| Method | Path | Demonstrates |
|---|---|---|
| GET | `/api/v1/products/{id}` | Full L1→L2→DB composite path (stampede + bloom filter protected) |
| GET | `/api/v1/products/annotated/{id}` | `@Cacheable` against L2 only |
| POST | `/api/v1/products` | Write-through: DB + both cache layers + cross-instance invalidation |
| PATCH | `/api/v1/products/{id}/stock?newStockLevel=N` | Redis Hash (`HSET`) partial update |
| POST | `/api/v1/products/{id}/stock/decrement?quantity=N` | Atomic `HINCRBY` |
| POST | `/api/v1/products/{id}/purchase?quantity=N` | `ZINCRBY` trending score |
| GET | `/api/v1/products/trending?topN=10` | `ZREVRANGE ... WITHSCORES` |

See [`INTERVIEW_DEFENSE_STRATEGY.md`](./INTERVIEW_DEFENSE_STRATEGY.md) for the deep-dive talking
points on `@Cacheable` internals, eviction policies, Redis threading model, and RDB/AOF/Cluster
trade-offs.

## Notes / things to point out unprompted in an interview

- **No JDK serialization anywhere** — `GenericJackson2JsonRedisSerializer` with an explicitly
  configured `ObjectMapper`, not Spring's wide-open default typing.
- **TTL jitter is per-write**, not per-cache-name-at-startup — see `JitteringRedisCacheWriter`
  in `CacheConfig`. That's the difference between "kind of helps" and "actually prevents
  avalanche."
- **Two independent Redis outage defenses**, deliberately layered: `CacheErrorHandler` (for the
  annotation-driven `@Cacheable` path) and a Resilience4j `@CircuitBreaker` with fallback (for
  the manual `L1L2CacheService` path) — both degrade to "read the DB directly," neither throws.
- **Bloom filter is checked before Redis is even touched** — that's what actually stops
  penetration attacks, not the negative-cache-with-short-TTL layer underneath it (that second
  layer only protects against *legitimate* dead IDs, not a deliberate scan of random IDs).
