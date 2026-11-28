# Pitfalls (selected)

Nine problems worth retelling, out of 56 recorded while building the project. Numbers are kept from the full log.
Each entry: Symptom → Root cause → Fix.

### P1 · Snowflake workerId was always 0
- **Symptom**: with several instances, generated IDs could collide
- **Root cause**: `@Value` was put on a `static` field. Spring does not inject static fields, so the generator was built with workerId 0 at class-load time; another utility hard-coded workerId 1
- **Fix**: one shared ID generator; each instance leases a workerId slot in Redis at startup and renews it (see P41)

### P17 · Transactional-message check-back answered by "someone else", and earlier than configured
- **Symptom**: a test asserting "our listener received the check-back" failed, although the message had been committed and delivered; the first check-back came ~1.9 s after the half message, below the broker's 3 s minimum
- **Root cause**: ① the broker sends the check-back to **any live instance of the same producer group**, not necessarily the sender (another cached Spring context in the same JVM was in that group); ② the "too new to check" window is computed with the broker's clock against the producer's `bornTimestamp`, so clock skew shifts it
- **Fix**: a dedicated producer group for the test; on check-back, if the order is not found and the message is younger than `rollback-min-age-seconds`, answer UNKNOW instead of ROLLBACK. In production this means every instance must be able to answer for any half message, from the shared database only

### P19 · 10 concurrent duplicate callbacks produced 11 payment events
- **Symptom**: expected 1 PAYMENT_EVENT, got 11; the extras carried `TRANSACTION_CHECK_TIMES=1`
- **Root cause**: the 9 losers of the CAS sent ROLLBACK, but COMMIT/ROLLBACK are oneway and race with check-back. The check-back only verified "payment succeeded with this payNo", which is also true for the losers' half messages, so they were all committed
- **Fix**: each callback attempt gets an attempt ID in the message; the winning CAS stores it in `payment.success_attempt`, and check-back commits only the matching attempt. The winner's message may still arrive twice (RocketMQ is at-least-once); consumer dedupe by eventId covers that. Lesson: check-back must decide "did *this* local transaction commit", not "what is the business state now"

### P22 · Payment vs. timeout race: timeout won, but the payment side recorded DUPLICATE_PAYMENT
- **Symptom**: for an order closed by timeout, the anomaly record said DUPLICATE_PAYMENT with order status 10; expected PAID_AFTER_CLOSED / 50
- **Root cause**: under InnoDB REPEATABLE READ, the plain SELECT after the failed CAS read the transaction's snapshot (status 10), while the UPDATE was a current read and saw the committed 50
- **Fix**: after a failed CAS, read the current state with `SELECT ... FOR UPDATE`. Only a concurrency test that forces the interleaving catches this

### P29 · Feign read timeout configured as 3 s, actually 60 s
- **Symptom**: a fake downstream sleeping 1 s with a 300 ms read timeout returned successfully after 1 s
- **Root cause**: Spring Cloud OpenFeign looks up `spring.cloud.openfeign.client.config.<key>` by **contextId** (it equals `name` only when contextId is not set). The config used the service name, so it never matched and the 60 s default applied — since the first version
- **Fix**: keys changed to the contextId, plus a `default` entry as a floor. Lesson: settings that only matter during incidents (timeouts) need a test proving they take effect

### P41 · After the laptop slept, checkout returned 500
- **Symptom**: "snowflake slot lease lost, refusing to issue IDs"
- **Root cause**: the process was suspended for ~10 min, the Redis lease on the workerId slot expired and could have been taken by another instance. Refusing to issue IDs is the intended fail-safe: two instances with the same workerId would produce duplicate order numbers
- **Fix**: restart to lease a new slot. A production version would re-lease automatically in the background, at the cost of a short unavailability

### P45 · Connection pool queue of 207; a bigger pool made it slower
- **Symptom**: at 400 orders/s, order's Hikari had 207 pending and all Tomcat threads busy; raising the pool 10 → 30 lowered throughput and doubled P99
- **Root cause**: the bottleneck was the database (commit fsync + stock row lock); COMMIT went 6 → 16 ms, the stock UPDATE 1.9 → 27 ms, total row-lock wait 59 s → 890 s, while service CPU stayed ≤ 16%. A bigger pool only moves the queue from the application into the database, where more concurrent transactions make every commit slower
- **Fix**: rolled back to 10. Lesson: when something queues, find out what it is waiting for before adding resources (HikariCP *About Pool Sizing*)

### P54 · RDS `Too many connections` after scaling order to 3
- **Symptom**: after scale-out, even the check script could not connect (`ERROR 1040`)
- **Root cause**: db.t4g.micro allows 60 connections; 6 services + 2 extra order instances × Hikari 10 peaked at 58. The same thing happened again when the XXL-Job admin was added with its default pool of 30
- **Fix**: budget `instances × pool size ≤ max_connections − headroom` before scaling (or a bigger instance / RDS Proxy); the XXL-Job admin pool is capped at 3

### P56 · Outbox safety net silently dead in the cloud: 129k undelivered events
- **Symptom**: after a cloud load test, 129,087 outbox rows were stuck in status 0 and not decreasing; ~5,400 closed orders still held their stock reservations; nothing in logs or alerts
- **Root cause** (four layers, none fatal alone):
  - **Config drift**: the XXL-Job executor was enabled in the dev profile and disabled in the aws profile, and XXL-Job was never deployed in the cloud. Tests were green locally while every safety net was off in the cloud
  - **Silent drop**: under overload the fast-path pool drops sends (DiscardPolicy, by design: the row is committed and the scan resends it) — with no log or metric
  - **Single point**: outbox resend, timeout close and reservation recon all depended on the same scheduler, so one gap disabled all three
  - **No alarm**: nothing checked that "eventually consistent" ever became consistent
- **Fix**:
  - XXL-Job admin deployed in the cloud (compose on the middleware EC2, schema on RDS), executors enabled
  - Drops counted (`outbox.fastpath.dropped`) and logged once per second
  - `OutboxBacklogMonitor` inside order on Spring `@Scheduled` (deliberately not on XXL-Job) reports the stale backlog to CloudWatch, and runs the scan itself when the backlog stops shrinking
  - CloudWatch alarm on "rows pending > 5 min > 0", with missing data treated as breaching
  - The scan catches up with batches of 1000 on 4 threads
- **Verification** (cloud, 150 orders/s ≈ 2× capacity):
  - With XXL-Job up: a backlog of 21k drained ~80 s after load ended (414 rows/s, so 129k ≈ 5 min, vs. 1.8 h before)
  - With XXL-Job stopped: the monitor took over and drained it (4 min 20 s, then 1 min 52 s after making the fallback continue while rows are still stale)
  - The alarm fired and recovered in every round
  - Data check: 0 undelivered, 0 expired-pending, 0 closed-but-reserved
- **Lessons**:
  - Before removing a component, list everything that depends on it
  - A design that is allowed to drop must make drops visible
  - A watchdog must not be scheduled by what it watches
  - Under overload the safety net competes with checkout for CPU and connections; it guarantees catching up afterwards, not "no backlog" (that needs load shedding)
