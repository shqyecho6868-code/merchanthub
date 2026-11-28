# Architecture

## Services

```mermaid
flowchart LR
    Client[Client / Swagger] --> GW[gateway<br/>auth · identity headers · Sentinel]
    Client -. WebSocket .-> RT[realtime<br/>Netty push]

    GW --> UT[user-tenant<br/>accounts · tenants · Flowable approvals]
    GW --> PI[product-inventory<br/>products · SKUs · stock]
    GW --> OD[order<br/>checkout · state machine · ES query<br/>per-merchant rate limit · Feign circuit breaker]
    GW --> PAY[payment<br/>payments · mock channel · reconciliation<br/>Feign circuit breaker]

    OD -- Feign: price snapshot / reserve stock --> PI
    PI -- Feign: start listing approval --> UT

    MQ[(RocketMQ)]
    OD -- outbox / scheduled messages --> MQ
    PAY -- payment succeeded --> MQ
    UT -- approval result --> MQ
    MQ --> OD
    MQ --> PI
    MQ --> RT
    RT -- routing table --> RDS[(Redis)]

    OD -. binlog / Canal .-> ES[(Elasticsearch)]
    XXL[XXL-Job admin] -. compensation / reconciliation jobs .-> OD
    XXL -.-> PAY
    XXL -.-> PI
    XXL -.-> UT
```

## Checkout + stock reservation

```mermaid
sequenceDiagram
    autonumber
    participant B as Buyer
    participant O as order
    participant PI as product-inventory
    participant DBP as MySQL mh_product
    participant DBO as MySQL mh_order

    B->>O: POST /orders (X-User-Id, items[skuId, qty], expectedPayAmount)
    O->>PI: Feign /internal/skus/query
    PI->>DBP: SELECT sku JOIN product
    PI-->>O: SKU snapshot (price, specs, status, tenant)
    O->>O: check on sale / same merchant, price, compare expectedPayAmount
    O->>O: order_no = snowflake ID (idempotency key of the reservation)
    O->>PI: Feign /internal/inventory/reserve (order_no, tenant, items)
    rect rgb(235, 245, 255)
    Note over PI,DBP: Tx A: all or nothing
    loop by sku_id ascending
        PI->>DBP: INSERT inventory_reservation (uk order_no + sku_id)
        PI->>DBP: UPDATE inventory ... WHERE available >= ?
        alt 0 rows affected
            PI->>PI: throw INVENTORY_INSUFFICIENT → roll back everything
        end
    end
    end
    PI-->>O: OK (a repeated request with the same content is also OK)
    rect rgb(235, 255, 240)
    Note over O,DBO: Tx B
    O->>DBO: trade_snapshot + order_item + orders(10) + order_status_log
    end
    O-->>B: orderNo (string), payAmount, expireAt
    Note over O,PI: Gap: A succeeds, B fails or Feign times out → dangling reservation, released by reconciliation
```

## Rate limiting at checkout + Feign circuit breaker

```mermaid
sequenceDiagram
    autonumber
    participant B as Buyer
    participant O as order (OrderRateLimiter)
    participant S as Sentinel (in-process)
    participant PI as product-inventory

    B->>O: POST /orders (tenantId=7, items)
    O->>S: SphU.entry("order:create", tenantId=7)
    alt tenant 7 has no tokens left this second (one bucket per merchant, default 20 QPS)
        S-->>O: BlockException
        O-->>B: HTTP 429 + 42900 + Retry-After: 1 (no SKU query, no reservation, no DB)
    else token available
        O->>S: entry("product-inventory:querySkus" / ":reserve")
        alt breaker open (slow-call or error ratio over threshold)
            S-->>O: DegradeException → DOWNSTREAM_UNAVAILABLE (no request sent)
        else normal
            O->>PI: Feign (read timeout 3 s, no automatic retry)
            PI-->>O: result; timeouts / 5xx count toward the breaker, business failures don't
        end
        O->>O: check tenantId matches SKU ownership, save order
        O-->>B: 200 orderNo
    end
```

The Sentinel dashboard is for monitoring and temporary rule changes only; rules are loaded from application.yml at startup and are not persisted.

## "Order created" event: two reliable-publish options

### Option 1: RocketMQ transactional message (`event-mode=TX_MESSAGE`)

```mermaid
sequenceDiagram
    participant O as order
    participant B as RocketMQ broker
    participant DB as MySQL mh_order
    participant C as Consumers
    O->>B: ① half message (RMQ_SYS_TRANS_HALF_TOPIC, invisible to consumers)
    B-->>O: SEND_OK
    O->>DB: ② local tx: order + lines + snapshot + status log (timeout 5 s)
    alt success
        O->>B: ③ COMMIT → message goes to ORDER_EVENT
    else failure
        O->>B: ③ ROLLBACK → half message discarded
    end
    Note over O,B: if ③ is lost (process killed)
    B->>O: ④ check-back (any live instance of the producer group, after ≥ 30 s)
    O->>DB: look up orders by order number
    O-->>B: exists → COMMIT / missing and old enough → ROLLBACK / DB error or too new → UNKNOW
    B->>C: deliver ORDER_EVENT
    C->>C: dedupe on consumed_event unique key + write notice (same tx)
```

### Option 2: transactional outbox (`event-mode=OUTBOX`, default)

```mermaid
sequenceDiagram
    participant O as order
    participant DB as MySQL mh_order
    participant R as OutboxRelay
    participant B as RocketMQ broker
    participant C as Consumers
    O->>DB: one local tx: order … + order_outbox(status=0)
    O->>R: afterCommit → async fast path (pool full → drop, counted + logged)
    loop fast path / scan every 5 s for due status=0 rows
        R->>DB: claim: push next_retry_at forward (lease), send only if 1 row affected
        R->>B: send (plain message, keys = order no, event id)
        alt success
            R->>DB: status=1, sent_at
        else failure
            R->>DB: retry_count+1, backoff 10 s → 20 s → … ≤ 10 min; status=2 + alert after 10 tries
        end
    end
    B->>C: deliver ORDER_EVENT
    C->>C: dedupe on consumed_event unique key + write notice (same tx)
```

## Payment callback → order paid

```mermaid
sequenceDiagram
    participant CH as Channel (mock)
    participant P as payment
    participant DBP as MySQL mh_payment
    participant MQ as RocketMQ PAYMENT_EVENT
    participant O as order
    participant DBO as MySQL mh_order
    CH->>P: payment succeeded (may repeat)
    P->>DBP: already succeeded? → return OK
    P->>MQ: half message (eventId = payNo)
    P->>DBP: UPDATE payment ... WHERE status = 0
    P->>MQ: won → COMMIT / lost → ROLLBACK
    MQ->>O: PAY_SUCCESS
    O->>DBO: in one tx: consumed_event → orders CAS(10→20) → order_status_log
```

## Timeout close / payment → confirm or release stock

```mermaid
sequenceDiagram
    participant O as order
    participant DBO as MySQL mh_order
    participant MQ as RocketMQ
    participant I as product-inventory
    participant DBI as MySQL mh_product
    Note over O,DBO: checkout tx: orders + status log + outbox(ORDER_CREATED) + outbox(PAY_TIMEOUT, deliver_at = expire_at)
    O->>MQ: ORDER_TIMEOUT scheduled message (setDeliverTimeMs)
    par payment succeeds
        MQ->>O: PAY_SUCCESS (PAYMENT_EVENT)
        O->>DBO: consumed_event → CAS 10→20 → won: outbox(ORDER_PAID) / lost: pay_anomaly
    and deadline reached
        MQ->>O: PAY_TIMEOUT (ORDER_TIMEOUT, delivered at the deadline)
        O->>DBO: consumed_event → CAS 10→50 → won: outbox(ORDER_CLOSED) / lost: write nothing
    end
    Note over O: only one CAS wins per order → only one kind of event
    O->>MQ: ORDER_PAID or ORDER_CLOSED (ORDER_EVENT)
    MQ->>I: consumer group inventory-order-settle
    I->>DBI: reservation CAS 10→20/30 + reserved -= n (confirm) or available += n, reserved -= n (release)
    Note over I,DBI: reservation recon: still 10 after 30 min → ask order /internal/orders/statuses → missing/closed: release, paid: confirm
```

## Tenant context propagation

A merchant-7 staff member queries an order, and an order event travels through MQ to inventory:

```mermaid
sequenceDiagram
    participant C as Client
    participant G as gateway AuthorizeFilter
    participant O as order
    participant DB as MySQL
    participant P as product-inventory
    participant MQ as RocketMQ

    C->>G: GET /api/orders/8000…01<br/>Access-Token (merchant 7 staff)<br/>+ forged X-Tenant-Id: 8
    G->>G: strip X-Tenant-Id / X-User-Id / X-Role<br/>verify token → set X-Tenant-Id: 7, X-Role: MERCHANT_STAFF
    G->>O: GET /orders/8000…01 (/internal/** is 404 already here)
    O->>O: TenantContextInterceptor: headers → ThreadLocal<br/>RoleCheckInterceptor: @RequireRole passes
    O->>DB: SELECT … WHERE order_no = 8000…01 AND tenant_id = 7 (added by the plugin)
    DB-->>O: 0 rows
    O-->>C: 40400 order not found<br/>afterCompletion: clear ThreadLocal

    Note over O,P: checkout: order → product-inventory (Feign)
    O->>P: POST /internal/inventory/reserve<br/>TenantFeignInterceptor adds X-Role: BUYER
    Note over O,MQ: order event (outbox thread, SYSTEM identity)
    O->>MQ: message property TENANT_ID = the outbox row's tenant_id
    MQ->>P: consume: consumeAs → ThreadLocal = that merchant<br/>reservation / stock SQL carries tenant_id, restored afterwards
```

## Product listing approval

The Flowable engine lives only in user-tenant. Merchant-7 staff submit product 1001 → merchant-7 admin reviews → platform admin reviews → the result is written back to product:

```mermaid
sequenceDiagram
    participant S as Merchant 7 staff
    participant P as product-inventory
    participant U as user-tenant (Flowable)
    participant A as Approvers
    participant MQ as RocketMQ
    S->>P: POST /products/1001/listing
    P->>P: tx ①: status 0→1, audit_round=1, status log (committed)
    P->>U: Feign start (outside the tx, idempotent on merchant + product + round)
    U->>U: start instance + insert listing_approval; ACT_RU_TASK: first review (group MERCHANT_ADMIN:7)
    U-->>P: processInstanceId → stored
    A->>U: merchant 7 admin approves → task to history, second review created (group PLATFORM_ADMIN)
    A->>U: platform admin approves
    Note over U: same tx: engine removes runtime task, writes history<br/>+ end listener writes the result + approval_outbox
    U->>MQ: after commit the relay sends PRODUCT_LISTING_RESULT (scan resends on failure)
    MQ->>P: consume: UPDATE ... SET status=2 WHERE status=1 AND audit_round=1 (duplicates hit 0 rows)
    Note over P,U: safety net: under review > 2 min → query the approval → write back with the same CAS / restart if never started
```

Merchant onboarding is local to user-tenant: when the platform admin approves, a ServiceTask creates the tenant and the MERCHANT_ADMIN account in the same transaction; on rejection the same instance waits for the applicant to revise and resubmit, or withdraw.

## Order push: multi-node routing

Merchant-7 staff are connected to node A; ORDER_CREATED is consumed by node B:

```mermaid
sequenceDiagram
    participant M as Merchant 7 staff
    participant A as realtime node A
    participant R as Redis
    participant MQ as RocketMQ
    participant B as realtime node B
    participant DB as MySQL mh_realtime
    M->>A: WebSocket handshake (JWT + compare with the login token in Redis)
    A->>R: HSET rt:route:merchant:7 node-a + HEXPIRE 90s
    A->>DB: resend "pending" rows from offline_message
    loop every 30 s
        M->>A: ping → pong, HEXPIRE renewed
    end
    MQ->>B: ORDER_CREATED (clustered group realtime-order-event)
    B->>DB: tx: consumed_event dedupe + merchant_order_notice
    B->>DB: insert offline_message as "pending" (store before push)
    B->>R: HKEYS rt:route:merchant:7 → {node-a}
    B->>MQ: REALTIME_PUSH, Tag = node-a
    MQ->>A: node-specific group realtime-push-node-a
    A->>M: write to the connection → mark "delivered" on success
    Note over A,R: if A dies: the field stops being renewed and expires after 90 s; messages routed to A meanwhile stay "pending" and are resent when the staff member reconnects elsewhere
```

## Consistency layers and reconciliation

```mermaid
flowchart TB
    subgraph L1[① Transactional message: local tx and message commit together]
        P1[payment CAS] -->|half message + check-back| E1[PAY_SUCCESS]
    end
    subgraph L2[② Outbox: state and event in one tx, scan resends]
        O1[order state CAS] --> OB[order_outbox] -->|orderOutboxRelayJob| E2[ORDER_PAID / CLOSED]
    end
    subgraph L3[③ Scheduled reconciliation: compare outcomes, not messages]
        J1[paymentReconJob<br/>payment succeeded ↔ handled by order? → resend<br/>stale pending payments → close]
        J2[reservationReconJob<br/>reservation ↔ order status → release / confirm]
        J3[orderReconJob<br/>pay_anomaly → refund alert<br/>outbox failure alert]
        J4[orderTimeoutScanJob<br/>expired but not closed → close]
    end
    E1 -.lost.-> J1
    E2 -.lost.-> J2
    J1 & J2 & J3 & J4 --> RE[(recon_exception + ERROR alert<br/>unfixable cases go to a human)]
```

### Watching the safety net itself

All jobs above are triggered by XXL-Job, so XXL-Job is a single point: if it is missing or down, every safety net stops at once (this happened in the cloud, see P56). A second, independent line runs inside order:

```mermaid
flowchart LR
    subgraph order
        FP[fast path pool<br/>full → drop<br/>counter outbox.fastpath.dropped + WARN]
        MON[OutboxBacklogMonitor<br/>Spring @Scheduled, every 60 s<br/>NOT on XXL-Job]
        SCAN[OutboxRelay.scan<br/>catch-up: batches of 1000, 4 threads]
    end
    XXL[XXL-Job admin] -->|every 5 s| SCAN
    MON -->|stale backlog not shrinking for 2 rounds<br/>or still stale after a fallback| SCAN
    MON -->|PutMetricData<br/>OutboxStalePending, ExpiredPendingOrders| CW[CloudWatch]
    CW --> AL[Alarm: rows pending > 5 min > 0<br/>missing data = breaching]
```

- The monitor must not be scheduled by the thing it watches: scheduling it on XXL-Job would make it go silent exactly when XXL-Job is down
- Two drainers at once are safe: `claim` is a conditional UPDATE (one winner per row), consumers dedupe by event id
- The alarm covers what automatic fallback can't fix (e.g. RocketMQ down, or the scan starved during overload): it turns "the system did not heal itself" into "someone knows"

## Cloud deployment (AWS)

Browser → Internet Gateway → **ALB** (public subnets) → **gateway** (Fargate, private subnets) → private IPs from Nacos → order / payment / product-inventory / user-tenant / realtime (one Fargate task each) → **RDS MySQL** / **ElastiCache Redis** (private subnets). Nacos, RocketMQ, Elasticsearch and the XXL-Job admin run on one private EC2 (docker compose); the XXL-Job schema lives on RDS. Product images go to S3 via presigned URLs. Task egress (ECR pulls, CloudWatch) goes through NAT; S3 traffic uses the free gateway endpoint. Custom metrics and alarms are in CloudWatch.

Infrastructure as code in `infra/` (Terraform); the deploy pipeline is `.github/workflows/cd.yml` (GitHub OIDC → ECR → ECS rolling update).
