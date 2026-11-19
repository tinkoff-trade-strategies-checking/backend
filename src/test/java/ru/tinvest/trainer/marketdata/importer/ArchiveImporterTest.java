package ru.tinvest.trainer.marketdata.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import ru.tinvest.trainer.PostgresIntegrationTest;
import ru.tinvest.trainer.marketdata.Snapshots;

/**
 * Импорт целиком: поддельные ISS и ЦБ, настоящие ClickHouse (Testcontainers) и PostgreSQL. Данные читаются теми же
 * запросами, что и {@code core}.
 */
class ArchiveImporterTest extends PostgresIntegrationTest {

    private static final GenericContainer<?> clickhouse = new GenericContainer<>(
                    "clickhouse/clickhouse-server:25.8-alpine")
            .withEnv("CLICKHOUSE_USER", "ttsc")
            .withEnv("CLICKHOUSE_PASSWORD", "secret")
            .withEnv("CLICKHOUSE_DB", "ttsc")
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123));

    private static final LocalDate FROM = LocalDate.of(2024, 2, 28);
    private static final LocalDate TO = LocalDate.of(2024, 3, 12);

    @Autowired
    private Snapshots snapshots;

    @Autowired
    private javax.sql.DataSource dataSource;

    private FakeIssServer server;
    private ClickHouse ch;
    private ArchiveImporter importer;

    @BeforeAll
    static void startClickHouse() {
        clickhouse.start();
    }

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeIssServer();
        importer = importer(3);
        ch = new ClickHouse(
                HttpClient.newHttpClient(),
                "http://" + clickhouse.getHost() + ":" + clickhouse.getMappedPort(8123),
                "ttsc",
                "ttsc",
                "secret");
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private ArchiveImporter importer(int maxRetries) {
        HttpClient http = HttpClient.newHttpClient();
        HttpFetcher fetcher =
                new HttpFetcher(http, Duration.ZERO, maxRetries, Duration.ofMillis(1), Duration.ofMillis(1), d -> {});
        ClickHouse chClient = new ClickHouse(
                http,
                "http://" + clickhouse.getHost() + ":" + clickhouse.getMappedPort(8123),
                "ttsc",
                "ttsc",
                "secret");
        return new ArchiveImporter(
                new IssClient(fetcher, server.issUrl(), 2, FakeIssServer.PAGE),
                new CbrRuonia(fetcher, server.cbrUrl(), 2),
                new ClickHouseArchive(chClient),
                snapshots,
                Clock.systemUTC(),
                SnapshotLocks.postgres(dataSource),
                Duration.ofMinutes(8));
    }

    private ArchiveImporter.Params params() {
        return new ArchiveImporter.Params(FROM, TO, java.util.List.of("SBER", "GAZP"), "test-" + UUID.randomUUID());
    }

    private String q(String sql) {
        return ch.execute(sql + " FORMAT TSV").trim();
    }

    @Test
    void loadsSnapshotReadableByCoreQueries() {
        ArchiveImporter.Params p = params();
        UUID id = p.snapshotId();

        ArchiveImporter.Result r = importer.run(p);

        assertThat(r.alreadyLoaded()).isFalse();
        assertThat(r.snapshot().snapshotKey()).isEqualTo(id.toString());
        assertThat(snapshots.findByKey(id.toString())).isPresent();
        assertThat(r.snapshot().description()).contains("MOEX ISS", "GAZP,SBER", "2024-02-28…2024-03-12");
        assertThat(q("SELECT count() FROM snapshots_ready WHERE snapshot_id = '" + id + "'"))
                .isEqualTo("1");

        // торговые дни 28.02–12.03: 8 будней по 3 минутные свечи на бумагу
        long days = FakeIssServer.tradingDays().stream()
                .filter(d -> !d.isBefore(FROM) && !d.isAfter(TO))
                .count();
        assertThat(days).isEqualTo(10);
        assertThat(q("SELECT count() FROM candles WHERE snapshot_id = '" + id + "' AND interval_sec = 60"))
                .isEqualTo(String.valueOf(2 * days * 3));
        // INV-1: бар доступен в ts_open + интервал
        assertThat(q("SELECT count() FROM candles WHERE snapshot_id = '" + id
                        + "' AND available_at != ts_open + INTERVAL interval_sec SECOND"))
                .isEqualTo("0");
        // 10:00 МСК = 07:00 UTC; цена из ISS без искажений
        assertThat(q(
                        "SELECT toString(ts_open), toString(open), volume, toString(value) FROM candles WHERE snapshot_id = '"
                                + id + "' AND secid = 'SBER' AND interval_sec = 60 ORDER BY ts_open LIMIT 1"))
                .isEqualTo("2024-02-28 07:00:00.000\t302.1\t1000\t40851055.7");
        // дневной ряд IMOEX для бенчмарка: интервал 1 сутки, начало 00:00 МСК = 21:00 UTC предыдущих суток
        assertThat(q("SELECT toString(ts_open), toString(available_at) FROM candles WHERE snapshot_id = '" + id
                        + "' AND secid = 'IMOEX' AND interval_sec = 86400 AND ts_open >= '2024-02-27 00:00:00' "
                        + "ORDER BY ts_open LIMIT 1"))
                .isEqualTo("2024-02-27 21:00:00.000\t2024-02-28 21:00:00.000");
        // бумаги: лот, шаг цены, сектор; SBER в двух отраслевых индексах — берётся индекс с наибольшим весом (MOEXOG:
        // 30,25 против 10,5);
        // TINY (шаг < 1e-6) и GONE (нет на TQBR) не попали
        assertThat(q("SELECT secid, sector, lot_size, toString(price_step), name FROM securities WHERE snapshot_id = '"
                        + id + "' ORDER BY secid"))
                .isEqualTo("GAZP\tНефть и газ\t10\t0.01\tГазпром ПАО ао\nSBER\tНефть и газ\t1\t0.01\tСбербанк");
    }

    @Test
    void calendarMarksWeekendsAsNonTrading() {
        ArchiveImporter.Params p = params();
        importer.run(p);
        UUID id = p.snapshotId();

        assertThat(q("SELECT is_trading FROM trading_calendar WHERE snapshot_id = '" + id
                        + "' AND date = '2024-03-02'"))
                .isEqualTo("0");
        assertThat(q("SELECT is_trading FROM trading_calendar WHERE snapshot_id = '" + id
                        + "' AND date = '2024-03-04'"))
                .isEqualTo("1");
        // запас до from: ядру нужен торговый день перед первым днём шкалы
        assertThat(q("SELECT min(date) FROM trading_calendar WHERE snapshot_id = '" + id + "'"))
                .isEqualTo(FROM.minusDays(14).toString());
    }

    @Test
    void ruoniaAndCorporateActionsAreStored() {
        ArchiveImporter.Params p = params();
        importer.run(p);
        UUID id = p.snapshotId();

        assertThat(q("SELECT toString(rate) FROM ruonia WHERE snapshot_id = '" + id + "' AND date = '2024-03-04'"))
                .isEqualTo("20.04");
        // только бумаги снапшота; GAZP 5 -> 1 — консолидация
        assertThat(q("SELECT secid, toString(date), kind, before, after FROM corporate_actions WHERE snapshot_id = '"
                        + id + "' ORDER BY secid"))
                .isEqualTo("GAZP\t2024-03-05\tconsolidation\t5\t1\nSBER\t2024-02-28\tsplit\t1\t10");
    }

    @Test
    void repeatedRunLeavesExistingSnapshotUntouched() {
        ArchiveImporter.Params p = params();
        importer.run(p);
        UUID id = p.snapshotId();
        String rows = q("SELECT count() FROM candles WHERE snapshot_id = '" + id + "'");
        int requests = server.requests.size();

        ArchiveImporter.Result again = importer.run(p);

        assertThat(again.alreadyLoaded()).isTrue();
        assertThat(server.requests).hasSize(requests);
        assertThat(q("SELECT count() FROM candles WHERE snapshot_id = '" + id + "'"))
                .isEqualTo(rows);
        assertThat(q("SELECT count() FROM snapshots_ready WHERE snapshot_id = '" + id + "'"))
                .isEqualTo("1");
    }

    @Test
    void newLabelMakesNewSnapshotAndKeepsTheOld() {
        ArchiveImporter.Params p = params();
        UUID first = importer.run(p).snapshotId();
        ArchiveImporter.Params other = new ArchiveImporter.Params(FROM, TO, p.tickers(), p.label() + "-2");

        ArchiveImporter.Result r = importer.run(other);

        assertThat(r.snapshotId()).isNotEqualTo(first);
        assertThat(r.alreadyLoaded()).isFalse();
        assertThat(q("SELECT count() FROM candles WHERE snapshot_id = '" + first + "' AND secid = 'SBER'"))
                .isEqualTo(q(
                        "SELECT count() FROM candles WHERE snapshot_id = '" + r.snapshotId() + "' AND secid = 'SBER'"));
    }

    @Test
    void interruptedImportResumesWithoutDuplicatesOrRefetch() {
        ArchiveImporter.Params p = params();
        UUID id = p.snapshotId();
        server.broken.add("/SBER/candles");

        assertThatThrownBy(() -> importer(1).run(p)).isInstanceOf(ImportException.class);

        assertThat(snapshots.findByKey(id.toString())).isEmpty();
        assertThat(q("SELECT count() FROM snapshots_ready WHERE snapshot_id = '" + id + "'"))
                .isEqualTo("0");
        // бумаги грузятся по алфавиту: GAZP успел загрузиться целиком, SBER — ни одного куска
        assertThat(q("SELECT count() FROM candles WHERE snapshot_id = '" + id + "' AND secid = 'GAZP'"))
                .isEqualTo("30");
        assertThat(q("SELECT count() FROM candles WHERE snapshot_id = '" + id + "' AND secid = 'SBER'"))
                .isEqualTo("0");

        server.broken.clear();
        server.requests.clear();
        ArchiveImporter.Result r = importer.run(p);

        assertThat(r.alreadyLoaded()).isFalse();
        // GAZP, IMOEX и список бумаг не запрашивались заново
        assertThat(server.requestsContaining("/GAZP/candles")).isZero();
        // дневной ряд IMOEX не перекачивается (10-минутный для проверки конца сессии — нужен)
        assertThat(server.requests).noneMatch(req -> req.contains("/IMOEX/candles") && req.contains("interval=24"));
        assertThat(q("SELECT count() FROM candles WHERE snapshot_id = '" + id + "' AND secid = 'SBER'"))
                .isEqualTo("30");
        assertThat(q("SELECT count() FROM candles WHERE snapshot_id = '" + id + "' AND secid = 'GAZP'"))
                .isEqualTo("30");
        assertThat(q("SELECT count() FROM securities WHERE snapshot_id = '" + id + "'"))
                .isEqualTo("2");
        assertThat(snapshots.findByKey(id.toString())).isPresent();
        // докачка частями даёт тот же хэш, что загрузка за один раз
        assertThat(r.snapshot().dataHash())
                .isEqualTo(importer.run(params()).snapshot().dataHash());
    }

    @Test
    void legacySchemaLoadIsNotResumedSilently() {
        ArchiveImporter.Params p = params();
        ClickHouseArchive archive = new ClickHouseArchive(ch);
        archive.createSchema();
        archive.insertSecurities(
                p.snapshotId(ArchiveImporter.LEGACY_SCHEMA_VERSION),
                java.util.List.of(
                        new ClickHouseArchive.SecurityRow("SBER", "", 1, new java.math.BigDecimal("0.01"), "")));

        assertThatThrownBy(() -> importer.run(p))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("недогруженный снапшот")
                .hasMessageContaining(
                        p.snapshotId(ArchiveImporter.LEGACY_SCHEMA_VERSION).toString());
        assertThat(snapshots.findByKey(p.snapshotId().toString())).isEmpty();
    }

    private static Candle candle(String time, String close) {
        java.math.BigDecimal c = new java.math.BigDecimal(close);
        return new Candle(java.time.Instant.parse(time), c, c, c, c, 100, c);
    }

    private String hashOfCloses(String first, String second) {
        UUID id = UUID.randomUUID();
        ClickHouseArchive archive = new ClickHouseArchive(ch);
        archive.createSchema();
        archive.insertCandles(
                id,
                "SBER",
                60,
                java.util.List.of(candle("2024-03-04T07:00:00Z", first), candle("2024-03-04T07:01:00Z", second)));
        return archive.dataHash(id, ArchiveImporter.SCHEMA_VERSION);
    }

    @Test
    void hashSeesValuesNotOnlyRowCounts() {
        String base = hashOfCloses("10", "20");

        // то же число строк и тот же набор значений, но на других свечах; и одно другое значение
        assertThat(hashOfCloses("20", "10")).isNotEqualTo(base);
        assertThat(hashOfCloses("10", "21")).isNotEqualTo(base);
        assertThat(hashOfCloses("10", "20")).isEqualTo(base);
    }

    @Test
    void hashOfFixedFixtureMatchesReference() {
        // эталон: хэш не должен меняться при обновлении ClickHouse и кода; изменился — это новая версия схемы
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000042");
        ClickHouseArchive archive = new ClickHouseArchive(ch);
        archive.createSchema();
        archive.insertSecurities(
                id,
                java.util.List.of(new ClickHouseArchive.SecurityRow(
                        "SBER", "Финансы", 10, new java.math.BigDecimal("0.01"), "Сбербанк")));
        archive.insertCandles(
                id,
                "SBER",
                60,
                java.util.List.of(candle("2024-03-04T07:00:00Z", "290.5"), candle("2024-03-04T07:01:00Z", "291.25")));
        archive.insertCalendar(id, java.util.Map.of(LocalDate.of(2024, 3, 4), true));
        archive.insertRuonia(id, java.util.Map.of(LocalDate.of(2024, 3, 4), new java.math.BigDecimal("16.05")));
        archive.insertActions(
                id,
                java.util.List.of(new ClickHouseArchive.ActionRow("SBER", LocalDate.of(2024, 2, 28), "split", 1, 10)));

        assertThat(archive.dataHash(id, "2"))
                .isEqualTo("06939b914988b7ddf17ffffedb5d15d20a9276866f02126f70da89059962b98a");
    }

    @Test
    void snapshotReadyInClickHouseButMissingInPostgresIsOnlyRegistered() {
        ArchiveImporter.Params p = params();
        UUID id = p.snapshotId();
        // обрыв между snapshots_ready и регистрацией в PostgreSQL
        ClickHouseArchive archive = new ClickHouseArchive(ch);
        archive.createSchema();
        archive.markReady(id);

        ArchiveImporter.Result r = importer.run(p);

        assertThat(r.alreadyLoaded()).isFalse();
        assertThat(snapshots.findByKey(id.toString())).isPresent();
        assertThat(server.requests).isEmpty();
    }

    @Test
    void missingTradingDayBlocksReadyAndReportsGaps() {
        ArchiveImporter.Params p = params();
        UUID id = p.snapshotId();
        server.skipDays.add("GAZP|2024-03-05");

        assertThatThrownBy(() -> importer.run(p))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("GAZP")
                .hasMessageContaining("2024-03-05");

        assertThat(q("SELECT count() FROM snapshots_ready WHERE snapshot_id = '" + id + "'"))
                .isEqualTo("0");
        assertThat(snapshots.findByKey(id.toString())).isEmpty();
    }

    @Test
    void lostTailOfPeriodIsAGapAndChunkIsNotInserted() {
        ArchiveImporter.Params p = params();
        server.skipDays.add("GAZP|2024-03-11");
        server.skipDays.add("GAZP|2024-03-12");

        assertThatThrownBy(() -> importer.run(p))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("2024-03-11")
                .hasMessageContaining("2024-03-12");

        // мартовский кусок GAZP не вставлен целиком, февральский (6 свечей) на месте
        assertThat(q("SELECT count() FROM candles WHERE snapshot_id = '" + p.snapshotId() + "' AND secid = 'GAZP'"))
                .isEqualTo("6");
        assertThat(q("SELECT count() FROM snapshots_ready WHERE snapshot_id = '" + p.snapshotId() + "'"))
                .isEqualTo("0");
    }

    @Test
    void lostStartOfPeriodIsAGap() {
        ArchiveImporter.Params p = params();
        server.skipDays.add("GAZP|2024-02-28");
        server.skipDays.add("GAZP|2024-02-29");

        assertThatThrownBy(() -> importer.run(p))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("2024-02-28")
                .hasMessageContaining("нет свечей");
    }

    @Test
    void dayCutInTheMiddleIsAGapAndFixedByRerun() {
        ArchiveImporter.Params p = params();
        server.truncateDays.add("GAZP|2024-03-06");

        assertThatThrownBy(() -> importer.run(p))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("2024-03-06")
                .hasMessageContaining("оборван день");

        // ISS «вылечился»: докачка перекачивает кусок и снапшот становится готовым
        server.truncateDays.clear();
        assertThat(importer.run(p).alreadyLoaded()).isFalse();
        assertThat(q("SELECT count() FROM candles WHERE snapshot_id = '" + p.snapshotId() + "' AND secid = 'GAZP'"))
                .isEqualTo("30");
    }

    @Test
    void securityListedInTheMiddleOfPeriodIsNotAGapWhenListingIsKnown() {
        ArchiveImporter.Params p = params();
        // GAZP торгуется на TQBR с 04.03: свечей до этой даты нет, и это не пробел
        server.listings.put("GAZP", new LocalDate[] {LocalDate.of(2024, 3, 4), LocalDate.of(2030, 1, 1)});
        for (String d : new String[] {"2024-02-28", "2024-02-29", "2024-03-01"}) {
            server.skipDays.add("GAZP|" + d);
        }

        ArchiveImporter.Result r = importer.run(p);

        assertThat(r.alreadyLoaded()).isFalse();
        assertThat(q("SELECT count() FROM candles WHERE snapshot_id = '" + p.snapshotId() + "' AND secid = 'GAZP'"))
                .isEqualTo("21");
        assertThat(r.snapshot().description()).doesNotContain("пробел").doesNotContain("allow-gaps");
    }

    @Test
    void allowGapsWritesAcceptedGapsIntoDescription() {
        ArchiveImporter.Params p = params();
        server.skipDays.add("GAZP|2024-03-05");
        server.truncateDays.add("SBER|2024-03-06");
        ArchiveImporter.Params lenient = new ArchiveImporter.Params(p.from(), p.to(), p.tickers(), p.label(), true);

        String description = importer.run(lenient).snapshot().description();

        assertThat(description)
                .contains("allow-gaps: включён")
                .contains("GAZP: 1 дн. [2024-03-05]")
                .contains("SBER: 1 дн. [2024-03-06]");
    }

    @Test
    void emptyImoexSeriesFailsInsteadOfProducingEmptyCalendar() {
        ArchiveImporter.Params p = params();
        server.empties.put("/IMOEX/candles", new AtomicInteger(100));

        assertThatThrownBy(() -> importer.run(p))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("пустой дневной ряд");
        assertThat(q("SELECT count() FROM candles WHERE snapshot_id = '" + p.snapshotId() + "'"))
                .isEqualTo("0");
    }

    @Test
    void sparseImoexSeriesNeedsAllowGapsAndIsRecordedInDescription() {
        ArchiveImporter.Params p = params();
        // ряд кончается 2024-03-01, за 11 дней до конца периода: остановка торгов или усечённый ответ
        for (String d : new String[] {"04", "05", "06", "07", "08", "11", "12"}) {
            server.skipDays.add("IMOEX|2024-03-" + d);
        }

        assertThatThrownBy(() -> importer.run(p))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("ряд кончается 2024-03-01")
                .hasMessageContaining("остановка торгов")
                .hasMessageContaining("--import.allow-gaps=true");
        assertThat(q("SELECT count() FROM candles WHERE snapshot_id = '" + p.snapshotId() + "'"))
                .isEqualTo("0");

        ArchiveImporter.Params lenient = new ArchiveImporter.Params(p.from(), p.to(), p.tickers(), p.label(), true);
        String description = importer.run(lenient).snapshot().description();

        assertThat(description)
                .contains("календарь: ряд кончается 2024-03-01, ожидалось около 2024-03-12")
                .contains("allow-gaps: включён");
        assertThat(q("SELECT countIf(is_trading) FROM trading_calendar WHERE snapshot_id = '" + p.snapshotId()
                        + "' AND date >= '2024-03-04'"))
                .isEqualTo("0");
    }

    @Test
    void massGapsAreTruncatedInDescription() {
        ArchiveImporter.Params p = params();
        for (LocalDate d : FakeIssServer.tradingDays()) {
            if (d.isAfter(FROM) && d.isBefore(LocalDate.of(2024, 3, 12))) {
                server.skipDays.add("GAZP|" + d);
            }
        }
        ArchiveImporter.Params lenient = new ArchiveImporter.Params(p.from(), p.to(), p.tickers(), p.label(), true);

        String description = importer.run(lenient).snapshot().description();

        // 8 торговых дней 29.02–11.03: показаны первые три и последние два
        assertThat(description)
                .contains("GAZP: 8 дн. [2024-02-29, 2024-03-01, 2024-03-04, …, 2024-03-08, 2024-03-11]")
                .doesNotContain("2024-03-05")
                .doesNotContain("календарь");
    }

    @Test
    void gapsAcceptedByInterruptedRunAreInDescriptionOfTheRunThatFinishes() {
        ArchiveImporter.Params p = params();
        ArchiveImporter.Params lenient = new ArchiveImporter.Params(p.from(), p.to(), p.tickers(), p.label(), true);
        server.skipDays.add("GAZP|2024-03-05");
        server.broken.add("/SBER/candles");
        assertThatThrownBy(() -> importer(0).run(lenient)).isInstanceOf(ImportException.class);

        // докачка без флага: GAZP уже загружен с дырой, она всё равно попадает в description
        server.broken.clear();
        String description = importer.run(p).snapshot().description();

        assertThat(description).contains("пробелы: GAZP: 1 дн. [2024-03-05]").doesNotContain("allow-gaps");
    }

    @Test
    void stateOfOneRunDoesNotLeakIntoTheNext() {
        ArchiveImporter.Params first = params();
        ArchiveImporter.Params firstLenient =
                new ArchiveImporter.Params(first.from(), first.to(), first.tickers(), first.label(), true);
        server.truncateDays.add("SBER|2024-03-06");
        importer.run(firstLenient);
        server.truncateDays.clear();

        // тот же импортёр, другой снапшот без проблем: чужие принятые пробелы в его description не попадают
        ArchiveImporter.Params second = params();
        String description = importer.run(second).snapshot().description();

        assertThat(description).doesNotContain("пробел").doesNotContain("2024-03-06");
    }

    @Test
    void listingIsNotRequestedWhenAllChunksAreAlreadyLoaded() {
        ArchiveImporter.Params p = params();
        server.broken.add("/SBER/candles");
        assertThatThrownBy(() -> importer(0).run(p)).isInstanceOf(ImportException.class);
        server.broken.clear();
        server.requests.clear();

        importer.run(p);

        // GAZP загружен целиком: его листинг не запрашивался, SBER — запрошен
        assertThat(server.requests).noneMatch(req -> req.contains("/securities/GAZP.json"));
        assertThat(server.requests).anyMatch(req -> req.contains("/securities/SBER.json"));
    }

    @Test
    void gapsAreAcceptedOnlyWithAllowGaps() {
        ArchiveImporter.Params p = params();
        server.skipDays.add("GAZP|2024-03-05");
        ArchiveImporter.Params lenient = new ArchiveImporter.Params(p.from(), p.to(), p.tickers(), p.label(), true);

        ArchiveImporter.Result r = importer.run(lenient);

        assertThat(r.alreadyLoaded()).isFalse();
        assertThat(snapshots.findByKey(lenient.snapshotId().toString())).isPresent();
    }

    @Test
    void secondImportOfSameSnapshotIsRejectedWhileFirstHoldsTheLock() {
        ArchiveImporter.Params p = params();

        try (SnapshotLocks.Held ignored =
                SnapshotLocks.postgres(dataSource).acquire(p.snapshotId().toString())) {
            assertThatThrownBy(() -> importer.run(p))
                    .isInstanceOf(ImportException.class)
                    .hasMessageContaining("уже запущен");
        }

        assertThat(importer.run(p).alreadyLoaded()).isFalse();
    }

    @Test
    void ruoniaIsRequestedByRateDateWithLookbackAndStoredWithoutShift() {
        ArchiveImporter.Params p = params();
        importer.run(p);

        // запрос: с запасом 14 дней до from и до to; даты ставок хранятся как отданы ЦБ (без available_at)
        assertThat(server.requests)
                .anyMatch(r -> r.contains("UniDbQuery.From=14.02.2024") && r.contains("UniDbQuery.To=12.03.2024"));
        assertThat(q("SELECT min(date), max(date) FROM ruonia WHERE snapshot_id = '" + p.snapshotId() + "'"))
                .isEqualTo("2024-02-12\t2024-03-12");
    }

    @Test
    void emptyIssResponsesAreRetried() {
        ArchiveImporter.Params p = params();
        server.empties.put("/SBER/candles", new AtomicInteger(2));
        server.failures.put("/GAZP/candles", new AtomicInteger(2));

        importer.run(p);

        assertThat(q("SELECT count() FROM candles WHERE snapshot_id = '" + p.snapshotId() + "' AND secid = 'SBER'"))
                .isEqualTo("30");
        assertThat(q("SELECT count() FROM candles WHERE snapshot_id = '" + p.snapshotId() + "' AND secid = 'GAZP'"))
                .isEqualTo("30");
    }

    @Test
    void poolWithoutTickersIsTakenFromImoexAndFilteredByPriceStep() {
        ArchiveImporter.Params p =
                new ArchiveImporter.Params(FROM, TO, java.util.List.of(), "pool-" + UUID.randomUUID());

        importer.run(p);

        // IMOEX в поддельном ISS: SBER, GAZP, TINY (шаг 1e-7 — пропуск), GONE (нет на TQBR — пропуск)
        assertThat(q("SELECT groupArray(secid) FROM (SELECT secid FROM securities WHERE snapshot_id = '"
                        + p.snapshotId() + "' ORDER BY secid)"))
                .isEqualTo("['GAZP','SBER']");
    }

    @Test
    void sectorIsTheIndexWithTheLargestWeight() {
        assertThat(ArchiveImporter.sectorIndex(java.util.Map.of(
                        "MOEXFN", new java.math.BigDecimal("10.5"), "MOEXOG", new java.math.BigDecimal("30.25"))))
                .contains("MOEXOG");
        // 9,5 < 10 как числа, а не как строки
        assertThat(ArchiveImporter.sectorIndex(java.util.Map.of(
                        "MOEXFN", new java.math.BigDecimal("9.5"), "MOEXOG", new java.math.BigDecimal("10"))))
                .contains("MOEXOG");
    }

    @Test
    void sectorTieGoesToTheSmallerIndexCode() {
        java.math.BigDecimal w = new java.math.BigDecimal("5.0");
        assertThat(ArchiveImporter.sectorIndex(java.util.Map.of("MOEXOG", w, "MOEXFN", w, "MOEXIT", w)))
                .contains("MOEXFN");
        assertThat(ArchiveImporter.sectorIndex(java.util.Map.of("MOEXOG", w, "MOEXFN", new java.math.BigDecimal("5"))))
                .contains("MOEXFN");
    }

    @Test
    void paperOutsideSectorIndexesHasNoSector() {
        assertThat(ArchiveImporter.sectorIndex(java.util.Map.of())).isEmpty();
    }

    @Test
    void snapshotHashIsStoredAndDoesNotDependOnLabel() {
        ArchiveImporter.Params a = params();
        ArchiveImporter.Params b = params();

        ArchiveImporter.Result ra = importer.run(a);
        ArchiveImporter.Result rb = importer.run(b);

        assertThat(ra.snapshot().dataHash()).matches("[0-9a-f]{64}");
        assertThat(snapshots.findByKey(a.snapshotId().toString()).orElseThrow().dataHash())
                .isEqualTo(ra.snapshot().dataHash());
        assertThat(ra.snapshot().description())
                .contains("хэш: sha256:" + ra.snapshot().dataHash());
        // другой снапшот (метка), те же данные — тот же хэш
        assertThat(rb.snapshotId()).isNotEqualTo(ra.snapshotId());
        assertThat(rb.snapshot().dataHash()).isEqualTo(ra.snapshot().dataHash());
    }

    @Test
    void snapshotHashChangesWhenDataChanges() {
        ArchiveImporter.Params p = params();
        String before = importer.run(p).snapshot().dataHash();
        UUID id = p.snapshotId();

        String same = new ClickHouseArchive(ch).dataHash(id, ArchiveImporter.SCHEMA_VERSION);
        assertThat(same).isEqualTo(before);

        // на самом деле снапшот неизменяем; меняем ClickHouse напрямую только чтобы проверить чувствительность хэша
        ch.execute("INSERT INTO ruonia (snapshot_id, date, rate) VALUES ('" + id + "', '2030-01-01', 1)");
        assertThat(new ClickHouseArchive(ch).dataHash(id, ArchiveImporter.SCHEMA_VERSION))
                .isNotEqualTo(before);
    }

    @Test
    void snapshotHashDiffersForDifferentPeriod() {
        String a = importer.run(params()).snapshot().dataHash();
        ArchiveImporter.Params shorter = new ArchiveImporter.Params(
                FROM, TO.minusDays(3), java.util.List.of("SBER", "GAZP"), "test-" + UUID.randomUUID());

        assertThat(importer.run(shorter).snapshot().dataHash()).isNotEqualTo(a);
    }
}
