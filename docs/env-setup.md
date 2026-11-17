# 本地开发环境

> A2 产出。所有中间件由仓库根目录的 `docker-compose.yml` 拉起，密码在 `.env`（不提交，模板见 `.env.example`）。

## 1. 一键启动

```bash
docker compose up -d        # 启动全部
docker compose ps -a        # 查看状态（broker-init 显示 Exited (0) 是正常的，它是一次性任务）
docker compose logs -f broker   # 看某个服务的日志
docker compose down         # 停止并删除容器（数据卷保留）
docker compose down -v      # 连数据卷一起删 —— 会清空所有数据，慎用
```

## 2. 中间件一览

| 组件 | 镜像版本 | 宿主机端口 | 控制台 / 连接地址 | 账号 |
|---|---|---|---|---|
| MySQL | `mysql:8.0`（实测 8.0.46） | 3306 | `127.0.0.1:3306`，库名 `merchanthub` | `root` / `.env` 中 `MYSQL_ROOT_PASSWORD` |
| Redis | `redis:7.4` | 6379 | `127.0.0.1:6379` | 无用户名 / `.env` 中 `REDIS_PASSWORD` |
| Nacos | `nacos/nacos-server:v3.0.3` | 8080 / 8848 / 9848 | 控制台 http://127.0.0.1:8080 ；服务端地址 `127.0.0.1:8848` | `nacos` / `.env` 中 `NACOS_PASSWORD` |
| RocketMQ NameServer | `apache/rocketmq:5.3.1` | 9876 | `127.0.0.1:9876` | 无 |
| RocketMQ Broker | `apache/rocketmq:5.3.1` | 10911 / 10909 | 注册地址 `host.docker.internal:10911` | 无 |
| RocketMQ Dashboard | `apacherocketmq/rocketmq-dashboard:2.1.0` | 8082 | http://127.0.0.1:8082 | 无 |

## 3. Java 侧版本

| 项 | 版本 | 依据 |
|---|---|---|
| JDK | 17（本机 `ms-17.0.20`） | 本机默认 `java` 是 26，命令行编译要先 `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` |
| Maven | 3.9.11 | A2+ 起用仓库自带的 `./mvnw`（Maven Wrapper），本机无需安装 Maven |
| Spring Boot | 3.5.11 | — |
| Spring Cloud | 2025.0.0 | SCA 官方版本说明 |
| Spring Cloud Alibaba | 2025.0.0.0 | 官方：2025.0.0.0 ↔ Spring Cloud 2025.0.0 ↔ Spring Boot 3.5.x |
| nacos-client | 3.0.3（随 SCA） | 服务端选同版本 v3.0.3 |

## 4. 在 IDEA 里启动服务

各服务 `application-dev.yml` 通过环境变量读取密码：`MYSQL_ROOT_PASSWORD`、`REDIS_PASSWORD`、`NACOS_USERNAME`、`NACOS_PASSWORD`，名字和 `.env` 完全一致。
在 IDEA 的 Run Configuration → Environment variables 里填入这几个变量即可（Spring 不会自动读取 `.env` 文件）。

## 5. 一次性设置（用到 RocketMQ 代码之前做）

宿主机上的 Java 程序要连 Broker，需要让 Mac 也能解析 `host.docker.internal`：

```bash
sudo sh -c 'echo "127.0.0.1 host.docker.internal" >> /etc/hosts'
```

原因见 `docs/notes/A2-环境搭建.md` 第 3 条。A2 还没有 RocketMQ 代码，所以暂时不做也不影响。

## 6. 尚未纳入 compose 的组件

ES、Canal、XXL-Job、Sentinel、Flowable 在后续对应任务中再加入；Kafka、MinIO、pgvector 是旧代码仍引用的组件，未拉起（A2 不要求服务能启动）。
