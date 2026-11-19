package ru.tinvest.trainer.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import ru.tinvest.trainer.api.DependencyUnavailableException;

/** Запросы каталога к настоящему ClickHouse (Testcontainers) по схеме, которую создаёт импортёр. */
class ClickHouseArchiveCatalogTest {

    private static final GenericContainer<?> clickhouse = new GenericContainer<>(
                    "clickhouse/clickhouse-server:25.8-alpine")
            .withEnv("CLICKHOUSE_USER", "ttsc")
            .withEnv("CLICKHOUSE_PASSWORD", "secret")
            .withEnv("CLICKHOUSE_DB", "ttsc")
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123));

    private static final String SNAP = UUID.randomUUID().toString();
    private static final String OTHER = UUID.randomUUID().toString();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static String url() {
        return "http://" + clickhouse.getHost() + ":" + clickhouse.getMappedPort(8123);
    }

    private static void exec(String sql) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url() + "/?database=ttsc"))
                .header("X-ClickHouse-User", "ttsc")
                .header("X-ClickHouse-Key", "secret")
                .POST(HttpRequest.BodyPublishers.ofString(sql))
                .build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    }

    private static ClickHouseArchiveCatalog catalog(String url) {
        return new ClickHouseArchiveCatalog(HTTP, url, "ttsc", "ttsc", "secret", Duration.ofSeconds(10));
    }

    @BeforeAll
    static void load() throws Exception {
        clickhouse.start();
        exec("CREATE TABLE snapshots_ready (snapshot_id UUID) ENGINE = MergeTree() ORDER BY snapshot_id");
        exec(
                "CREATE TABLE securities (snapshot_id UUID, secid String, sector String, lot_size Int64, "
                        + "price_step Decimal(18, 6), name String DEFAULT '') ENGINE = MergeTree() ORDER BY (snapshot_id, secid)");
        exec("CREATE TABLE candles (snapshot_id UUID, secid LowCardinality(String), interval_sec UInt32, "
                + "ts_open DateTime64(3, 'UTC'), available_at DateTime64(3, 'UTC'), open Decimal(18, 6), "
                + "high Decimal(18, 6), low Decimal(18, 6), close Decimal(18, 6), volume Int64, "
                + "value Decimal(18, 6)) ENGINE = MergeTree() ORDER BY (snapshot_id, secid, interval_sec, ts_open)");
        exec("CREATE TABLE trading_calendar (snapshot_id UUID, date Date, is_trading UInt8) "
                + "ENGINE = MergeTree() ORDER BY (snapshot_id, date)");
        exec("CREATE TABLE corporate_actions (snapshot_id UUID, secid String, date Date, kind String, "
                + "before Int64, after Int64) ENGINE = MergeTree() ORDER BY (snapshot_id, secid, date)");

        exec("INSERT INTO snapshots_ready VALUES ('" + SNAP + "')");
        exec("INSERT INTO securities VALUES ('" + SNAP + "', 'SBER', 'Финансы', 10, 0.01, 'Сбербанк'), ('" + SNAP
                + "', 'GAZP', '', 10, 0.02, ''), ('" + OTHER + "', 'LKOH', 'Нефть и газ', 1, 0.5, 'Лукойл')");
        exec("INSERT INTO trading_calendar VALUES ('" + SNAP + "', '2024-03-05', 1), ('" + SNAP
                + "', '2024-03-04', 1), ('" + SNAP + "', '2024-03-06', 0)");
        // SBER: 04.03 — две минутные свечи, вечерняя 23:49 МСК (20:49 UTC) тоже 04.03; 05.03 00:00 МСК = 04.03 21:00
        // UTC
        exec("INSERT INTO candles (snapshot_id, secid, interval_sec, ts_open, available_at, open, high, low, close, "
                + "volume, value) VALUES "
                + row("SBER", 60, "2024-03-04 07:00:00")
                + ","
                + row("SBER", 60, "2024-03-04 20:49:00")
                + ","
                + row("SBER", 60, "2024-03-04 21:00:00")
                + ","
                + row("GAZP", 60, "2024-03-04 07:00:00")
                + ","
                // 06.03 в календаре нерабочий день (is_trading = 0): торги в выходной вне шкалы сессии
                + row("GAZP", 60, "2024-03-06 07:00:00")
                + ","
                + row("IMOEX", 86400, "2024-03-03 21:00:00")
                + ","
                + row("SBER", 60, "2024-03-10 07:00:00"));
        exec("INSERT INTO corporate_actions VALUES ('" + SNAP + "', 'SBER', '2024-03-04', 'split', 1, 10), ('" + SNAP
                + "', 'GAZP', '2024-04-01', 'split', 1, 2)");
    }

    private static String row(String secid, int interval, String ts) {
        return "('" + SNAP + "', '" + secid + "', " + interval + ", '" + ts + "', '" + ts + "', 1, 1, 1, 1, 1, 1)";
    }

    @AfterAll
    static void stop() {
        clickhouse.stop();
    }

    @Test
    void readinessAndCalendar() {
        var c = catalog(url());
        assertThat(c.isReady(SNAP)).isTrue();
        assertThat(c.isReady(OTHER)).isFalse();
        assertThat(c.tradingDays(SNAP)).containsExactly(LocalDate.of(2024, 3, 4), LocalDate.of(2024, 3, 5));
    }

    @Test
    void intervalsExcludeBenchmark() {
        assertThat(catalog(url()).intervals(SNAP)).containsExactly(60);
    }

    @Test
    void securitiesOfSnapshot() {
        var list = catalog(url()).securities(SNAP);
        assertThat(list).extracting(ArchiveCatalog.ArchivePaper::secid).containsExactly("GAZP", "SBER");
        assertThat(list.get(0).sector()).isEmpty();
        assertThat(list.get(1).sector()).isEqualTo("Финансы");
        assertThat(list.get(1).name()).isEqualTo("Сбербанк");
        assertThat(list.get(0).name()).isEmpty();
        assertThat(list.get(1).lotSize()).isEqualTo(10);
        assertThat(list.get(1).priceStep()).isEqualByComparingTo("0.01");
    }

    @Test
    void securitiesOfArchiveWithoutNameColumnHaveEmptyNames() throws Exception {
        exec("CREATE DATABASE IF NOT EXISTS legacy_arch");
        exec("CREATE TABLE legacy_arch.securities (snapshot_id UUID, secid String, sector String, lot_size Int64, "
                + "price_step Decimal(18, 6)) ENGINE = MergeTree() ORDER BY (snapshot_id, secid)");
        exec("INSERT INTO legacy_arch.securities VALUES ('" + SNAP + "', 'SBER', 'Финансы', 10, 0.01)");
        var legacy = new ClickHouseArchiveCatalog(HTTP, url(), "legacy_arch", "ttsc", "secret", Duration.ofSeconds(10));

        var list = legacy.securities(SNAP);

        assertThat(list).extracting(ArchiveCatalog.ArchivePaper::secid).containsExactly("SBER");
        assertThat(list.get(0).name()).isEmpty();
        assertThat(list.get(0).sector()).isEqualTo("Финансы");
    }

    @Test
    void nameWithEscapedCharactersIsReadBack() throws Exception {
        exec("INSERT INTO securities VALUES ('" + OTHER + "', 'ESC', '', 1, 0.5, 'О\\'Брайен \\\\ ПАО')");

        var list = catalog(url()).securities(OTHER);

        assertThat(list).extracting(ArchiveCatalog.ArchivePaper::name).contains("О'Брайен \\ ПАО");
    }

    @Test
    void candleDaysAreMoscowDatesPerPaper() {
        var c = catalog(url());
        // SBER: 04.03, 05.03 (00:00 МСК = 21:00 UTC предыдущего дня) и 10.03; бенчмарк IMOEX исключён
        assertThat(c.candleDays(SNAP, 60))
                .containsOnlyKeys("SBER", "GAZP")
                .containsEntry(
                        "SBER", Set.of(LocalDate.of(2024, 3, 4), LocalDate.of(2024, 3, 5), LocalDate.of(2024, 3, 10)))
                .containsEntry("GAZP", Set.of(LocalDate.of(2024, 3, 4), LocalDate.of(2024, 3, 6)));
        assertThat(c.candleDays(SNAP, 300)).isEmpty();
    }

    @Test
    void corporateActionsPerPaper() {
        assertThat(catalog(url()).corporateActions(SNAP))
                .containsEntry("SBER", List.of(LocalDate.of(2024, 3, 4)))
                .containsEntry("GAZP", List.of(LocalDate.of(2024, 4, 1)));
    }

    private static List<LocalDate> days(String... iso) {
        return java.util.Arrays.stream(iso).map(LocalDate::parse).toList();
    }

    @Test
    void sessionStepsAreExactUnionOfStepStartsOverCalendarDays() {
        var c = catalog(url());
        var both = List.of("SBER", "GAZP");
        // часовые шаги, дни 04-05.03: SBER 07:00, 20:00 (20:49) и 21:00 UTC, GAZP 07:00 совпадает с SBER
        assertThat(c.sessionSteps(SNAP, 60, 3600, both, days("2024-03-04", "2024-03-05")))
                .isEqualTo(3);
        // граница дня по МСК: свеча 00:00 МСК 05.03 не входит в 04.03 и входит в 05.03
        assertThat(c.sessionSteps(SNAP, 60, 3600, both, days("2024-03-04"))).isEqualTo(2);
        assertThat(c.sessionSteps(SNAP, 60, 3600, both, days("2024-03-05"))).isEqualTo(1);
        // шаг 1 день - даты МСК
        assertThat(c.sessionSteps(SNAP, 60, 86400, both, days("2024-03-04", "2024-03-05")))
                .isEqualTo(2);
        // минутные свечи: 07:00 общая у обеих бумаг
        assertThat(c.sessionSteps(SNAP, 60, 60, both, days("2024-03-04"))).isEqualTo(2);
        assertThat(c.sessionSteps(SNAP, 60, 60, List.of("GAZP"), days("2024-03-04")))
                .isEqualTo(1);
        assertThat(c.sessionSteps(SNAP, 60, 60, List.of(), days("2024-03-04"))).isZero();
        assertThat(c.sessionSteps(SNAP, 60, 60, both, List.of())).isZero();
        // кавычка в тикере не ломает запрос
        assertThat(c.sessionSteps(SNAP, 60, 60, List.of("O\'NEIL"), days("2024-03-04")))
                .isZero();
    }

    @Test
    void candlesOutsideCalendarDaysDoNotEnterTheSessionScale() {
        var c = catalog(url());
        // окно 04-08.03 по календарю: 06.03 (торги GAZP в выходной) внутри диапазона времени, но не в шкале
        assertThat(c.sessionSteps(SNAP, 60, 60, List.of("GAZP"), days("2024-03-04", "2024-03-08")))
                .isEqualTo(1);
        assertThat(c.sessionSteps(SNAP, 60, 60, List.of("GAZP"), days("2024-03-04", "2024-03-06", "2024-03-08")))
                .isEqualTo(2);
    }

    @Test
    void maxStepsPerDayIsTheBusiestMoscowDayOverAllPapersInOnePass() {
        var c = catalog(url());
        // дни МСК: 04.03 — SBER 07:00 и 20:49 (23:49 МСК), GAZP 07:00; 05.03 — SBER 00:00 МСК; 06.03 — GAZP; 10.03 —
        // SBER
        var limits = c.maxStepsPerDay(SNAP, 60, List.of(60, 3600, 86400));
        assertThat(limits).containsEntry(60, 2).containsEntry(3600, 2).containsEntry(86400, 1);
        assertThat(c.maxStepsPerDay(SNAP, 300, List.of(300))).containsEntry(300, 0);
        assertThat(c.maxStepsPerDay(SNAP, 60, List.of())).isEmpty();
    }

    @Test
    void clientErrorFromClickHouseIsNotReportedAsUnavailable() {
        var c = new ClickHouseArchiveCatalog(HTTP, url(), "no_such_db", "ttsc", "secret", Duration.ofSeconds(10));
        assertThatThrownBy(() -> c.isReady(SNAP)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unavailableClickHouseIsDependencyUnavailable() {
        var c = catalog("http://127.0.0.1:1");
        assertThatThrownBy(() -> c.isReady(SNAP)).isInstanceOf(DependencyUnavailableException.class);
    }
}
