#!/bin/sh
# E3：给 XXL-Job 调度中心建库建表 + 登记执行器和任务（只需执行一次）。
# 已有的 mysql 数据卷不会再跑 /docker-entrypoint-initdb.d，所以手动导入。
cd "$(dirname "$0")/../.." || exit 1
set -a; . ./.env; set +a
if docker exec mh-mysql mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "SHOW DATABASES LIKE 'xxl_job'" 2>/dev/null | grep -q xxl_job; then
  echo "xxl_job 库已存在，跳过"
  exit 0
fi
docker exec -i mh-mysql mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" < deploy/xxl-job/tables_xxl_job.sql || exit 1
docker exec -i mh-mysql mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" < deploy/xxl-job/seed-jobs.sql || exit 1
echo "已导入。重启调度中心让它读到新表：docker compose restart xxl-job-admin"
