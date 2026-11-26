# perf/: load testing

The tool is **k6**: scenarios are JS code, parameters come from env vars, and it runs from the Docker image with nothing to install. In the cloud, the same scripts run with a different `BASE_URL`. Results are summarized in the [root README](../README.md).

## How to run (local)

```bash
docker compose up -d                                   # middleware
docker compose --profile perf up -d                    # optional: Prometheus + Grafana dashboard
./mvnw -q package -DskipTests
./perf/start-services.sh                               # gateway, user-tenant, product-inventory, order, payment
./perf/start-services.sh status                        # wait until all are UP
./perf/data/seed.sh                                    # load-test merchants 901 / 902, 200 buyers, SKUs and stock
./perf/run.sh perf/k6/smoke.js                         # smoke test: the whole chain works
./perf/round.sh s1 perf/k6/scenarios/s1-rate-limit.js  # one full round: seed -> sample -> k6 -> data check -> hot SQL
```

Scenarios other than S1 need a higher rate limit for merchant 901, e.g.
`ORDER_OPTS='--merchanthub.order.sentinel.rate-limit.tenant-overrides[901]=100000' ./perf/start-services.sh`.
S2b also needs `HOT_STOCK=500` (see the scenario header). Each scenario file states its question, load, metrics and expected result.

## Layout

| Path | Purpose |
|---|---|
| `run.sh` | Run a test with one command: `./perf/run.sh <script> [-e VUS=.. -e DURATION=..]` |
| `round.sh` | One full round with data reset, sampling, data check and hot-SQL report |
| `start-services.sh` | Start the 5 services on the trading path in the background (`stop` / `status`) |
| `k6/lib/` | Parameters (`config.js`), API wrappers (`api.js`), result summary as a Markdown table (`summary.js`) |
| `k6/smoke.js` | Smoke test: 1 user runs a few full transactions to verify framework + data + services (not a load scenario) |
| `k6/scenarios/` | Load scenarios, copied from `_template.js` |
| `data/seed.sh` | Before each round: delete old orders etc. of merchants 901 / 902, reset stock and accounts |
| `check/check.sh` | After a round: no negative stock, deductions reconcile, orders match reservations, duplicate callbacks applied once |
| `monitor/sample.sh` | During a round: MySQL connections / row locks and MQ consumer backlog, sampled to CSV every 5s |
| `monitor/top-sql.sh` | `reset` before and `report` after: Top 10 hot / slow SQL |
| `monitor/prometheus.yml`, `monitor/grafana/` | `docker compose --profile perf up -d` starts Prometheus + a Grafana dashboard |
| `cloud/` | Running the same scenarios against the AWS environment (in-VPC k6, RDS data check, timeline slicing) |
| `results/<RUN_ID>/` | Output of each run (not committed) |
