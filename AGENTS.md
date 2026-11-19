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
- `identity` — учётные записи и вход пользователя (схема PostgreSQL `identity`): `/api/v1/auth/login`, `/auth/logout`, `GET /me`. Публичный API для других модулей — `identity.CurrentUser` (`requireId()` / `findId()`: UUID вошедшего пользователя); пароли bcrypt (до 72 байт UTF-8), роль одна — `USER`. Безопасность — `api.SecurityConfiguration`: cookie `SESSION` (HttpOnly, Secure, SameSite=Lax; сессии в памяти Tomcat), CSRF `XSRF-TOKEN` / `X-XSRF-TOKEN` (cookie выдаётся любым запросом, в том числе `GET /me` с 401), всё под `/api/v1/**` кроме входа требует входа (401 ProblemDetail), CSRF — 403; `/api/health` и `/actuator/health` открыты. Ограничитель попыток входа (`LoginRateLimiter`): неудачные попытки считаются по нормализованной почте и по IP (`identity.login-limit.max-failures-per-email` = 5, `max-failures-per-ip` = 20, `window` = 5m); сверх лимита — 429 ProblemDetail с `Retry-After`, bcrypt не считается; успешный вход сбрасывает счётчик почты. Счётчики в памяти одного экземпляра: не общие между экземплярами и теряются при рестарте; чужую почту можно временно заблокировать перебором. IP клиента — из `X-Forwarded-For` Caddy (`server.forward-headers-strategy: native`). Сбой БД при входе — 503, не 401. Новый защищённый контроллер ничего настраивать не должен; в тестах MockMvc сессия и CSRF — как в `IdentityApiTest`.
- `config` — общие настройки приложения.
- `worker` — связь с Simulation Worker по gRPC, передача заданий и результатов. Публичный интерфейс — `SimulationWorkerClient` (пока `ping()`); реализация на `ManagedChannel`, адрес `worker.address` (env `WORKER_ADDRESS`, по умолчанию `localhost:50051`), дедлайн вызова `worker.deadline` (env `WORKER_DEADLINE`, по умолчанию `5s`); `UNAVAILABLE` и `DEADLINE_EXCEEDED` → `DependencyUnavailableException` (503).
- `marketdata` — каталог бумаг и доступной истории; подбор отрезка выбранной категории периода (схема `marketdata`). Импортёр архива ISS — пакет `marketdata.importer` (профиль `import`, раздел «Импорт архива ISS»).
- `workshop` — черновики стратегий, публикация неизменяемых версий, форки и скрытие из каталога (схема `workshop`).
- `sessions` — ручная торговля: журнал команд, виртуальное время, плеер, слепой режим, завершение и воспроизведение (схема `sessions`).
- `simulation` — очередь вычислительных заданий, прогоны алгоритмов и повтор прогона (схема `simulation`).
- `analysis` — отчёты, история, каталог алгоритмов, сравнение результатов, разбор расхождений и выгрузка CSV/XLSX (схема `analysis`).

Миграции Flyway — в `src/main/resources/db/migration/` (`V1__baseline.sql` создаёт схемы модулей). Нумерация: `V<N>__<что>.sql`, по возрастанию в одном каталоге; таблицы модуля — только в его схеме. Конфигурация — `src/main/resources/application.yml` (БД из `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, без значений по умолчанию) и профиль `application-local.yml` (значения для локального запуска).

## Создание пользователя
Регистрации нет, учётные записи заводит команда профилем `create-user` (без веб-сервера, нужен PostgreSQL; для локальной БД добавьте `local`). Пароль лучше передавать переменной `USER_PASSWORD` (не виден в списке процессов), допустим `--user.password`:

```bash
USER_PASSWORD='...' ./gradlew bootRun --args='--spring.profiles.active=create-user,local --user.email=trader@example.com'
```

Почта хранится в нижнем регистре и уникальна без учёта регистра; пароль длиннее 72 байт UTF-8 отклоняется. Сброс пароля — команда вручную (решение «Учётные записи»).

## Импорт архива ISS
Импортёр (UC-26, `backend#7`) грузит MOEX ISS и ставки RUONIA ЦБ РФ в ClickHouse как неизменяемый снапшот и регистрирует его в `marketdata.snapshot` (`Snapshots.register`). Запускается тем же приложением под профилем `import` (без веб-сервера), нужны PostgreSQL и ClickHouse:

```bash
./gradlew bootRun --args='--spring.profiles.active=import,local --import.from=2024-01-01 --import.to=2024-12-31 --import.tickers=SBER,GAZP'
```

- Без `--import.tickers` пул — состав индекса IMOEX на `--import.to` (ближайший торговый день не позже). `--import.label=<текст>` — новый снапшот на тех же параметрах.
- ClickHouse: `IMPORT_CLICKHOUSE_URL` (HTTP, например `http://localhost:8123`), `IMPORT_CLICKHOUSE_DATABASE`, `IMPORT_CLICKHOUSE_USER`, `IMPORT_CLICKHOUSE_PASSWORD` — без значений по умолчанию, не заданы — запуск падает. PostgreSQL — `SPRING_DATASOURCE_*` как в `application.yml`; для локального запуска добавьте профиль `local` (`import,local`). `--import.allow-gaps=true` принимает пробелы покрытия (ниже). ISS и повторы — `--import.iss.*` (`pause`, `max-retries`, `initial-backoff`, `max-backoff`, `empty-retries`, `base-url`), ЦБ — `--import.cbr.url`.
- Что грузится: минутные свечи пула с TQBR (`candles`, `interval_sec = 60`, `ts_open` — UTC из МСК = UTC+3, `available_at = ts_open + интервал`), дневной ряд IMOEX (`interval_sec = 86400`), `securities` (лот, шаг цены `MINSTEP`, сектор), `trading_calendar` (торговый день — день с дневной свечой IMOEX), `ruonia`, `corporate_actions` (сплиты и консолидации из `statistics/engines/stock/splits`, таблица сверх контракта `core`), затем `snapshots_ready`. Календарь и ставки берутся с запасом 14 дней до `--import.from`. Бумага не на TQBR, с шагом цены < 1e-6 ₽ (или больше шести знаков) или лотом < 1 пропускается с предупреждением. Цены и шаг цены разбираются без округления (больше шести знаков — ошибка, INV-6), оборот свечи (`value`, double с артефактами в ISS) округляется HALF_UP до 6 знаков. Сектор — по вхождению в отраслевые индексы MOEX на дату состава; нет сектора — пустая строка, несколько — первый по коду индекса, оба случая с предупреждением в логе.
- Идемпотентность: `snapshot_id` = UUID по имени от параметров (`Params.snapshotId`). Зарегистрированный снапшот не меняется (INV-5): повторный запуск с теми же параметрами ничего не делает. Оборванный запуск докачивается: каждая таблица и каждый кусок (бумага × месяц) вставляются одним атомарным `INSERT`, «в таблице есть строки» значит «загружено». Снапшот виден `core` только после `snapshots_ready`; готовый в ClickHouse, но не зарегистрированный в PostgreSQL только регистрируется.
- RUONIA (ЦБ РФ, `cbr.ru/hd_base/ruonia/dynamics`): фильтр по дате ставки (`Posted=True` — только признак формы), ставка хранится по своей дате без `available_at`: `core` в день d берёт `RUONIA_{d-1}` и сам переносит последнюю ставку на выходные (`matchRuoniaRates`), заглядывания вперёд нет. Ставка за дату D выходит в D+1, поэтому за последние 1–2 рабочих дня её может не быть; на праздники ЦБ ставку не публикует.
- Целостность: пустая страница при известном `TOTAL` (аналитика, состав индексов) — ошибка, а не конец данных. У свечей курсора нет, поэтому покрытие проверяется ДО вставки куска «бумага × месяц»: ожидаемые дни — торговые дни календаря IMOEX внутри куска и периода торгов бумаги на TQBR (`listed_from`…`listed_till` из ISS; нет данных — весь кусок); в каждый ожидаемый день должны быть свечи, и последняя — не раньше начала последней 10-минутной свечи IMOEX за этот день минус `--import.end-of-day-tolerance` (по умолчанию `30m`; обрыв посреди дня; на 03.2024 IMOEX — 18:50, SBER — 23:49 МСК). Не проверяется: пропуск минут внутри дня и день, где бумага торговалась меньше индекса в пределах допуска; день без внутридневных свечей IMOEX проверяется только на наличие свечей. Проблема — отчёт в лог и `ImportException`, кусок не вставляется (докачка его перекачает). `--import.allow-gaps=true` принимает проблемы с предупреждением и принятые проблемы остаются в снапшоте. В `description` всегда пишутся пробелы, найденные по ClickHouse (`пробелы: <бумага>: N дн. [даты]`, больше пяти дат — первые три и последние две через «…», независимо от флага последнего запуска; оборванные дни — только из текущего запуска), и `allow-gaps: включён`, если флаг задан.
- Календарь IMOEX проверяется до вставки годового куска. Пустой ответ — всегда `ImportException` (иначе пустой календарь попал бы в неизменяемый снапшот). Подозрительно редкий ряд — месяц, целиком внутри куска, с долей торговых дней меньше 50 % будней, или первый/последний день дальше 10 дней от границы куска — бывает и на настоящих событиях (остановка торгов 28.02–23.03.2022), поэтому без `--import.allow-gaps=true` это `ImportException` с объяснением, с флагом — WARN и запись `календарь: 2022-03 6 из 23 будних` в `description` (по данным ClickHouse, как пробелы). Неполные месяцы на краях и доля за весь кусок не проверяются: на коротком периоде с январём (~74 % будней) они ложно малы. Сверить с ISS-календарём нельзя: `trading_calendar` выводится из этого же ряда.
- Параллельный запуск с теми же параметрами невозможен: advisory lock PostgreSQL по ключу снапшота. ISS и ЦБ получают `User-Agent: trainer-importer/<версия>`.
- ISS: пауза между запросами, ошибка (сеть, не 200, не JSON) — повтор с экспоненциальной задержкой, пустая страница — до `empty-retries` повторов, затем конец данных; следующая страница нужна, пока страница полная (500 для свечей) или не набран `TOTAL` из `<блок>.cursor`.
- Тесты `marketdata/importer`: поддельный ISS на JDK `HttpServer` с образцами из `src/test/resources/iss`, настоящие ClickHouse (Testcontainers) и PostgreSQL, сети нет.

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
