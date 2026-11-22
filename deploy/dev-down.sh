#!/usr/bin/env bash
# Останавливает локальный стенд (deploy/dev-up.sh) и чистит: backend/compose, контейнеры, сеть, deploy/.dev и deploy/.env.dev.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEV="$HERE/.dev"
ENV_FILE="$HERE/.env.dev"

HAS_COMPOSE=false
if [ -f "$DEV/mode" ] && [ "$(cat "$DEV/mode")" = "compose" ]; then
  HAS_COMPOSE=true
elif [ -n "$(docker ps -aq --filter "label=com.docker.compose.project=trainer" 2>/dev/null || true)" ]; then
  HAS_COMPOSE=true
fi

if [ "$HAS_COMPOSE" = "true" ]; then
  if [ -f "$ENV_FILE" ]; then
    docker compose -f "$HERE/docker-compose.yml" --env-file "$ENV_FILE" down -v >/dev/null 2>&1 || true
  else
    POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-x}" CLICKHOUSE_PASSWORD="${CLICKHOUSE_PASSWORD:-x}" \
      docker compose -f "$HERE/docker-compose.yml" down -v >/dev/null 2>&1 || true
  fi
fi

if [ -f "$DEV/backend.pid" ]; then
  kill "$(cat "$DEV/backend.pid")" 2>/dev/null || true
fi
docker rm -f trainer-dev-pg trainer-dev-ch trainer-dev-worker >/dev/null 2>&1 || true
docker network rm trainer-dev >/dev/null 2>&1 || true
rm -rf "$DEV" "$ENV_FILE"
echo "стенд остановлен"
