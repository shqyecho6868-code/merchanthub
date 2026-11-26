#!/bin/sh
# Create the XXL-Job admin database and tables, and register executors and jobs (run once).
# An existing mysql data volume won't run /docker-entrypoint-initdb.d again, so import manually.
cd "$(dirname "$0")/../.." || exit 1
set -a; . ./.env; set +a
if docker exec mh-mysql mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "SHOW DATABASES LIKE 'xxl_job'" 2>/dev/null | grep -q xxl_job; then
  echo "Database xxl_job already exists, skipping"
  exit 0
fi
docker exec -i mh-mysql mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" < deploy/xxl-job/tables_xxl_job.sql || exit 1
docker exec -i mh-mysql mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" < deploy/xxl-job/seed-jobs.sql || exit 1
echo "Imported. Restart the admin so it picks up the new tables: docker compose restart xxl-job-admin"
