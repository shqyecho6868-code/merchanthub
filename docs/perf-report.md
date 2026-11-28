# Load test report

Three rounds: a single-laptop baseline, a cloud run (capacity, scale-out, instance failure), and a cloud re-run of the overload
scenario after fixing the outbox safety net. Raw outputs live in `perf/results/<RUN_ID>/` (not committed); each section gives the command to reproduce it.

## 1. Local baseline (single laptop)

### 1.1 Setup

| Item | Value |
|---|---|
| Machine | Apple M4 (10 cores) / 16 GB; Docker Desktop VM 10 CPU / 8 GB |
| Deployment | 5 services (`perf/start-services.sh`) + all middleware (docker compose) on the same machine |
| Tooling | k6 1.2.3 (Docker) through the gateway (`MODE=gateway`); each round runs `perf/round.sh`: reset data → sample → load → `check.sh` → top SQL |
| Key config | Hikari default 10 connections; MySQL 8.0 defaults (`innodb_flush_log_at_trx_commit=1`, `sync_binlog=1`: two fsyncs per commit); merchant 901's rate limit raised for S2a/S2b/S3, S1 uses the default 20 QPS |
| Data | `perf/data/seed.sh`: merchant 901 (10 SKUs × 1M stock), 902 (1 SKU × 100k), 200 buyers |

### 1.2 Scenarios

| Scenario | Question | Load | Expected |
|---|---|---|---|
| **S1 rate limit** | Under too much traffic, what gets rejected, and does a rejected request leave a broken order behind? | merchant 901 at **100 orders/s** (limit 20) + merchant 902 at **5 orders/s**, 1 min | 901 passes ~20/s, the rest get a fast 429; 902 unaffected; **k6 successes = orders in DB** |
| **S2a hot row** | When every request hits the same stock row, how much slower is checkout and where is the bottleneck? | SKU 90101 only, vs. a control spread over 10 SKUs; steps 20 → 50 → 100 → 200 orders/s, 1 min each | find the knee; hot P99 clearly above control |
| **S2b sell-out** | Stock 500, 2000 concurrent buyers: exactly 500 sold, never more? | 2000 orders from 200 buyers as fast as possible | exactly **500** succeed, the other 1500 are "out of stock", no 5xx / timeouts |
| **S3 duplicate callback** | Under load, the same payment callback arrives 3 times concurrently: does it take effect once? | 20 transactions/s × 1 min, each callback sent 3× in parallel | PAID = transactions, DUPLICATE = 2 × transactions |

### 1.3 Results

| Scenario | Result | P50 / P99 | Errors | Data check |
|---|---|---|---|---|
| **S1 rate limit** | 901: **1200 / 6001 passed (exactly 20/s)**, 4801 × 429; 902: **301 / 301** | 901: 2.5 / 71.6 ms (429 returned at the entry); 902: 22.7 / 85.8 ms | 0 | ✅ k6 successes = orders in DB (1200, 301), no dangling reservations |
| **S2a hot** (1 SKU) | holds 200/s; ceiling **~240 orders/s** (300 / 400 offered → 240 / 225 completed) | 200/s: 23.8 / 231 ms; 300/s: 1557 / 2746 ms | 0 | ✅ |
| **S2a control** (10 SKUs) | stable at 300/s; 400/s offered → **385** | 300/s: 28.8 / 204 ms; 400/s: 622 / 1618 ms | 0 | ✅ |
| **S2b sell-out** | **exactly 500** succeeded, **1500 "out of stock"**, stock left 0 | 394 / 1569 ms (244 req/s) | 0 | ✅ no overselling |
| **S3 duplicate callback** | 1200 transactions: **PAID 1200 / DUPLICATE 2400** | callback 5.5 / 29.8 ms | 0 | ✅ 1200 paid orders, 1200 successful payments, 1200 confirmed reservations |

Reproduce: `./perf/round.sh <RUN_ID> perf/k6/scenarios/<script>`; S2a ceiling with `-e STAGES=200,300,400 -e STEP=30s`, control with `-e HOT=0`; S2b with `HOT_STOCK=500`.

One first-round avalanche in S2a (P99 2.4 s, 8 Feign timeouts, 4 dangling reservations) did not reproduce in two reruns. A single transaction holding the hot row lock stalled for seconds, queued requests then held all 10 product connections. Takeaway: at 100–200/s the hot row is close enough to its limit that a small hiccup can snowball. The 4 dangling reservations were released by the reservation scan, as designed.

### 1.4 Bottleneck

| Symptom | Evidence | Conclusion |
|---|---|---|
| Hot SKU flat at ~240 orders/s | the same load on 10 SKUs is fine; on the hot row the stock UPDATE averages 27 ms (mostly lock wait), COMMIT 3.6–6.8 ms | **row-lock serialization**: one transaction at a time on the row, and the lock is held until COMMIT (fsync) ≈ 4 ms → ~250/s |
| 10 SKUs stop at 385 orders/s | order Hikari pending 207, active 10/10, all 200 Tomcat threads busy; COMMIT is the top SQL by time; order does 3 commits per order (order tx + outbox claim + mark sent) | looks like the **connection pool**, root cause is **commit fsync** in the database |

Service CPU never exceeded 16% and GC pauses were tens of ms: time is spent waiting (row lock, fsync, connection), and the only shared, serialized resource on the checkout path is the database.

### 1.5 Before / after: bigger connection pool (10 → 30) — **worse, rolled back**

| Metric | Pool 10 | Pool 30 |
|---|---|---|
| Control at 400/s, completed | 385 | **368 / 317** (two runs) |
| Control at 400/s, P99 | 1618 ms | 1793 / 3220 ms |
| order COMMIT avg | 6.1 ms | **15.6 ms** |
| Stock UPDATE avg (control) | 1.9 ms | **27 ms** |
| Total row-lock wait (control) | 59 s | **890 s** |
| Hot 200/s P99 | 231–652 ms | **1792 ms**, plus 3936 requests fast-failed by the circuit breaker |

With 10 connections the excess requests wait in the application; with 30 they wait inside the database, where each extra concurrent transaction adds lock contention and log to flush, so every commit gets slower. The pool queue was a symptom. This matches HikariCP's *About Pool Sizing*. Rolled back to 10, with a comment in both services' `application.yml`.

What would actually raise the hot-row ceiling (not done): stock buckets (split a SKU into N rows), per-SKU queueing in the application, batching reservations into one commit, fewer commits per order.

### 1.6 Laptop vs. production

The absolute numbers don't transfer, the method does. Production has far more data (slower SQL) and a network hop between app and DB (longer lock hold → lower hot-row ceiling), but faster disks (shorter commits) and no CPU contention with middleware. More instances do not raise the hot-row ceiling. Real capacity has to be measured on production-like infrastructure, which is what the next section does.

## 2. Cloud run (AWS us-east-1)

One Fargate task per service (0.5 vCPU / 1 GB), RDS db.t4g.micro (`max_connections` = 60). k6 runs inside the VPC on the middleware EC2 (RTT 4 ms) and goes through ALB → gateway → order, like real users; a laptop → us-east-1 run was unreliable (the home network blocked the traffic mid-test).

### 2.1 Single-instance capacity

| Scenario | Load | Completed QPS | P50 / P99 | Error rate |
|---|---|---|---|---|
| Warm-up | 30/s × 1 min | 30 | 364 / 1009 ms | 0 |
| Spread over 10 SKUs | steps 50 / 100 / 200 / 300 | 47 / 45 / 48 / 55 (**caps at ~50**) | already 4.7 s / 8.9 s at 50 | 0.5% → 11.5% (10 s timeouts) |
| Hot SKU | steps 50 / 100 | 50 / **81** | 408 / 1968 ms; 5.0 s / 7.4 s at 100 | 0 |

**The bottleneck is order's CPU, not the database**: order CPU 100% throughout, product-inventory 40–57%, gateway 26–37%, RDS CPU 13–23%, RDS write latency 0.5–0.8 ms. Locally the database was the limit (spare CPU, slow fsync); in the cloud RDS commits in < 1 ms, so the row lock is short and the 0.5 vCPU app saturates first. The hot run beating the spread run is mostly JIT warm-up (it ran second); both are CPU-bound.

### 2.2 Scale order 1 → 3

| | 1 order | 3 orders |
|---|---|---|
| Ceiling (completed QPS) | ~80 | **~110–118** |
| Ratio | 1 | **~1.4×, far from 3×** |

From `update-service --desired-count 3` to traffic on the new tasks: **~2 min** (image pulled and container running at 28 s; Spring needs 81–85 s on 0.5 vCPU, then Nacos registration and the gateway's instance cache).

Why not 3×:
1. **Downstream not scaled**: product-inventory (88% CPU) and gateway are still single instances on every order's path
2. **RDS connections at the limit**: 58 / 60 with 3 orders (10 per Hikari pool); the check script then hit `Too many connections`. Scaling needs `instances × pool size ≤ max_connections` (or RDS Proxy / a bigger instance)
3. **Congestion collapse leftovers**: an earlier sustained 200/s (2.5× capacity, no load shedding) made almost every request time out; after scaling, the old instance kept serving requests clients had already abandoned

Lessons: scale the whole path and budget DB connections first; shed load at the entry (fast 429) instead of queueing until everything times out.

### 2.3 Failure drill: stop 1 of 3 order instances at 60 orders/s

| Time | Event |
|---|---|
| T+0 | `stop-task`, ECS sends SIGTERM |
| T+3 s | that instance's fast-path outbox send fails (MQ client shutting down); the rows stay in `order_outbox` |
| T+6 s | graceful shutdown done: deregistered from Nacos, Hikari closed |
| T+0 … T+40 s | **385 errors (2.67% of the run)**, then 0 |
| T+1 min 40 s | ECS starts the replacement task |

The ~40 s error window ≈ the gateway's Spring Cloud LoadBalancer instance cache (35 s default) still routing to the stopped instance. The remaining 2 instances carried 60/s, so traffic recovered before the replacement came up.

### 2.4 Data check after all cloud runs (`perf/cloud/check.sh`)

- ✅ no overselling; stock reconciles (initial − available = reserved + confirmed); one successful payment per order; amount paid = amount due
- ✅ **no lost orders**: 89,919 orders in the DB ≥ 55,752 k6 successes (the extra ~34k were committed server-side after k6 had timed out)
- ❌ **~5,400 orders closed but their reservations still held**, and **129,087 outbox rows never sent** (P53). The fast path drops sends when its pool is full, by design, relying on the compensation scan — which is scheduled by XXL-Job, and XXL-Job had not been deployed in the cloud. Nothing was lost (rows are in RDS), but stock stayed frozen and nothing alerted. Fixed and re-verified in section 3

## 3. Cloud re-run: outbox safety net (after the fix)

Fix (P56): XXL-Job deployed in the cloud; fast-path drops counted and logged; an in-process monitor (Spring `@Scheduled`, independent of XXL-Job) reports the stale backlog to CloudWatch and runs the scan itself when the backlog stops shrinking; alarm on "pending outbox rows older than 5 min > 0"; the scan catches up with larger batches in parallel.

Load: `s4-steady` at **150 orders/s** (~2× single-instance capacity) from inside the VPC; backlog sampled every 10 s.

| Round | Condition | Backlog peak | How it drained | Time to drain | Alarm |
|---|---|---|---|---|---|
| R1 | XXL-Job up, 150/s × 4 min | **21,327** (~21k fast-path drops) | XXL-Job scan in catch-up mode: **18 rows/s** while overloaded (competes with checkout for CPU and DB connections), **414 rows/s** once load ended | **~80 s** after order finished queued requests | fired (rows stale > 5 min during overload), recovered 1 min later |
| R2 | **XXL-Job admin stopped**, 150/s × 2 min | **18,696**, flat for 3 min (nobody sending) | monitor: 2 rounds without progress → fallback scan, 14,100 rows in 45 s; the remaining 4,596 waited 2 more rounds | **4 min 20 s** from first fallback | fired → OK |
| R3 | same as R2, with "keep falling back while still stale" | 9,111 | fallback 7,100 rows → next round continued with the remaining 1,679 | **1 min 52 s** | fired → OK |

- **129k rows now**: 129,087 ÷ 414 rows/s ≈ **5 min** (before: 100 rows every 5 s ≈ 1.8 h). Only after the overload ends: during overload the safety net can guarantee catching up later, not "no backlog"; that needs load shedding at the entry
- **Data check** (after all orders passed pay timeout + grace): **all checks pass**; outbox undelivered **0**, orders past deadline still pending **0**, closed orders with reservations held **0** (27,520 orders)
- **Reconciliation**: 75 dangling reservations (reserved, but the order failed because the inventory call tripped the circuit breaker) were all released by reservation recon; 0 left
- **Alarm**: fired and recovered in all three rounds; right after deploy it was in ALARM until order started reporting (missing data counts as breaching, so a silent monitor also pages)
- **Dashboard gaps**: a few minutes without data points: the monitor uses `fixedDelay`, and slow rounds (COUNT waiting for a connection, the fallback scan running in the same round) push the next report back. Known issue: move the fallback to its own thread and report at a fixed rate
