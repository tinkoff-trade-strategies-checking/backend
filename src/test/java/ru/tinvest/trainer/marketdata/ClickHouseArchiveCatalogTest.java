package ru.tinvest.trainer.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
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
        exec("CREATE TABLE securities (snapshot_id UUID, secid String, sector String, lot_size Int64, "
                + "price_step Decimal(18, 6)) ENGINE = MergeTree() ORDER BY (snapshot_id, secid)");
        exec("CREATE TABLE candles (snapshot_id UUID, secid LowCardinality(String), interval_sec UInt32, "
                + "ts_open DateTime64(3, 'UTC'), available_at DateTime64(3, 'UTC'), open Decimal(18, 6), "
                + "high Decimal(18, 6), low Decimal(18, 6), close Decimal(18, 6), volume Int64, "
                + "value Decimal(18, 6)) ENGINE = MergeTree() ORDER BY (snapshot_id, secid, interval_sec, ts_open)");
        exec("CREATE TABLE trading_calendar (snapshot_id UUID, date Date, is_trading UInt8) "
                + "ENGINE = MergeTree() ORDER BY (snapshot_id, date)");
        exec("CREATE TABLE corporate_actions (snapshot_id UUID, secid String, date Date, kind String, "
                + "before Int64, after Int64) ENGINE = MergeTree() ORDER BY (snapshot_id, secid, date)");

        exec("INSERT INTO snapshots_ready VALUES ('" + SNAP + "')");
        exec("INSERT INTO securities VALUES ('" + SNAP + "', 'SBER', 'Финансы', 10, 0.01), ('" + SNAP
                + "', 'GAZP', '', 10, 0.02), ('" + OTHER + "', 'LKOH', 'Нефть и газ', 1, 0.5)");
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
        assertThat(list.get(1).lotSize()).isEqualTo(10);
        assertThat(list.get(1).priceStep()).isEqualByComparingTo("0.01");
    }

    @Test
    void daysWithCandlesCountsMoscowDates() {
        var c = catalog(url());
        // SBER: 04.03 (две свечи, одна дата) и 05.03 (00:00 МСК); свеча 10.03 вне периода
        assertThat(c.daysWithCandles(SNAP, 60, LocalDate.of(2024, 3, 4), LocalDate.of(2024, 3, 5)))
                .containsEntry("SBER", 2)
                .containsEntry("GAZP", 1)
                .doesNotContainKey("IMOEX");
        assertThat(c.daysWithCandles(SNAP, 60, LocalDate.of(2024, 3, 5), LocalDate.of(2024, 3, 5)))
                .containsOnlyKeys("SBER");
        assertThat(c.daysWithCandles(SNAP, 300, LocalDate.of(2024, 3, 4), LocalDate.of(2024, 3, 5)))
                .isEmpty();
    }

    @Test
    void corporateActionsInPeriod() {
        var c = catalog(url());
        assertThat(c.withCorporateActions(SNAP, LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 31)))
                .containsExactly("SBER");
        assertThat(c.withCorporateActions(SNAP, LocalDate.of(2024, 3, 5), LocalDate.of(2024, 3, 31)))
                .isEmpty();
    }

    @Test
    void unavailableClickHouseIsDependencyUnavailable() {
        var c = catalog("http://127.0.0.1:1");
        assertThatThrownBy(() -> c.isReady(SNAP)).isInstanceOf(DependencyUnavailableException.class);
    }
}
