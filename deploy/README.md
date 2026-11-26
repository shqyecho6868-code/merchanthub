# deploy/

Middleware config used by `docker-compose.yml`, plus small scripts for running and observing each feature locally. Run every script from the repo root; most of them source `deploy/env.sh` (JDK 17 + passwords from `.env`).

| Path | Purpose |
|---|---|
| `env.sh` | Shared environment for the scripts below |
| `rocketmq/` | Broker config; `create-topics.sh` pre-creates the topics |
| `xxl-job/` | XXL-Job admin schema and job seeds; `init-xxl-job.sh` imports them once |
| `order-events/` | Place an order and inspect its transactional / outbox messages in the broker |
| `payment/` | Payment callbacks: duplicate callbacks, payment arriving before the order, payment vs. timeout-close race |
| `tenant-isolation/` | Issue identity tokens and try cross-tenant access through the gateway |
| `rate-limit/` | Per-merchant Sentinel rate limiting under load |
| `approval/` | Flowable approvals: log in, list pending tasks, approve / reject |
| `order-search/` | Canal → Elasticsearch sync and order search |
| `reconciliation/` | Start the services and compare deliberately inconsistent orders before / after reconciliation |
