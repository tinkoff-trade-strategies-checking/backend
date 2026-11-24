#!/usr/bin/env bash
# Проверка конфигурации и (опционально) живого стека наблюдаемости.
# Режимы:
#   --static  (по умолчанию) проверяет JSON дашбордов, docker compose config, правила INV-10 и Caddyfile.observability;
#   --live    дополнительно проверяет HTTPS-эндпоинты UI через Caddy и опубликованные порты контейнеров.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "$HERE/.." && pwd)"
BASE_COMPOSE="$DEPLOY_DIR/docker-compose.yml"
OBS_COMPOSE="$DEPLOY_DIR/docker-compose.observability.yml"
OBS_CADDY="$HERE/Caddyfile.observability"

MODE="static"
BASE_URL="${BASE_URL:-https://localhost}"
BASIC_USER="${OBS_BASIC_AUTH_USER:-admin}"
BASIC_PASS="${OBS_BASIC_AUTH_PASSWORD:-secret}"

while [ $# -gt 0 ]; do
  case "$1" in
    --static) MODE="static"; shift ;;
    --live) MODE="live"; shift ;;
    --base-url) BASE_URL="$2"; shift 2 ;;
    --user) BASIC_USER="$2"; shift 2 ;;
    --password) BASIC_PASS="$2"; shift 2 ;;
    *) echo "неизвестный аргумент: $1" >&2; exit 64 ;;
  esac
done

echo "1/4 Проверка JSON дашбордов Grafana"
PYTHON_BIN="$(command -v python3 || command -v python || true)"
if [ -n "$PYTHON_BIN" ]; then
  for f in "$HERE"/grafana/dashboards/*.json; do
    "$PYTHON_BIN" -m json.tool "$f" >/dev/null
    echo "  OK: $(basename "$f")"
  done
else
  echo "  SKIP: python не найден, проверка JSON пропущена"
fi

echo "2/4 Проверка Caddyfile.observability (INV-10: только HTTPS)"
if grep -Eq '^[[:space:]]*http://.*\{' "$OBS_CADDY"; then
  echo "INV-10 нарушен: найден http:// сайт в $OBS_CADDY" >&2
  exit 1
fi
if grep -Eq '^[[:space:]]*(tls[[:space:]]+off|auto_https[[:space:]]+off)\b' "$OBS_CADDY"; then
  echo "INV-10 нарушен: отключён TLS в $OBS_CADDY" >&2
  exit 1
fi
echo "  OK: Caddyfile.observability использует только HTTPS"

echo "3/4 Проверка объединения docker compose и отсутствия лишних портов (INV-10)"
# Проверяем, что в docker-compose.observability.yml нет директивы ports:
if grep -Eq '^[[:space:]]*ports:' "$OBS_COMPOSE"; then
  echo "INV-10 нарушен: в $OBS_COMPOSE обнаружена публикация портов" >&2
  exit 1
fi

DOCKER_COMPOSE=()
if docker compose version >/dev/null 2>&1; then
  DOCKER_COMPOSE=(docker compose)
elif command -v docker.exe >/dev/null 2>&1 && docker.exe compose version >/dev/null 2>&1; then
  DOCKER_COMPOSE=(docker.exe compose)
elif command -v docker-compose >/dev/null 2>&1; then
  DOCKER_COMPOSE=(docker-compose)
fi

if [ "${#DOCKER_COMPOSE[@]}" -gt 0 ]; then
  TMP_ENV="$DEPLOY_DIR/.env.observability.validate.tmp"
  trap 'rm -f "$TMP_ENV"' EXIT
  printf 'POSTGRES_PASSWORD=validate_pg\nCLICKHOUSE_PASSWORD=validate_ch\n' > "$TMP_ENV"
  MERGED="$(cd "$DEPLOY_DIR" && "${DOCKER_COMPOSE[@]}" --env-file .env.observability.validate.tmp -f docker-compose.yml -f docker-compose.observability.yml config)"
  rm -f "$TMP_ENV"

  # Проверяем, что в итоговом конфиге только caddy публикует порты 80 и 443
  if [ -n "$PYTHON_BIN" ]; then
    echo "$MERGED" | "$PYTHON_BIN" -c '
import sys, re
text = sys.stdin.read()
services_section = text.split("\nservices:\n", 1)[1].split("\nnetworks:\n", 1)[0]
current = None
for line in services_section.splitlines():
    m = re.match(r"^  ([a-zA-Z0-9_-]+):$", line)
    if m:
        current = m.group(1)
    elif re.match(r"^    ports:$", line) and current != "caddy":
        sys.stderr.write(f"INV-10 нарушен: сервис {current} публикует порты\n")
        sys.exit(1)
print("  OK: docker compose config валиден, порты публикует только caddy")
'
  else
    echo "  OK: docker compose config валиден"
  fi
else
  echo "  SKIP: docker compose не найден, проверка объединения compose пропущена"
fi

if [ "$MODE" = "live" ]; then
  echo "4/4 Проверка живых HTTPS-эндпоинтов через Caddy ($BASE_URL)"
  check_url() {
    local url="$1"
    local auth="${2:-}"
    local code
    if [ -n "$auth" ]; then
      code="$(curl -sk -u "$auth" -o /dev/null -w '%{http_code}' "$url")"
    else
      code="$(curl -sk -o /dev/null -w '%{http_code}' "$url")"
    fi
    if [ "$code" != "200" ]; then
      echo "Ожидался 200 на $url, получен $code" >&2
      exit 1
    fi
    echo "  OK ($code): $url"
  }

  check_url "$BASE_URL/grafana/api/health"
  check_url "$BASE_URL/vm/health" "$BASIC_USER:$BASIC_PASS"
  check_url "$BASE_URL/vl/health" "$BASIC_USER:$BASIC_PASS"
  check_url "$BASE_URL/vmalert/health" "$BASIC_USER:$BASIC_PASS"
  check_url "$BASE_URL/alertmanager/-/healthy" "$BASIC_USER:$BASIC_PASS"

  if command -v docker >/dev/null 2>&1; then
    CONTAINERS="$(docker ps --filter "label=com.docker.compose.project=trainer" --format '{{.Label "com.docker.compose.service"}}|{{.Ports}}')"
    while IFS='|' read -r svc ports; do
      [ -z "$svc" ] && continue
      if [ "$svc" != "caddy" ] && echo "$ports" | grep -q -- '->'; then
        echo "INV-10 нарушен: сервис $svc публикует порт наружу: $ports" >&2
        exit 1
      fi
    done <<< "$CONTAINERS"
    echo "  OK: INV-10 на живых контейнерах соблюдён"
  fi
else
  echo "4/4 Пропуск live-проверки (запустите с --live для проверки работающего стека)"
fi

echo "observability validate OK"
