#!/usr/bin/env bash
# Локальный стенд для проверки UI: PostgreSQL, ClickHouse с фикстурой (та же, что у e2e), воркер из образа
# simulation-worker и backend на http://localhost:8080 или --port (в фоне, pid в deploy/.dev/backend.pid).
# Использование: deploy/dev-up.sh [--days N] [--port P]   (N >= 160 торговых дней, по умолчанию 160; порт backend по умолчанию 8080)
# Пароль тестового пользователя пишется в deploy/.env.dev (в .gitignore), в вывод не попадает.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
DEV="$HERE/.dev"
ENV_FILE="$HERE/.env.dev"
NET=trainer-dev
PG=trainer-dev-pg
CH=trainer-dev-ch
WK=trainer-dev-worker
PG_PORT=15432
CH_PORT=18123
WK_PORT=15051
DAYS=160
PORT=8080
CORE_DIR="${CORE_DIR:-$ROOT/../core}"
[ -d "$CORE_DIR" ] || CORE_DIR="$ROOT/../../../core"

while [ $# -gt 0 ]; do
  case "$1" in
    --days) DAYS="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    *) echo "неизвестный аргумент: $1" >&2; exit 64 ;;
  esac
done

if [ -f "$DEV/backend.pid" ] && kill -0 "$(cat "$DEV/backend.pid")" 2>/dev/null; then
  echo "стенд уже запущен (deploy/dev-down.sh остановит)" >&2
  exit 1
fi
mkdir -p "$DEV"

if [ ! -f "$ENV_FILE" ]; then
  rand() { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "$1" || true; }
  umask 077
  {
    echo "DEV_USER_EMAIL=trader@example.com"
    echo "DEV_USER_PASSWORD=$(rand 20)"
    echo "POSTGRES_PASSWORD=$(rand 20)"
    echo "CLICKHOUSE_PASSWORD=$(rand 20)"
  } > "$ENV_FILE"
fi
# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a

# образ воркера: нет — собираем из core (тяжёлая сборка C++)
if ! docker image inspect simulation-worker:latest >/dev/null 2>&1; then
  echo "образа simulation-worker нет, собираю из $CORE_DIR"
  docker build -t simulation-worker:latest "$CORE_DIR"
fi

docker network inspect "$NET" >/dev/null 2>&1 || docker network create "$NET" >/dev/null
docker rm -f "$PG" "$CH" "$WK" >/dev/null 2>&1 || true

docker run -d --name "$PG" --network "$NET" -p "127.0.0.1:$PG_PORT:5432" \
  -e POSTGRES_DB=trainer -e POSTGRES_USER=trainer -e POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
  postgres:17-alpine >/dev/null
docker run -d --name "$CH" --network "$NET" --network-alias clickhouse -p "127.0.0.1:$CH_PORT:8123" \
  -e CLICKHOUSE_DB=ttsc -e CLICKHOUSE_USER=ttsc -e CLICKHOUSE_PASSWORD="$CLICKHOUSE_PASSWORD" \
  -e CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1 clickhouse/clickhouse-server:25.8-alpine >/dev/null

wait_for() { # описание, команда...
  local what="$1"; shift
  for _ in $(seq 1 60); do "$@" >/dev/null 2>&1 && return 0; sleep 2; done
  echo "не дождался: $what" >&2; exit 1
}
wait_for postgres docker exec "$PG" pg_isready -U trainer -d trainer
wait_for clickhouse curl -fs "http://127.0.0.1:$CH_PORT/ping"

ch() { # один оператор SQL; тело через stdin (длинные INSERT не помещаются в аргументы)
  printf '%s' "$1" | curl -fsS -H "X-ClickHouse-User: ttsc" -H "X-ClickHouse-Key: $CLICKHOUSE_PASSWORD" \
    --data-binary @- "http://127.0.0.1:$CH_PORT/?database=ttsc" >/dev/null
}

echo "фикстура: $DAYS торговых дней"
( cd "$ROOT" && ./gradlew -q devFixtureSql "-PfixtureOut=$DEV/fixture.sql" "-PfixtureDays=$DAYS" > "$DEV/snapshot-key" )
SNAPSHOT_KEY="$(tail -n 1 "$DEV/snapshot-key" | tr -d '\r\n')"
while IFS= read -r line || [ -n "$line" ]; do
  [ -n "$line" ] && ch "$line"
done < "$DEV/fixture.sql"

docker run -d --name "$WK" --network "$NET" -p "127.0.0.1:$WK_PORT:50051" \
  -e WORKER_ADDRESS=0.0.0.0:50051 \
  -e "CLICKHOUSE_URL=http://ttsc:$CLICKHOUSE_PASSWORD@clickhouse:8123/ttsc" \
  simulation-worker:latest >/dev/null

# Java 25: системная или JDK, скачанный Gradle (toolchain)
JAVA=java
if ! java -version 2>&1 | head -n 1 | grep -q '"25'; then
  for cand in "$HOME"/.gradle/jdks/*25*/bin/java "$HOME"/.gradle/jdks/*25*/bin/java.exe "$HOME"/.gradle/jdks/*25*/*/bin/java; do
    [ -x "$cand" ] && { JAVA="$cand"; break; }
  done
fi
( cd "$ROOT" && ./gradlew -q bootJar )
JAR="$(ls "$ROOT"/build/libs/*.jar | grep -v -- '-plain' | head -n 1)"

export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:$PG_PORT/trainer"
export SPRING_DATASOURCE_USERNAME=trainer
export SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD"
export WORKER_ADDRESS="localhost:$WK_PORT"
export WORKER_DEADLINE=30s
export MARKETDATA_CLICKHOUSE_URL="http://localhost:$CH_PORT"
export MARKETDATA_CLICKHOUSE_DATABASE=ttsc
export MARKETDATA_CLICKHOUSE_USER=ttsc
export MARKETDATA_CLICKHOUSE_PASSWORD="$CLICKHOUSE_PASSWORD"
# в фикстуре IMOEX лежит и в securities (его требует воркер для бенчмарка), торговать им нельзя
export MARKETDATA_POOL=SBER,GAZP,LKOH
# UI на http://localhost: cookie сессии без Secure
export SERVER_SERVLET_SESSION_COOKIE_SECURE=false
export SERVER_PORT="$PORT"

# пользователь (профиль create-user заодно применяет миграции). JVM профиля после работы не завершается
# (остаются потоки), поэтому ждём строку об успехе в логе и останавливаем процесс сами
USER_PASSWORD="$DEV_USER_PASSWORD" "$JAVA" -jar "$JAR"   --spring.profiles.active=create-user --user.email="$DEV_USER_EMAIL" >"$DEV/create-user.log" 2>&1 &
CU_PID=$!
for _ in $(seq 1 90); do
  grep -q 'Пользователь создан' "$DEV/create-user.log" 2>/dev/null && break
  kill -0 "$CU_PID" 2>/dev/null || break
  sleep 1
done
kill "$CU_PID" 2>/dev/null || true
grep -q 'Пользователь создан' "$DEV/create-user.log" || { echo "create-user не удался, см. deploy/.dev/create-user.log" >&2; exit 1; }

# снапшот готов в ClickHouse; приложению он известен по строке в PostgreSQL
docker exec "$PG" psql -U trainer -d trainer -q -c \
  "INSERT INTO marketdata.snapshot (id, snapshot_key, description) VALUES ('$SNAPSHOT_KEY', '$SNAPSHOT_KEY', 'dev-stand') ON CONFLICT DO NOTHING" >/dev/null

nohup "$JAVA" -jar "$JAR" >"$DEV/backend.log" 2>&1 &
echo $! > "$DEV/backend.pid"
for _ in $(seq 1 60); do
  kill -0 "$(cat "$DEV/backend.pid")" 2>/dev/null || { echo "backend не запустился, см. deploy/.dev/backend.log (порт $PORT занят? --port)" >&2; exit 1; }
  curl -fs -o /dev/null "http://localhost:$PORT/api/health" && break
  sleep 2
done

echo "стенд готов: http://localhost:$PORT (API /api/v1), почта $DEV_USER_EMAIL, пароль в deploy/.env.dev"
