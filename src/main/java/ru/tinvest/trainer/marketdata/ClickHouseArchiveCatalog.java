package ru.tinvest.trainer.marketdata;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import ru.tinvest.trainer.api.DependencyUnavailableException;

/**
 * {@link ArchiveCatalog} поверх HTTP-интерфейса ClickHouse (порт 8123). Только чтение; снапшот приходит как UUID
 * из {@code marketdata.snapshot.snapshot_key}, поэтому в SQL попадает только проверенный UUID.
 */
final class ClickHouseArchiveCatalog implements ArchiveCatalog {

    private static final String MSK = "'Europe/Moscow'";
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter UTC_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HttpClient http;
    private final String url;
    private final String database;
    private final String user;
    private final String password;
    private final Duration timeout;

    ClickHouseArchiveCatalog(
            HttpClient http, String url, String database, String user, String password, Duration timeout) {
        this.http = http;
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.database = database;
        this.user = user;
        this.password = password;
        this.timeout = timeout;
    }

    @Override
    public boolean isReady(String snapshotKey) {
        return !rows("SELECT 1 FROM snapshots_ready WHERE snapshot_id = '" + uuid(snapshotKey) + "' LIMIT 1")
                .isEmpty();
    }

    @Override
    public List<LocalDate> tradingDays(String snapshotKey) {
        List<LocalDate> days = new ArrayList<>();
        for (String[] r : rows("SELECT date FROM trading_calendar WHERE snapshot_id = '" + uuid(snapshotKey)
                + "' AND is_trading = 1 ORDER BY date")) {
            days.add(LocalDate.parse(r[0]));
        }
        return days;
    }

    @Override
    public Set<Integer> intervals(String snapshotKey) {
        Set<Integer> set = new HashSet<>();
        for (String[] r : rows("SELECT DISTINCT interval_sec FROM candles WHERE snapshot_id = '" + uuid(snapshotKey)
                + "' AND secid != 'IMOEX'")) {
            set.add(Integer.parseInt(r[0]));
        }
        return set;
    }

    @Override
    public List<ArchivePaper> securities(String snapshotKey) {
        List<ArchivePaper> list = new ArrayList<>();
        for (String[] r : rows("SELECT secid, sector, lot_size, price_step FROM securities WHERE snapshot_id = '"
                + uuid(snapshotKey) + "' ORDER BY secid")) {
            list.add(new ArchivePaper(r[0], r[1], Long.parseLong(r[2]), new BigDecimal(r[3])));
        }
        return list;
    }

    @Override
    public Map<String, Set<LocalDate>> candleDays(String snapshotKey, int intervalSec) {
        Map<String, Set<LocalDate>> map = new HashMap<>();
        for (String[] r : rows("SELECT secid, toDate(ts_open, " + MSK + ") AS d FROM candles WHERE snapshot_id = '"
                + uuid(snapshotKey) + "' AND interval_sec = " + intervalSec
                + " AND secid != 'IMOEX' GROUP BY secid, d")) {
            map.computeIfAbsent(r[0], k -> new HashSet<>()).add(LocalDate.parse(r[1]));
        }
        return map;
    }

    @Override
    public Map<String, List<LocalDate>> corporateActions(String snapshotKey) {
        Map<String, List<LocalDate>> map = new HashMap<>();
        for (String[] r : rows("SELECT secid, date FROM corporate_actions WHERE snapshot_id = '" + uuid(snapshotKey)
                + "' ORDER BY secid, date")) {
            map.computeIfAbsent(r[0], k -> new ArrayList<>()).add(LocalDate.parse(r[1]));
        }
        return map;
    }

    @Override
    public int sessionSteps(
            String snapshotKey,
            int sourceIntervalSec,
            int stepSec,
            Collection<String> secids,
            List<LocalDate> windowDays) {
        if (secids.isEmpty() || windowDays.isEmpty()) {
            return 0;
        }
        // Торговый день — дата МСК: границы по ts_open (первичный ключ), все сессии дня внутри.
        String bucket = stepSec >= 86400
                ? "toDate(ts_open, " + MSK + ")"
                : "toStartOfInterval(ts_open, INTERVAL " + stepSec + " SECOND)";
        String list = secids.stream().map(ClickHouseArchiveCatalog::quote).collect(Collectors.joining(", "));
        LocalDate from = windowDays.getFirst();
        LocalDate to = windowDays.getLast();
        String dates = windowDays.stream().map(d -> "'" + d + "'").collect(Collectors.joining(", "));
        List<String[]> r = rows("SELECT uniqExact(" + bucket + ") FROM candles WHERE snapshot_id = '"
                + uuid(snapshotKey) + "' AND secid IN (" + list + ") AND interval_sec = " + sourceIntervalSec
                + " AND ts_open >= toDateTime64('" + utc(from.atStartOfDay(ZONE)) + "', 3, 'UTC')"
                + " AND ts_open < toDateTime64('" + utc(to.plusDays(1).atStartOfDay(ZONE)) + "', 3, 'UTC')"
                + " AND toDate(ts_open, " + MSK + ") IN (" + dates + ")");
        return Integer.parseInt(r.getFirst()[0]);
    }

    private static String utc(ZonedDateTime t) {
        return UTC_FORMAT.format(t.withZoneSameInstant(ZoneOffset.UTC));
    }

    private static String quote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static UUID uuid(String snapshotKey) {
        return UUID.fromString(snapshotKey);
    }

    private List<String[]> rows(String sql) {
        URI uri = URI.create(url + "/?database=" + URLEncoder.encode(database, StandardCharsets.UTF_8));
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(sql + " FORMAT TSV", StandardCharsets.UTF_8));
        if (!user.isEmpty()) {
            b.header("X-ClickHouse-User", user).header("X-ClickHouse-Key", password);
        }
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            // 516 — аутентификация: ошибка настройки, а не временный сбой
            if (r.statusCode() >= 500 && r.statusCode() != 516) {
                throw new DependencyUnavailableException("ClickHouse ответил HTTP " + r.statusCode(), null);
            }
            if (r.statusCode() != 200) {
                // 4xx — неверные учётные данные или запрос: ошибка настройки или кода, не временный сбой
                throw new IllegalStateException("ClickHouse отклонил запрос: HTTP " + r.statusCode());
            }
            List<String[]> rows = new ArrayList<>();
            for (String line : r.body().split("\n")) {
                if (!line.isEmpty()) {
                    rows.add(line.split("\t", -1));
                }
            }
            return rows;
        } catch (IOException e) {
            throw new DependencyUnavailableException("ClickHouse недоступен", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DependencyUnavailableException("Запрос к ClickHouse прерван", e);
        }
    }
}
