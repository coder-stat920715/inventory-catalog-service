# Testing Guide — Inventory & Catalog Service

Companion to `postman/Inventory-Catalog-Service.postman_collection.json`. Each section below
maps a Postman folder/request to the specific caching mechanism it exercises, and tells you
exactly what to look at (console logs, `redis-cli`, Actuator) to confirm it actually worked —
not just that you got a 200.

## 0. Prerequisites

1. **Redis** running locally:
   ```bash
   docker run -d --name redis -p 6379:6379 redis:7-alpine
   ```
2. **Redis CLI** available for verification (either `docker exec -it redis redis-cli`, or a
   local `redis-cli` pointed at `localhost:6379`).
3. **Application** running with DEBUG logging (already the default in `application.yml`):
   ```bash
   mvn spring-boot:run
   ```
   Keep this terminal visible — most of the tests below are confirmed by watching log lines,
   not just HTTP status codes.
4. **Postman**: import both files from `postman/` — the collection and the environment — then
   select the "Inventory Catalog Service - Local" environment in the top-right environment
   dropdown before sending anything.

---

## 1. Basic CRUD + L1/L2 backfill

**Requests:** *1. Product CRUD* folder, in order: Create/Update → Get Product → Get Product
Again → Get Product (annotated) → Get Unknown Product → Delete Product.

| Step | What to send | What to check | Expected |
|---|---|---|---|
| 1 | `Create/Update Product` | HTTP status | `200`, body echoes the product |
| 2 | `Get Product` (1st time) | App console | `CACHE MISS` is NOT logged here — this hits `L1L2CacheService`, not the annotated path, so look instead for the absence of any `readFromL2Fallback` warning, and a 200 with full product JSON |
| 3 | `Get Product Again` (send 3-4×) | App console | No new DB query — Spring Data JPA's `show-sql` is off by default; instead confirm via Actuator (`GET /actuator/metrics/spring.cache.gets?tag=cache:product-details`) that `hit` count increments each call |
| 4 | `Get Product - Annotated` | App console | First call after a fresh cache logs `CACHE MISS (annotated) - loading product 'SKU-001' from DB`; repeat the call — that log line should NOT reappear |
| 5 | `Get Unknown Product` | HTTP status + console | `404`, and console logs `Bloom filter negative for id='does-not-exist-12345' - short-circuiting, no cache/DB hit` |
| 6 | `Delete Product` | HTTP status | `204` |

**redis-cli spot check** (after step 1):
```bash
redis-cli KEYS "product-details*"
redis-cli GET "product-details::SKU-001"     # should return the serialized JSON product
redis-cli TTL "product-details::SKU-001"     # should be ~480-720s (10min ± 20% jitter)
```

---

## 2. TTL jitter (cache avalanche prevention)

**Goal:** prove TTLs are NOT all identical.

1. In Postman, run `Create/Update Product` 10 times in a Postman **Runner** loop, changing
   `productId` each iteration (e.g. `SKU-001` .. `SKU-010`) — easiest way is to duplicate the
   request and override the `productId` variable per call, or use a Runner with a small CSV of
   10 ids.
2. Then:
   ```bash
   redis-cli KEYS "product-details::SKU-0*" | xargs -I{} redis-cli TTL {}
   ```
3. **Expected:** TTL values spread roughly between 480s and 720s (10 min ± 20%), NOT all
   exactly 600. If every TTL is identical, the jitter isn't wired up correctly.

---

## 3. Redis Hash — partial stock updates

**Requests:** *2. Stock - Redis Hash* folder.

1. `Recreate Product` (setup).
2. `Partial Stock Update` (`newStockLevel=180`) → `204`.
3. Verify directly in Redis (bypassing the app entirely):
   ```bash
   redis-cli HGETALL "stock:hash:SKU-001"
   ```
   **Expected:** a hash with `stockLevel = 180` and a `lastUpdated` timestamp — confirming this
   used `HSET`, not a full object rewrite.
4. `Decrement Stock Atomically` (`quantity=5`) → response body `{"remaining": 175}`.
5. Re-run `HGETALL` in `redis-cli` — `stockLevel` should now read `175`, confirming `HINCRBY`
   worked without a read-modify-write round trip from the app.
6. `Get Product` — console should show a fresh DB read (or L2 backfill), because step 2/4
   evicted the full-object cache entry (`evictProduct` is called after every hash write) —
   confirm the returned `stockLevel` in the JSON now matches Redis (`175`).

**Concurrency check (optional, proves HINCRBY is race-free):** fire the decrement request 20
times concurrently with `quantity=1` each, e.g. with Postman Runner (iterations=20, no delay)
or:
```bash
for i in $(seq 1 20); do curl -s -X POST "http://localhost:8080/api/v1/products/SKU-001/stock/decrement?quantity=1" & done; wait
redis-cli HGET "stock:hash:SKU-001" stockLevel
```
**Expected:** final value is exactly `175 - 20 = 155`, no lost updates — this is what
`HINCRBY`'s atomicity guarantees (see Interview Defense Strategy §3).

---

## 4. Redis ZSET — trending products

**Requests:** *3. Trending - ZSET* folder.

1. `Record View` (a plain `GET /products/{id}` already calls `recordProductView`) — send it 2×
   for `SKU-001`.
2. `Record Purchase` with `quantity=2` for `SKU-001` → adds `2 * 5 = 10` to its score (total ≈ 12).
3. Create a second product (`SKU-002`) and send `Record View` once for it (score ≈ 1).
4. `Get Trending Products` (`topN=10`) → **Expected:** JSON array with `SKU-001` ranked above
   `SKU-002`, scores matching the arithmetic above.
5. Direct verification:
   ```bash
   redis-cli ZREVRANGE "trending:products" 0 -1 WITHSCORES
   ```

---

## 5. Cache stampede / thundering herd protection

**Goal:** prove only ONE request reaches the DB when many concurrent requests miss the same
cold key simultaneously.

1. Evict any existing cache entry for the test id (or use a brand-new `productId` you just
   inserted directly via the H2 console / `Create Product`, then manually clear its cache):
   ```bash
   redis-cli DEL "product-details::SKU-STAMPEDE"
   ```
   (Make sure a DB row for `SKU-STAMPEDE` exists — run `Create/Update Product` first, which
   also happens to populate the cache, so instead: create it, then `redis-cli DEL` the L2 key
   and separately restart the app — or simplest: pick an id that's in the DB via a direct SQL
   insert but was never read through the API yet, so L1/L2 are both genuinely cold.)
2. Fire 30 concurrent `GET /api/v1/products/SKU-STAMPEDE` requests at once:
   ```bash
   for i in $(seq 1 30); do curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8080/api/v1/products/SKU-STAMPEDE" & done; wait
   ```
3. **Watch the console.** You should see exactly ONE `Redisson lock` acquisition path execute
   the DB query (there's no explicit "querying DB" log line in the current code — for this
   test it's worth temporarily adding `log.info("DB QUERY for {}", productId)` in
   `queryDbAndBackfill` before running this, or watch H2's query count via
   `spring.jpa.show-sql=true` temporarily). All 30 HTTP responses should still be `200` with
   identical data — the lock makes the *other* 29 requests wait and then read the now-warm
   cache instead of separately hitting the DB.

---

## 6. Cache penetration protection (Bloom filter)

**Requests:** `Get Unknown Product` in the CRUD folder, repeated.

1. Hit `GET /api/v1/products/does-not-exist-12345` 5 times in a row.
2. **Expected console output**, every single time:
   ```
   Bloom filter negative for id='does-not-exist-12345' - short-circuiting, no cache/DB hit
   ```
3. This confirms the request never reaches Redis's `product-not-found` cache OR the database —
   verify with:
   ```bash
   redis-cli EXISTS "product-not-found::does-not-exist-12345"   # should be 0 - never cached, never needed to be
   ```
4. **Negative-cache layer test (the second, different defense):** pick an id that a legitimate
   client might request but genuinely doesn't exist in the DB — one that WAS added to the bloom
   filter at some point (e.g. delete a product you previously created, so its id is still
   bloom-positive but DB-absent):
   ```bash
   # after deleting SKU-001 via the Delete Product request:
   curl http://localhost:8080/api/v1/products/SKU-001   # -> 404
   redis-cli GET "product-not-found::SKU-001"            # should now show a cached sentinel
   ```
   A second request for the same id should be visibly faster (no DB round-trip) — check with
   Postman's response time in the bottom-right of the response panel.

---

## 7. Circuit breaker / graceful degradation on Redis outage

**Goal:** prove the app degrades to direct DB reads instead of returning `500`s when Redis is
unreachable.

1. With the app running and `SKU-001` already cached (`GET /api/v1/products/SKU-001` once to
   warm it), stop Redis:
   ```bash
   docker stop redis
   ```
2. Send `GET /api/v1/products/SKU-001` again via Postman.
   - **Expected:** still `200 OK` with correct data — NOT a `500` or `503`.
   - Console should show a warning like `L2 (Redis) unavailable ... degrading straight to DB`
     from `readFromL2Fallback`, or (once the breaker trips open) calls skip Redis entirely.
3. Send it 10-15 more times in quick succession (enough to exceed
   `minimum-number-of-calls: 10` and `failure-rate-threshold: 50` from `application.yml`).
4. Check breaker state:
   ```
   GET {{baseUrl}}/actuator/circuitbreakers
   ```
   **Expected:** `"state": "OPEN"` for the `redisCache` instance once the failure threshold is
   crossed — confirming Resilience4j tripped the breaker rather than letting every call retry
   a dead connection with its full timeout.
5. Restart Redis:
   ```bash
   docker start redis
   ```
   Wait `wait-duration-in-open-state` (10s per config), then call the endpoint again — breaker
   should move to `HALF_OPEN` then back to `CLOSED` after a few successful calls. Watch
   `GET {{baseUrl}}/actuator/circuitbreakerevents` to see the `STATE_TRANSITION` events in order:
   `CLOSED → OPEN → HALF_OPEN → CLOSED`.
6. Also confirm the **annotation-driven path** degrades independently: call
   `GET /api/v1/products/annotated/SKU-001` while Redis is still down (before step 5) —
   should also return `200`, this time protected by `CacheErrorHandler` rather than the
   circuit breaker, since `@Cacheable` doesn't go through `L1L2CacheService` at all.

---

## 8. Cross-instance L1 invalidation (Pub/Sub)

This is the one scenario a single Postman collection can't fully prove by itself — it needs
**two JVM instances** sharing the same Redis.

1. Start a second instance on a different port, pointing at the same Redis:
   ```bash
   mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
   ```
2. Warm both instances' L1 caches for the same product:
   ```bash
   curl http://localhost:8080/api/v1/products/SKU-001
   curl http://localhost:8081/api/v1/products/SKU-001
   ```
3. Update the product through instance A (port 8080):
   ```bash
   curl -X POST http://localhost:8080/api/v1/products \
     -H "Content-Type: application/json" \
     -d '{"id":"SKU-001","sku":"SKU-001","name":"Wireless Mouse V2","price":29.99,"stockLevel":100}'
   ```
4. Immediately `GET` from instance B (port 8081):
   ```bash
   curl http://localhost:8081/api/v1/products/SKU-001
   ```
   **Expected:** instance B already returns `"name": "Wireless Mouse V2"` — its L1 cache was
   invalidated by the Pub/Sub message from instance A and it fell through to the freshly-
   updated L2 (Redis), NOT a stale local copy. Instance B's console should log
   `L1 evicted cache='product-details' key='SKU-001' (origin=<instance-A-uuid>)`.

---

## 9. Automated integration test (Testcontainers)

For a fully automated version of sections 1, 3, and part of 5/6 that runs in CI without any
manual Postman clicking:
```bash
mvn test -Dtest=L1L2CacheIntegrationTest
```
Requires Docker available to the test JVM (spins up a real `redis:7-alpine` container). See
`src/test/java/.../L1L2CacheIntegrationTest.java` for what's asserted.

---

## Quick reference: what proves what

| Requirement from the brief | Proven in section |
|---|---|
| L1 (Caffeine) + L2 (Redis) multi-level caching | §1 |
| Redis Hashes for partial updates | §3 |
| Redis Sorted Sets for trending | §4 |
| Custom serializers (no JDK serialization) | §1 (`redis-cli GET` returns readable JSON, not Java-serialized bytes) |
| Granular TTL + jitter | §2 |
| Cache stampede mitigation (Redisson RLock) | §5 |
| Cache penetration mitigation (Bloom filter + negative cache) | §6 |
| Circuit breaking / graceful Redis-outage fallback | §7 |
| Cross-instance L1 invalidation via Pub/Sub | §8 |
