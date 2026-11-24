# Развёртывание в Kubernetes (Kustomize)

Манифесты Kustomize для запуска приложения **Тренажёр торговых стратегий** (`trainer`) и полного стека наблюдаемости в Kubernetes с соблюдением инварианта сетевой изоляции **INV-10**.

## Архитектура

Все компоненты размещаются в namespace `trainer`.

### Приложение (`base/app/`)

| Компонент | Тип ресурса | Порты (внутренние) | Назначение |
| :--- | :--- | :--- | :--- |
| `frontend` | Deployment + Service + PDB | `8080` (HTTP) | Раздача собранного Angular SPA (`nginx-unprivileged`) |
| `backend` | Deployment + Service + HPA + PDB | `8080` (API), `8081` (management/metrics) | Spring Boot API, probes и `/actuator/prometheus` на `:8081` |
| `simulation-worker` | Deployment + Service + PDB | `50051` (gRPC) | C++20 воркер симуляций |
| `postgres` | StatefulSet + PVC + Service + PDB | `5432` (PostgreSQL), `9187` (`postgres_exporter`) | PostgreSQL 17 + sidecar экспортёр метрик |
| `clickhouse` | StatefulSet + PVC + Service + PDB | `8123` (HTTP), `9000` (Native), `9363` (Prometheus) | ClickHouse 25.8 с нативным экспортёром метрик |

### Стек наблюдаемости (`base/observability/`)

| Компонент | Тип ресурса | Порт | UI / Назначение |
| :--- | :--- | :--- | :--- |
| **VictoriaMetrics** | StatefulSet + PVC + Service | `8428` | Хранилище метрик + встроенный **VMUI** (`/vmui`) |
| **VictoriaLogs** | StatefulSet + PVC + Service | `9428` | Хранилище логов + встроенный **VLUI** (`/select/vmui/`) |
| **vmagent** | Deployment + RBAC + Service | `8429` | Сбор метрик (Kubernetes SD по аннотациям `prometheus.io/*` + статические таргеты) |
| **vmalert** | Deployment + Service | `8880` | Вычисление правил алертинга (`vmalert-rules`) + веб-интерфейс |
| **Alertmanager** | Deployment + Service | `9093` | Маршрутизация, дедупликация и группировка алертов + UI |
| **Grafana** | Deployment + PVC + Service | `3000` | Дашборды с провижинингом источников (VictoriaMetrics, VictoriaLogs, Alertmanager) |
| **Vector** | DaemonSet + RBAC + Service | `9598` | Сбор JSON-логов подов в namespace `trainer` и отправка в VictoriaLogs |

### Сетевая изоляция (INV-10)

Файл [`base/network-policies.yaml`](base/network-policies.yaml) реализует политику **default-deny** на входящий и исходящий трафик для всех подов в `trainer` с явными правилами:

- Внешний трафик проходит только через **HTTPS Ingress** (`ssl-redirect: true`, `force-ssl-redirect: true`, TLS через `cert-manager`).
- Ingress-контроллер имеет доступ только к `frontend:8080`, `backend:8080` (но **не** к `backend:8081`) и UI наблюдаемости.
- К `postgres:5432`, `clickhouse:8123` и `simulation-worker:50051` имеет доступ только `backend` (и `simulation-worker` к `clickhouse:8123`).
- К портам метрик (`backend:8081`, `postgres:9187`, `clickhouse:9363` и др.) имеет доступ только `vmagent`.

## Предварительные требования

- Кластер Kubernetes 1.28+ (`kind`, `minikube`, `k3s` или managed K8s) с поддержкой `NetworkPolicy` (например, Calico или Cilium)
- `kubectl` 1.28+ (или `kustomize` 5+)
- Установленный `ingress-nginx` (в namespace `ingress-nginx`)
- Установленный `cert-manager` с настроенным `ClusterIssuer` (`selfsigned-issuer` для `dev`, `letsencrypt-prod` для `prod`)
- `metrics-server` (для работы HPA у `backend`)

## Локальный запуск (`kind` / `minikube` / `k3s`)

1. Подготовьте кластер и включите `ingress-nginx` и `cert-manager`:

```bash
# Пример для minikube
minikube start --cni=calico --cpus=4 --memory=8192
minikube addons enable ingress
minikube addons enable metrics-server
```

2. Соберите локальные образы (или укажите свои теги в `overlays/dev/kustomization.yaml` через блок `images:`):

```bash
docker build -f deploy/backend.Dockerfile -t ghcr.io/tinkoff-trade-strategies-checking/trainer-backend:latest .
docker build -t ghcr.io/tinkoff-trade-strategies-checking/simulation-worker:latest ../core
```

3. Примените dev-оверлей:

```bash
kubectl apply -k deploy/k8s/overlays/dev
kubectl -n trainer get pods
```

4. Добавьте локальные домены в `/etc/hosts` (указав IP Ingress-контроллера):

```text
127.0.0.1 trainer.local grafana.obs.trainer.local vmui.obs.trainer.local vlui.obs.trainer.local alertmanager.obs.trainer.local vmalert.obs.trainer.local
```

## Развёртывание в `dev` и `prod`

```bash
# Проверка сгенерированных манифестов перед применением
kubectl kustomize deploy/k8s/overlays/dev
kubectl kustomize deploy/k8s/overlays/prod

# Валидация YAML и схем
bash deploy/k8s/validate.sh

# Применение в кластер
kubectl apply -k deploy/k8s/overlays/dev
# или для production:
kubectl apply -k deploy/k8s/overlays/prod
```

## Управление секретами

Файл [`base/secrets.yaml`](base/secrets.yaml) содержит **только шаблоны с заглушками** (`CHANGE_ME_*`). Реальные пароли и токены **запрещено** хранить в Git (`INV-7`).

Для production рекомендуется один из подходов:

- **External Secrets Operator (ESO)** — синхронизация секретов `trainer-db-credentials` и `grafana-admin-credentials` из HashiCorp Vault / AWS Secrets Manager / Yandex Lockbox (пример приведён в комментариях `base/secrets.yaml`).
- **Bitnami Sealed Secrets** или **Mozilla SOPS** — хранение зашифрованных манифестов в Git.

## Доступ к интерфейсам наблюдаемости

### Через HTTPS Ingress

- **Grafana**: `https://grafana.obs.trainer.example.com/` (в `dev`: `https://grafana.obs.trainer.local/`)
- **VictoriaMetrics UI (VMUI)**: `https://vmui.obs.trainer.example.com/vmui`
- **VictoriaLogs UI (VLUI)**: `https://vlui.obs.trainer.example.com/select/vmui/`
- **Alertmanager**: `https://alertmanager.obs.trainer.example.com/`
- **vmalert**: `https://vmalert.obs.trainer.example.com/`

### Через `kubectl port-forward` (без публикации наружу)

```bash
kubectl -n trainer port-forward svc/grafana 3000:3000
kubectl -n trainer port-forward svc/victoriametrics 8428:8428
kubectl -n trainer port-forward svc/victorialogs 9428:9428
kubectl -n trainer port-forward svc/alertmanager 9093:9093
kubectl -n trainer port-forward svc/vmalert 8880:8880
```
