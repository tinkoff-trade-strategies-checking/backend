package ru.tinvest.trainer.marketdata.importer;

import static ru.tinvest.trainer.marketdata.importer.ClickHouse.num;
import static ru.tinvest.trainer.marketdata.importer.ClickHouse.str;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Схема и запись архива в ClickHouse. Таблицы и столбцы — контракт, который читает {@code core}
 * ({@code core/AGENTS.md}, «Контракт схемы ClickHouse для импортёра»); {@code corporate_actions} — дополнение
 * импортёра. Каждая вставка — один оператор {@code INSERT}, то есть атомарна (блок меньше
 * {@code max_insert_block_size}): после обрыва в таблице либо весь кусок, либо ничего, поэтому «есть строки» значит
 * «кусок загружен» и докачка идемпотентна.
 */
final class ClickHouseArchive {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    /** Максимум строк в одном INSERT; меньше {@code max_insert_block_size} (1 048 576). */
    private static final int MAX_ROWS = 200_000;

    private final ClickHouse ch;

    ClickHouseArchive(ClickHouse ch) {
        this.ch = ch;
    }

    void createSchema() {
        ch.execute("CREATE DATABASE IF NOT EXISTS " + ch.database());
        ch.execute("CREATE TABLE IF NOT EXISTS snapshots_ready (snapshot_id UUID) ENGINE = MergeTree() "
                + "ORDER BY snapshot_id");
        ch.execute("CREATE TABLE IF NOT EXISTS securities (snapshot_id UUID, secid String, sector String, "
                + "lot_size Int64, price_step Decimal(18, 6)) ENGINE = MergeTree() ORDER BY (snapshot_id, secid)");
        // Кодеки не меняют типы столбцов (контракт core), но сжимают минутные свечи в 2,4 раза (замер 19.11.2026)
        ch.execute("CREATE TABLE IF NOT EXISTS candles (snapshot_id UUID CODEC(ZSTD), secid LowCardinality(String), "
                + "interval_sec UInt32 CODEC(ZSTD), ts_open DateTime64(3, 'UTC') CODEC(DoubleDelta, ZSTD), "
                + "available_at DateTime64(3, 'UTC') CODEC(DoubleDelta, ZSTD), "
                + "open Decimal(18, 6) CODEC(Delta, ZSTD), high Decimal(18, 6) CODEC(Delta, ZSTD), "
                + "low Decimal(18, 6) CODEC(Delta, ZSTD), close Decimal(18, 6) CODEC(Delta, ZSTD), "
                + "volume Int64 CODEC(ZSTD), value Decimal(18, 6) CODEC(ZSTD)) ENGINE = MergeTree() "
                + "ORDER BY (snapshot_id, secid, interval_sec, ts_open)");
        ch.execute("CREATE TABLE IF NOT EXISTS trading_calendar (snapshot_id UUID, date Date, is_trading UInt8) "
                + "ENGINE = MergeTree() ORDER BY (snapshot_id, date)");
        ch.execute("CREATE TABLE IF NOT EXISTS ruonia (snapshot_id UUID, date Date, rate Decimal(18, 6)) "
                + "ENGINE = MergeTree() ORDER BY (snapshot_id, date)");
        ch.execute("CREATE TABLE IF NOT EXISTS corporate_actions (snapshot_id UUID, secid String, date Date, "
                + "kind String, before Int64, after Int64) ENGINE = MergeTree() ORDER BY (snapshot_id, secid, date)");
    }

    boolean isReady(UUID snapshot) {
        return ch.count("SELECT count() FROM snapshots_ready WHERE snapshot_id = '" + snapshot + "'") > 0;
    }

    void markReady(UUID snapshot) {
        ch.execute("INSERT INTO snapshots_ready (snapshot_id) VALUES ('" + snapshot + "')");
    }

    long countRows(String table, UUID snapshot) {
        return ch.count("SELECT count() FROM " + table + " WHERE snapshot_id = '" + snapshot + "'");
    }

    /** Строк свечей бумаги с {@code ts_open} в полуинтервале [{@code from}, {@code to}). */
    long countCandles(UUID snapshot, String secid, int intervalSec, Instant from, Instant to) {
        return ch.count("SELECT count() FROM candles WHERE snapshot_id = '" + snapshot + "' AND secid = " + str(secid)
                + " AND interval_sec = " + intervalSec + " AND ts_open >= " + str(TS.format(from))
                + " AND ts_open < " + str(TS.format(to)));
    }

    /** {@code available_at = ts_open + interval} (INV-1). */
    void insertCandles(UUID snapshot, String secid, int intervalSec, List<Candle> candles) {
        for (int i = 0; i < candles.size(); i += MAX_ROWS) {
            List<Candle> part = candles.subList(i, Math.min(candles.size(), i + MAX_ROWS));
            StringBuilder sb = new StringBuilder(
                    "INSERT INTO candles (snapshot_id, secid, interval_sec, ts_open, available_at, open, high, low, "
                            + "close, volume, value) VALUES ");
            for (Candle c : part) {
                sb.append('(')
                        .append(str(snapshot.toString()))
                        .append(',')
                        .append(str(secid))
                        .append(',')
                        .append(intervalSec)
                        .append(',')
                        .append(str(TS.format(c.tsOpen())))
                        .append(',')
                        .append(str(TS.format(c.tsOpen().plus(Duration.ofSeconds(intervalSec)))))
                        .append(',')
                        .append(num(c.open()))
                        .append(',')
                        .append(num(c.high()))
                        .append(',')
                        .append(num(c.low()))
                        .append(',')
                        .append(num(c.close()))
                        .append(',')
                        .append(c.volume())
                        .append(',')
                        .append(num(c.value()))
                        .append("),");
            }
            sb.setLength(sb.length() - 1);
            ch.execute(sb.toString());
        }
    }

    void insertSecurities(UUID snapshot, List<SecurityRow> rows) {
        String values = rows.stream()
                .map(r -> "(" + str(snapshot.toString()) + "," + str(r.secid()) + "," + str(r.sector()) + ","
                        + r.lotSize() + "," + num(r.priceStep()) + ")")
                .collect(Collectors.joining(","));
        ch.execute("INSERT INTO securities (snapshot_id, secid, sector, lot_size, price_step) VALUES " + values);
    }

    List<String> secids(UUID snapshot) {
        String out = ch.execute(
                "SELECT secid FROM securities WHERE snapshot_id = '" + snapshot + "' ORDER BY secid FORMAT TSV");
        return out.isBlank() ? List.of() : out.trim().lines().toList();
    }

    /** Даты МСК, на которые есть свеча {@code secid} с интервалом {@code intervalSec}. */
    List<LocalDate> candleDates(UUID snapshot, String secid, int intervalSec) {
        String out = ch.execute("SELECT DISTINCT toDate(ts_open + INTERVAL 3 HOUR) AS d FROM candles "
                + "WHERE snapshot_id = '" + snapshot + "' AND secid = " + str(secid) + " AND interval_sec = "
                + intervalSec + " ORDER BY d FORMAT TSV");
        List<LocalDate> dates = new ArrayList<>();
        out.lines().filter(l -> !l.isBlank()).forEach(l -> dates.add(LocalDate.parse(l.trim())));
        return dates;
    }

    void insertCalendar(UUID snapshot, Map<LocalDate, Boolean> days) {
        for (List<Map.Entry<LocalDate, Boolean>> part : partition(List.copyOf(days.entrySet()))) {
            String values = part.stream()
                    .map(e -> "(" + str(snapshot.toString()) + ","
                            + str(e.getKey().toString()) + "," + (e.getValue() ? 1 : 0) + ")")
                    .collect(Collectors.joining(","));
            ch.execute("INSERT INTO trading_calendar (snapshot_id, date, is_trading) VALUES " + values);
        }
    }

    void insertRuonia(UUID snapshot, Map<LocalDate, BigDecimal> rates) {
        for (List<Map.Entry<LocalDate, BigDecimal>> part : partition(List.copyOf(rates.entrySet()))) {
            String values = part.stream()
                    .map(e -> "(" + str(snapshot.toString()) + ","
                            + str(e.getKey().toString()) + "," + num(e.getValue()) + ")")
                    .collect(Collectors.joining(","));
            ch.execute("INSERT INTO ruonia (snapshot_id, date, rate) VALUES " + values);
        }
    }

    void insertActions(UUID snapshot, List<ActionRow> rows) {
        String values = rows.stream()
                .map(r -> "(" + str(snapshot.toString()) + "," + str(r.secid()) + ","
                        + str(r.date().toString()) + "," + str(r.kind()) + "," + r.before() + "," + r.after() + ")")
                .collect(Collectors.joining(","));
        ch.execute("INSERT INTO corporate_actions (snapshot_id, secid, date, kind, before, after) VALUES " + values);
    }

    /** Объём таблиц на диске: имя → {строк, байт сжато, байт без сжатия}. */
    Map<String, long[]> diskUsage() {
        String out = ch.execute("SELECT table, sum(rows), sum(data_compressed_bytes), sum(data_uncompressed_bytes) "
                + "FROM system.parts WHERE active AND database = " + str(ch.database())
                + " GROUP BY table ORDER BY table FORMAT TSV");
        Map<String, long[]> usage = new TreeMap<>();
        out.lines().filter(l -> !l.isBlank()).forEach(l -> {
            String[] p = l.split("\t", -1);
            usage.put(p[0], new long[] {Long.parseLong(p[1]), Long.parseLong(p[2]), Long.parseLong(p[3])});
        });
        return usage;
    }

    private static <T> List<List<T>> partition(List<T> list) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += MAX_ROWS) {
            parts.add(list.subList(i, Math.min(list.size(), i + MAX_ROWS)));
        }
        return parts;
    }

    record SecurityRow(String secid, String sector, long lotSize, BigDecimal priceStep) {}

    record ActionRow(String secid, LocalDate date, String kind, long before, long after) {}
}
