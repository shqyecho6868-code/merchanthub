# perf/：压测（F1）

工具是 **k6**（脚本是 JS 代码、参数走环境变量、用 Docker 镜像跑不用安装，上云时同一个脚本只换 `BASE_URL`）。
操作步骤见 [`docs/runbook.md` 3.8 节](../docs/runbook.md)，结果报告见 [`docs/perf-report.md`](../docs/perf-report.md)。

| 路径 | 作用 |
|---|---|
| `run.sh` | 一条命令跑压测：`./perf/run.sh <脚本> [-e VUS=.. -e DURATION=..]` |
| `start-services.sh` | 后台起交易链路 5 个服务（`stop` / `status`） |
| `k6/lib/` | 参数（`config.js`）、接口封装（`api.js`）、结果汇总成 Markdown 表（`summary.js`） |
| `k6/smoke.js` | 冒烟：1 个用户跑几轮完整交易，验证框架 + 数据 + 服务是通的（不是压测场景） |
| `k6/scenarios/` | 压测场景（🧑 设计），从 `_template.js` 复制 |
| `data/seed.sh` | 每轮压测前：清掉压测商家 901 / 902 的历史订单等数据，重置库存、账号 |
| `check/check.sh` | 压测后：库存无负数、扣减对账、订单 ↔ 预占一致、重复回调只生效一次 |
| `monitor/sample.sh` | 压测中：MySQL 连接 / 行锁、MQ 消费堆积，每 5 秒采样成 CSV |
| `monitor/top-sql.sh` | 压测前 `reset`、压测后 `report`：热点 SQL / 慢 SQL Top 10 |
| `monitor/prometheus.yml`、`monitor/grafana/` | `docker compose --profile perf up -d` 起 Prometheus + Grafana 看板 |
| `results/<RUN_ID>/` | 每次运行的产物（不提交） |
