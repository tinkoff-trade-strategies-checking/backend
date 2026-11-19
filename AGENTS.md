# backend

Модульный монолит проекта «Т-Инвестиции: тренажёр и лаборатория стратегий» на Java 25 и Spring Boot 4. Принимает команды фронтенда по REST и отдаёт события по SSE, хранит данные приложения в PostgreSQL, читает каталог бумаг из ClickHouse и запускает симуляции в C++ Simulation Worker по gRPC.

Общие знания — в репозитории `documentation` (локально `../documentation/`, на GitHub: https://github.com/tinkoff-trade-strategies-checking/documentation). Карта документов — `README.md` там; читай только то, что нужно для задачи. Процесс работы и указатели на остальные репозитории — в `home` (`../home/`).

Если документация репозитория противоречит существующему коду, следуй соглашениям кода и укажи расхождения в финальном ответе. Исключение — инварианты из `documentation/invariants.md`: их нарушение в коде считается багом.

## Стек и связи
- Java 25 (Gradle toolchain с автоскачиванием через Foojay), Gradle (Kotlin DSL), версии в `gradle/libs.versions.toml`.
- Spring Boot 4 + Spring Modulith 2 (корневой пакет `ru.tinvest.trainer`).
- PostgreSQL + Flyway (по схеме на модуль с таблицами).
- Качество: Spotless + `palantir-java-format`, статический анализ ErrorProne (ошибки роняют сборку), JUnit + Testcontainers.
- Внешние связи (добавляются отдельными задачами): REST/SSE по `api/openapi.yaml` (`backend#8`), gRPC к C++ Simulation Worker по копии `core/proto/` в `proto/` (`backend#9`), локальное окружение `deploy/` (`backend#10`).

## Как работать над задачей
Полностью — `home/workflow.md`. Коротко:
1. Работа идёт по issue с доски https://github.com/orgs/tinkoff-trade-strategies-checking/projects/2. Перед стартом прочитай issue вместе с комментариями.
2. Прочитай ФТ, INV и решения раздела «Архитектура» в `documentation/decisions.md`. Отход от принятого решения — только явно, PR в `decisions.md` с причиной.
3. Ветка `<тип>/<номер-issue>` в worktree (из `home`: `sh scripts/worktree.sh start backend <тип> <N>`), изменения вместе с тестами, PR с `Closes #N`.
4. Изменение сделало документы неверными — обнови их в том же PR.

## Критично
- **Границы модулей.** Зависимости между модулями проверяет `ModularityTest` (`ApplicationModules.verify()`). Межмодульное взаимодействие — только через публичный API и события.
- **INV-5 (неизменяемость).** Опубликованная версия стратегии (`workshop.strategy_version`) и снапшот (`marketdata.snapshot`) не меняются: `UPDATE`/`DELETE` запрещены триггерами в БД (миграции V2, V3); прогон (`simulation.run`) хранит ID версий без внешних ключей между схемами, существование проверяет код. Тесты: `StrategyVersionImmutabilityTest`, `SnapshotImmutabilityTest`, `RunRepeatTest` (`src/test/java/.../{workshop,marketdata,simulation}`), общая база — `PostgresIntegrationTest`.
- **INV-6 (деньги).** Денежные суммы и цены — только `BigDecimal` (масштаб и `RoundingMode` — по `decisions.md`, «Деньги и округление»), без `float`/`double`.
- **INV-7 (слепой режим).** Маскирование названий, тикеров, реальных дат и рублёвых сумм — в одном месте, с контрактным тестом; данные слепого режима не должны попадать в ответы об ошибках и логи.
- **INV-8 (восстановление сессии).** Ручная сессия хранится как журнал команд; состояние восстанавливается проигрыванием журнала.

## Стандарты
Общие стандарты — `home/standards.md`. Специфика Java/Spring для `backend`:
- **Формат и статический анализ.** `palantir-java-format` через Spotless (`./gradlew spotlessApply` / `./gradlew spotlessCheck`) и ErrorProne при компиляции — проверяются в CI, падение = ошибка.
- **Ошибки API.** Формат `ProblemDetail` (RFC 9457, `application/problem+json`), общий обработчик `ApiExceptionHandler` в модуле `api`. Ошибка входа — `InvalidRequestException` (`400`), нет ресурса — `NotFoundException` (`404`), недоступная зависимость — `DependencyUnavailableException` или ошибка доступа к БД (`503`); `IllegalArgumentException` и прочие JDK-исключения — `500`, их текст клиенту не уходит; на `500` клиенту не отдаются стек, внутренние сообщения, секреты и данные слепого режима.
- **Деньги (INV-6).** Только `BigDecimal`, масштаб и `RoundingMode` — по `documentation/decisions.md`, «Деньги и округление» (INV-6). Никаких `double`/`float` для цен, сумм и комиссий.
- **DTO и сущности.** DTO API (Java `record`) отделены от сущностей и таблиц БД; сущности БД не отдаются напрямую из контроллеров.
- **Слепой режим (INV-7).** Единая точка маскирования для ответов слепой сессии; все изменения покрываются контрактным тестом.
- **Зависимости и генерация.** Новые зависимости — с версией в `gradle/libs.versions.toml` и записью в `documentation/decisions.md` («Архитектура»). Сгенерированный код (gRPC-стабы и т. п.) не коммитится.
- **Язык.** Идентификаторы, API, таблицы и колонки — на английском; комментарии, документация и сообщения коммитов — на русском.

## Структура
Корневой пакет: `ru.tinvest.trainer` (`TrainerApplication`). Модули верхнего уровня (`documentation/architecture.md`, «Модули бэкенда»):
- `api` — общие правила HTTP API, безопасность, `ApiExceptionHandler` (RFC 9457 `ProblemDetail`) и `GET /api/health`.
- `identity` — учётные записи и вход пользователя (схема PostgreSQL `identity`).
- `config` — общие настройки приложения.
- `worker` — связь с Simulation Worker по gRPC, передача заданий и результатов. Публичный интерфейс — `SimulationWorkerClient` (пока `ping()`); реализация на `ManagedChannel`, адрес `worker.address` (env `WORKER_ADDRESS`, по умолчанию `localhost:50051`), дедлайн вызова `worker.deadline` (env `WORKER_DEADLINE`, по умолчанию `5s`); `UNAVAILABLE` и `DEADLINE_EXCEEDED` → `DependencyUnavailableException` (503).
- `marketdata` — каталог бумаг и доступной истории; подбор отрезка выбранной категории периода (схема `marketdata`).
- `workshop` — черновики стратегий, публикация неизменяемых версий, форки и скрытие из каталога (схема `workshop`).
- `sessions` — ручная торговля: журнал команд, виртуальное время, плеер, слепой режим, завершение и воспроизведение (схема `sessions`).
- `simulation` — очередь вычислительных заданий, прогоны алгоритмов и повтор прогона (схема `simulation`).
- `analysis` — отчёты, история, каталог алгоритмов, сравнение результатов, разбор расхождений и выгрузка CSV/XLSX (схема `analysis`).

Миграции Flyway — в `src/main/resources/db/migration/` (`V1__baseline.sql` создаёт схемы модулей). Нумерация: `V<N>__<что>.sql`, по возрастанию в одном каталоге; таблицы модуля — только в его схеме. Конфигурация — `src/main/resources/application.yml` (БД из `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, без значений по умолчанию) и профиль `application-local.yml` (значения для локального запуска).

## gRPC-контракт (proto)
`proto/simulation.proto` и `proto/common.proto` — копии `core/proto/` с ветки `main` репозитория `core` (источник правды), без изменений. Java-код (protobuf и gRPC-стабы, пакет `pac.simulation.v1`) генерируется при сборке плагином `com.google.protobuf` в `build/generated` и не коммитится; ErrorProne и Spotless его не проверяют. Джоба CI `proto-sync` падает, если копия отличается от `core/main`. Контракт изменился в `core` — PR в `backend` с новой копией сразу после слияния PR в `core` (порядок контракта — `home/AGENTS.md`).

## Команды
Для интеграционных тестов (Testcontainers) нужен запущенный Docker. На машине без локального JDK 25 Gradle автоматически скачает toolchain 25 через Foojay.

```bash
./gradlew spotlessCheck   # проверка форматирования (palantir-java-format)
./gradlew spotlessApply   # автоформатирование исходников
./gradlew test            # модульные и интеграционные тесты (Modulith, ProblemDetail, Testcontainers PostgreSQL)
./gradlew build           # полная проверка: формат, компиляция с ErrorProne, тесты, сборка JAR
npx -y @redocly/cli@latest lint api/openapi.yaml   # контракт REST и SSE (джоба CI `openapi`)
```

INV-10 проверяет `DeployExposureTest` (без Docker): порты публикует только caddy (80, 443), postgres/clickhouse/worker/backend — только в сетях `internal: true`, `Caddyfile` без http-сайтов и `tls off`/`auto_https off`.

Контракт `api/openapi.yaml` — источник для frontend (копия там, клиент — `ng-openapi-gen`). Сначала меняется он, потом код; несовместимое изменение — только с новой версией пути (`/api/v2`). После слияния изменения — PR во frontend с копией (`node scripts/api.mjs sync`), иначе там падает `api:check`.

Локальный запуск против PostgreSQL (профиль `local`):

```bash
docker run -d --rm --name trainer-pg -p 5432:5432 \
  -e POSTGRES_DB=trainer -e POSTGRES_USER=trainer -e POSTGRES_PASSWORD=trainer \
  postgres:17-alpine
./gradlew bootRun --args='--spring.profiles.active=local'
# Проверка:
curl -s http://localhost:8080/api/health
curl -s http://localhost:8080/actuator/health
# Остановка БД:
docker stop trainer-pg
```

CI (`.github/workflows/ci.yml`): джоба `format` (`./gradlew spotlessCheck --no-daemon`), джоба `build-test` (`./gradlew build -x spotlessCheck --no-daemon`), джоба `proto-sync` (сверка `proto/` с `core/main`), джоба `pr-size` (наблюдение размера PR).

## Окружение
Локальный стек в `deploy/`: `caddy` (HTTPS, единственный с опубликованными портами 80/443, INV-10) → `backend`; внутри сети `postgres` (17), `clickhouse` (25.8) и `worker` (образ `simulation-worker`, собирается из `../core` по его `Dockerfile`).
Соседние репозитории лежат рядом с `backend` (`../core`, `../frontend`); нет локально — `home/scripts/clone-repos.sh`.

```bash
cp deploy/.env.example deploy/.env      # задать POSTGRES_PASSWORD и CLICKHOUSE_PASSWORD
# Образы собираем напрямую: `compose build` через bake требует --allow=fs.read=.. для ../../core
docker build -t simulation-worker ../core          # или путь к core; тяжёлая сборка C++
docker build -f deploy/backend.Dockerfile -t trainer-backend .
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --no-build
curl -k https://localhost/api/health    # 200 (-k: сертификат внутреннего CA Caddy)
docker compose -f deploy/docker-compose.yml --env-file deploy/.env ps   # порты только у caddy
docker compose -f deploy/docker-compose.yml --env-file deploy/.env down -v
```

`CORE_DIR` в `deploy/.env` — путь к репозиторию core для `up --build` (по умолчанию `../../core` от `deploy/`; в worktree задать явно).

Frontend: `FRONTEND_DIST` в `deploy/.env` — каталог сборки (`../../frontend/dist/<имя>/browser`); не задан — Caddy отдаёт заглушку `deploy/frontend-stub`. Сборка образа worker тяжёлая (C++, gRPC).

## Когда обновлять документы
Общая таблица — `home/workflow.md`, раздел «Когда обновлять документы». Для этого репозитория:
- новая команда Gradle, профиль запуска или джоба CI → «Команды» выше;
- новый модуль, пакет, схема БД или точка входа → «Структура» выше и `documentation/architecture.md`;
- новая библиотека или изменение контракта → `documentation/decisions.md` («Архитектура»);
- новый тест инварианта INV-* → колонка «Проверка» в `documentation/invariants.md`.
