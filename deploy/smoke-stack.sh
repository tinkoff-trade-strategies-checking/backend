#!/usr/bin/env bash
# Проверка живого стека deploy/docker-compose.yml через HTTPS Caddy:
# 1) GET / — реальный Angular SPA (не заглушка, если не задан --allow-stub);
# 2) GET /api/health — 200 {"status":"UP"};
# 3) GET /api/v1/me — 401 и cookie XSRF-TOKEN;
# 4) при наличии учётных данных (--email/--password или deploy/.env.dev): POST /api/v1/auth/login -> 204, затем GET /api/v1/me -> 200;
# 5) INV-10: порты наружу (80, 443) публикует только сервис caddy.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${BASE_URL:-https://localhost}"
ALLOW_STUB=false
SKIP_PORT_CHECK=false
EMAIL="${DEV_USER_EMAIL:-}"
PASSWORD="${DEV_USER_PASSWORD:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --base-url) BASE_URL="$2"; shift 2 ;;
    --allow-stub) ALLOW_STUB=true; shift ;;
    --skip-port-check) SKIP_PORT_CHECK=true; shift ;;
    --email) EMAIL="$2"; shift 2 ;;
    --password) PASSWORD="$2"; shift 2 ;;
    *) echo "неизвестный аргумент: $1" >&2; exit 64 ;;
  esac
done

if [ -z "$EMAIL" ] && [ -f "$HERE/.env.dev" ]; then
  # shellcheck disable=SC1090
  set -a; . "$HERE/.env.dev"; set +a
  EMAIL="${DEV_USER_EMAIL:-}"
  PASSWORD="${DEV_USER_PASSWORD:-}"
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "0/5 Ожидание готовности HTTPS Caddy: $BASE_URL/api/health"
READY=false
for _ in $(seq 1 15); do
  if curl -fsk --max-time 2 -o /dev/null "$BASE_URL/api/health"; then
    READY=true
    break
  fi
  sleep 1
done
[ "$READY" = "true" ] || { echo "Caddy/backend не отвечает по HTTPS: $BASE_URL/api/health" >&2; exit 1; }

echo "1/5 Проверка HTTPS SPA: $BASE_URL/"
HTML_STATUS="$(curl -sk -o "$TMP/index.html" -w '%{http_code}' "$BASE_URL/")"
[ "$HTML_STATUS" = "200" ] || { echo "Ожидался 200 на /, получен $HTML_STATUS" >&2; exit 1; }
if [ "$ALLOW_STUB" = "false" ]; then
  if grep -q "Frontend не собран" "$TMP/index.html"; then
    echo "Отдаётся заглушка вместо собранного Angular SPA" >&2
    exit 1
  fi
  if ! grep -q "<app-root>" "$TMP/index.html"; then
    echo "В ответе / нет <app-root> (не Angular SPA)" >&2
    exit 1
  fi
fi

echo "2/5 Проверка /api/health"
HEALTH_STATUS="$(curl -sk -o "$TMP/health.json" -w '%{http_code}' "$BASE_URL/api/health")"
[ "$HEALTH_STATUS" = "200" ] || { echo "Ожидался 200 на /api/health, получен $HEALTH_STATUS" >&2; exit 1; }
grep -q '"UP"' "$TMP/health.json" || { echo "Неожиданный ответ /api/health: $(cat "$TMP/health.json")" >&2; exit 1; }

echo "3/5 Проверка анонимного /api/v1/me -> 401 + XSRF-TOKEN"
ME_ANON_STATUS="$(curl -sk -D "$TMP/me_anon.hdr" -c "$TMP/cookies.txt" -o "$TMP/me_anon.json" -w '%{http_code}' "$BASE_URL/api/v1/me")"
[ "$ME_ANON_STATUS" = "401" ] || { echo "Ожидался 401 на /api/v1/me, получен $ME_ANON_STATUS" >&2; exit 1; }
grep -qi '^Set-Cookie:.*XSRF-TOKEN=' "$TMP/me_anon.hdr" || { echo "Не выдана cookie XSRF-TOKEN на /api/v1/me" >&2; exit 1; }

XSRF="$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$TMP/cookies.txt" | tail -n 1)"
[ -n "$XSRF" ] || { echo "Не удалось извлечь XSRF-TOKEN из cookies" >&2; exit 1; }

if [ -n "$EMAIL" ] && [ -n "$PASSWORD" ]; then
  echo "4/5 Проверка входа POST /api/v1/auth/login -> 204 и GET /api/v1/me -> 200"
  LOGIN_STATUS="$(curl -sk -b "$TMP/cookies.txt" -c "$TMP/cookies.txt" -o "$TMP/login.out" -w '%{http_code}' \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -H "X-XSRF-TOKEN: $XSRF" \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")"
  [ "$LOGIN_STATUS" = "204" ] || { echo "Ожидался 204 на /api/v1/auth/login, получен $LOGIN_STATUS: $(cat "$TMP/login.out")" >&2; exit 1; }

  ME_AUTH_STATUS="$(curl -sk -b "$TMP/cookies.txt" -o "$TMP/me_auth.json" -w '%{http_code}' "$BASE_URL/api/v1/me")"
  [ "$ME_AUTH_STATUS" = "200" ] || { echo "Ожидался 200 на /api/v1/me после входа, получен $ME_AUTH_STATUS" >&2; exit 1; }
  grep -q "$EMAIL" "$TMP/me_auth.json" || { echo "Ответ /api/v1/me не содержит email: $(cat "$TMP/me_auth.json")" >&2; exit 1; }
else
  echo "4/5 Пропуск проверки входа (не заданы DEV_USER_EMAIL/DEV_USER_PASSWORD)"
fi

if [ "$SKIP_PORT_CHECK" = "false" ] && command -v docker >/dev/null 2>&1; then
  echo "5/5 Проверка INV-10 (порты публикует только caddy)"
  CONTAINERS="$(docker ps --filter "label=com.docker.compose.project=trainer" --format '{{.Label "com.docker.compose.service"}}|{{.Ports}}')"
  [ -n "$CONTAINERS" ] || { echo "Контейнеры compose-проекта trainer не найдены" >&2; exit 1; }
  while IFS='|' read -r svc ports; do
    if [ "$svc" = "caddy" ]; then
      echo "$ports" | grep -qE ':80->' && echo "$ports" | grep -qE ':443->' || {
        echo "У caddy не опубликованы порты 80 и 443: $ports" >&2
        exit 1
      }
    else
      if echo "$ports" | grep -q -- '->'; then
        echo "INV-10 нарушен: сервис $svc публикует порт наружу: $ports" >&2
        exit 1
      fi
    fi
  done <<< "$CONTAINERS"
fi

echo "smoke-stack OK: $BASE_URL"
