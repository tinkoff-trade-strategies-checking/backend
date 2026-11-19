#!/usr/bin/env bash
# Останавливает локальный стенд (deploy/dev-up.sh) и чистит: backend, контейнеры, сеть, deploy/.dev и deploy/.env.dev.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEV="$HERE/.dev"
if [ -f "$DEV/backend.pid" ]; then
  kill "$(cat "$DEV/backend.pid")" 2>/dev/null || true
fi
docker rm -f trainer-dev-pg trainer-dev-ch trainer-dev-worker >/dev/null 2>&1 || true
docker network rm trainer-dev >/dev/null 2>&1 || true
rm -rf "$DEV" "$HERE/.env.dev"
echo "стенд остановлен"
