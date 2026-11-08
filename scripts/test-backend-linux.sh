#!/usr/bin/env bash
# Cloud Build/Docker-only runner. Always creates its own PostgreSQL cluster, never Cloud SQL.
set -euo pipefail
for name in $(compgen -e); do
  case "$name" in SPRING_*|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|MAVEN_OPTS|MAVEN_ARGS) unset "$name";; esac
done
root="$(cd "$(dirname "$0")/.." && pwd)"
pgbin="$(pg_config --bindir)"
cluster="$(mktemp -d /tmp/rwc-test-XXXXXXXX)"
password="$(cat /proc/sys/kernel/random/uuid)"
token="$(cat /proc/sys/kernel/random/uuid)"
printf '%s' "$password" > "$cluster/password"
chown postgres:postgres "$cluster" "$cluster/password"
chmod 600 "$cluster/password"
runuser -u postgres -- "$pgbin/initdb" -D "$cluster/data" -U rwc_test --auth=scram-sha-256 --pwfile="$cluster/password" --encoding=UTF8 --locale=C >/dev/null
runuser -u postgres -- "$pgbin/pg_ctl" -D "$cluster/data" -l "$cluster/server.log" -o '-h 127.0.0.1 -p 55432' -w start
trap 'runuser -u postgres -- "$pgbin/pg_ctl" -D "$cluster/data" -m fast -w stop >/dev/null' EXIT
export PGPASSWORD="$password"
"$pgbin/createdb" -h 127.0.0.1 -p 55432 -U rwc_test rwc_slice_test
"$pgbin/psql" -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -p 55432 -U rwc_test -d rwc_slice_test -c "COMMENT ON DATABASE rwc_slice_test IS '$token'" >/dev/null
identity="$("$pgbin/psql" -X -At -h 127.0.0.1 -p 55432 -U rwc_test -d rwc_slice_test -c "select current_database()||'|'||current_user||'|'||host(inet_server_addr())||'|'||inet_server_port()||'|'||shobj_description(oid,'pg_database') from pg_database where datname=current_database()")"
[[ "$identity" == "rwc_slice_test|rwc_test|127.0.0.1|55432|$token" ]] || { echo 'Database identity verification failed; tests will not run.' >&2; exit 1; }
export RWC_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/rwc_slice_test RWC_TEST_DB_USERNAME=rwc_test RWC_TEST_DB_PASSWORD="$password" RWC_TEST_DB_TOKEN="$token"
export DB_URL="$RWC_TEST_DB_URL" DB_USERNAME=rwc_test DB_PASSWORD="$password" RUN_DB_TESTS=true
export INITIAL_ADMIN_USERNAME=test-admin INITIAL_STAFF_USERNAME=test-staff
export INITIAL_ADMIN_PASSWORD="$(cat /proc/sys/kernel/random/uuid)" INITIAL_STAFF_PASSWORD="$(cat /proc/sys/kernel/random/uuid)"
# Explicitly override every alternative datasource/Flyway connection path before startup.
export SPRING_APPLICATION_JSON="{\"spring.datasource.url\":\"$DB_URL\",\"spring.datasource.username\":\"rwc_test\",\"spring.datasource.password\":\"$password\",\"spring.datasource.driver-class-name\":\"org.postgresql.Driver\",\"spring.datasource.hikari.jdbc-url\":\"$DB_URL\",\"spring.datasource.hikari.username\":\"rwc_test\",\"spring.datasource.hikari.password\":\"$password\",\"spring.datasource.hikari.schema\":\"public\",\"spring.flyway.url\":\"$DB_URL\",\"spring.flyway.user\":\"rwc_test\",\"spring.flyway.password\":\"$password\",\"spring.flyway.default-schema\":\"public\",\"spring.flyway.schemas\":\"public\"}"
cd "$root/backend"
mvn -B -ntp verify
