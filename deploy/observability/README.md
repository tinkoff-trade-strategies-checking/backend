# Стек наблюдаемости (Observability)

Оверлей `deploy/docker-compose.observability.yml` дополняет базовый стек `deploy/docker-compose.yml` полным набором инструментов мониторинга, логирования и алертинга без нарушения инварианта **INV-10**.

## Состав стека

| Сервис | Образ | Назначение |
| --- | --- | --- |
| `victoria-metrics` | `victoriametrics/victoria-metrics:v1.113.0` | Хранилище метрик и **VMUI** |
| `victoria-logs` | `victoriametrics/victoria-logs:v1.18.0` | Хранилище логов и **VLUI** |
| `vmagent` | `victoriametrics/vmagent:v1.113.0` | Сбор метрик (backend, postgres-exporter, clickhouse, caddy, self-scrape) |
| `vmalert` | `victoriametrics/vmalert:v1.113.0` | Вычисление правил алертинга и UI правил |
| `alertmanager` | `prom/alertmanager:v0.28.1` | Группировка, маршрутизация, подавление и доставка алертов |
| `grafana` | `grafana/grafana:11.6.0` | Единый UI с автопровижинингом источников данных и дашбордов |
| `postgres-exporter` | `prometheuscommunity/postgres-exporter:v0.17.1` | Метрики PostgreSQL |
| `vector` | `timberio/vector:0.45.0-alpine` | Сбор JSON-логов Docker-контейнеров проекта `trainer` в VictoriaLogs |

## Соответствие INV-10

- **Ни один сервис наблюдаемости не публикует порты на хост**: в `docker-compose.observability.yml` нет дополнительных директив `ports`.
- Все контейнеры наблюдаемости подключены исключительно к внутренней сети `internal` (`internal: true`, подсеть `172.28.0.0/24`).
- Единственная точка входа снаружи — **Caddy** (порты `80` и `443`, принудительный HTTPS с `tls internal`), конфигурация `deploy/observability/Caddyfile.observability`.
- Админ-метрики Caddy (`:2019/metrics`) доступны только внутри Docker-сети для `vmagent` и не публикуются на хост.
- Порт actuator backend (`8081`) и Prometheus-экспортер ClickHouse (`9363`) доступны только во внутренней сети `internal`.

## Запуск

1. Убедитесь, что создан `deploy/.env` (на основе `deploy/.env.example`). При необходимости добавьте переменные из `deploy/observability/.env.observability.example`.
2. Запустите базовый стек вместе с оверлеем наблюдаемости:

```bash
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.observability.yml up -d
```

3. Остановка:

```bash
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.observability.yml down
```

## Веб-интерфейсы (HTTPS через Caddy)

Все UI доступны по `https://${SITE_ADDRESS:-localhost}`:

| Интерфейс | URL | Аутентификация |
| --- | --- | --- |
| **Grafana** | `https://localhost/grafana/` | `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` (по умолчанию `admin` / `admin`) |
| **VictoriaMetrics UI (VMUI)** | `https://localhost/vm/vmui/` | Caddy Basic Auth (`OBS_BASIC_AUTH_USER` / `OBS_BASIC_AUTH_HASH`) |
| **VictoriaLogs UI (VLUI)** | `https://localhost/vl/select/vmui/` | Caddy Basic Auth |
| **vmalert UI** | `https://localhost/vmalert/` | Caddy Basic Auth |
| **Alertmanager UI** | `https://localhost/alertmanager/` | Caddy Basic Auth |

> По умолчанию для Basic Auth в локальной разработке задан пользователь `admin` с паролем `secret`. Перед деплоем обязательно сгенерируйте новый bcrypt-хеш:
>
> ```bash
> docker run --rm caddy:2-alpine caddy hash-password --plaintext 'ваш-надёжный-пароль'
> ```
>
> и пропишите `OBS_BASIC_AUTH_USER` и `OBS_BASIC_AUTH_HASH` в `deploy/.env`.

## Плагин VictoriaLogs для Grafana в изолированной сети

Источник данных `VictoriaLogs` (`victoriametrics-logs-datasource`) преднастроен в `deploy/observability/grafana/provisioning/datasources/datasources.yml`.
Поскольку контейнер `grafana` работает в сети `internal: true` без выхода в интернет, он не может скачать плагин при старте. Для работы панели логов внутри Grafana скачайте релиз `victoriametrics-logs-datasource` заранее и распакуйте его в каталог `/var/lib/grafana/plugins/victoriametrics-logs-datasource` (внутри volume `grafana-data`). Независимо от плагина Grafana, **VictoriaLogs UI (VLUI)** доступен напрямую по `https://localhost/vl/select/vmui/`.

## Проверка конфигурации и живого стека

Статическая проверка YAML, JSON, объединения Compose и правил INV-10:

```bash
bash deploy/observability/validate.sh --static
```

Проверка живого запущенного стека (HTTPS-эндпоинты и проверка портов контейнеров):

```bash
bash deploy/observability/validate.sh --live
```
