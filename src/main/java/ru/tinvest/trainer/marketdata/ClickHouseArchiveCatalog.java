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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import ru.tinvest.trainer.api.DependencyUnavailableException;

/**
 * {@link ArchiveCatalog} поверх HTTP-интерфейса ClickHouse (порт 8123). Только чтение; снапшот приходит как UUID
 * из {@code marketdata.snapshot.snapshot_key}, поэтому в SQL попадает только проверенный UUID.
 */
final class ClickHouseArchiveCatalog implements ArchiveCatalog {

    private static final String MSK = "'Europe/Moscow'";

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
    public Map<String, Integer> daysWithCandles(String snapshotKey, int intervalSec, LocalDate from, LocalDate to) {
        Map<String, Integer> map = new HashMap<>();
        for (String[] r : rows("SELECT secid, uniqExact(toDate(ts_open, " + MSK + ")) FROM candles "
                + "WHERE snapshot_id = '" + uuid(snapshotKey) + "' AND interval_sec = " + intervalSec
                + " AND secid != 'IMOEX' AND toDate(ts_open, " + MSK + ") BETWEEN '" + from + "' AND '" + to
                + "' GROUP BY secid")) {
            map.put(r[0], Integer.parseInt(r[1]));
        }
        return map;
    }

    @Override
    public Set<String> withCorporateActions(String snapshotKey, LocalDate from, LocalDate to) {
        Set<String> set = new HashSet<>();
        for (String[] r : rows("SELECT DISTINCT secid FROM corporate_actions WHERE snapshot_id = '" + uuid(snapshotKey)
                + "' AND date BETWEEN '" + from + "' AND '" + to + "'")) {
            set.add(r[0]);
        }
        return set;
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
            if (r.statusCode() != 200) {
                throw new DependencyUnavailableException("ClickHouse ответил HTTP " + r.statusCode(), null);
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
