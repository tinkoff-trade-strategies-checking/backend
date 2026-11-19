package ru.tinvest.trainer.e2e;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Маленький снапшот рынка для сквозных тестов: три бумаги и индекс IMOEX, дневные свечи за {@link #DAYS} торговых
 * дней, календарь и RUONIA. Данные детерминированы (фиксированное зерно) и лежат в памяти, чтобы тест пересчитывал
 * деньги независимо от воркера. Без сети: ISS не нужен.
 */
final class MarketFixture {

    /** Идентификатор снапшота в ClickHouse ({@code snapshot_id}) и ключ в PostgreSQL ({@code snapshot_key}). */
    static final String SNAPSHOT = "5a1c0de0-0000-4000-8000-0000000000e2";

    static final String INDEX = "IMOEX";

    /** Торговых дней в снапшоте: 100 на прогрев плюс запас на окна. */
    static final int DAYS = 160;

    private static final LocalDate FIRST_DAY = LocalDate.of(2025, 3, 3);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** Бумага: {@code lotSize} акций в лоте, шаг цены {@code priceStep}. */
    record Paper(String secid, String sector, String name, long lotSize, BigDecimal priceStep, long startPrice) {}

    /** Дневная свеча; {@code volume} — в штуках (как в ISS), объём в лотах — {@code volume / lotSize}. */
    record Bar(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {}

    static final List<Paper> PAPERS = List.of(
            new Paper("SBER", "Финансы", "Сбербанк", 10, new BigDecimal("0.01"), 2800),
            new Paper("GAZP", "Энергетика", "Газпром", 10, new BigDecimal("0.01"), 1600),
            new Paper("LKOH", "Нефть и газ", "Лукойл", 1, new BigDecimal("0.5"), 7000));

    private final List<LocalDate> days = new ArrayList<>();
    private final Map<String, Map<LocalDate, Bar>> bars = new LinkedHashMap<>();

    MarketFixture() {
        LocalDate day = FIRST_DAY;
        while (days.size() < DAYS) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(day);
            }
            day = day.plusDays(1);
        }
        Random random = new Random(20261120L);
        for (Paper paper : PAPERS) {
            bars.put(paper.secid(), walk(random, paper.startPrice(), paper.priceStep(), paper.lotSize(), 0.012));
        }
        bars.put(INDEX, index(random));
    }

    List<LocalDate> days() {
        return days;
    }

    Bar bar(String secid, LocalDate day) {
        return bars.get(secid).get(day);
    }

    static Paper paper(String secid) {
        return PAPERS.stream().filter(p -> p.secid().equals(secid)).findFirst().orElseThrow();
    }

    /** Все цены свечей бумаг (для проверки, что слепые ответы их не содержат). */
    List<BigDecimal> allPrices() {
        List<BigDecimal> prices = new ArrayList<>();
        for (Paper paper : PAPERS) {
            for (Bar bar : bars.get(paper.secid()).values()) {
                prices.add(bar.open());
                prices.add(bar.high());
                prices.add(bar.low());
                prices.add(bar.close());
            }
        }
        return prices;
    }

    /** Случайное блуждание цены: цена кратна шагу, {@code low <= min(open, close)}, {@code high >= max(open, close)}. */
    private Map<LocalDate, Bar> walk(Random random, long start, BigDecimal step, long lotSize, double volatility) {
        Map<LocalDate, Bar> result = new LinkedHashMap<>();
        BigDecimal previous = BigDecimal.valueOf(start);
        for (LocalDate day : days) {
            BigDecimal open = previous;
            BigDecimal close = toStep(open.doubleValue() * (1 + random.nextGaussian() * volatility), step);
            BigDecimal high = toStep(
                            Math.max(open.doubleValue(), close.doubleValue()) * (1 + random.nextDouble() * 0.006), step)
                    .max(open.max(close));
            BigDecimal low = toStep(
                            Math.min(open.doubleValue(), close.doubleValue()) * (1 - random.nextDouble() * 0.006), step)
                    .min(open.min(close));
            // объём в штуках: сотни тысяч лотов, чтобы лимит ликвидности и проскальзывание не мешали, но были
            // ненулевыми
            long volume = (400_000 + random.nextInt(600_000)) * lotSize;
            result.put(day, new Bar(open, high, low, close, volume));
            previous = close;
        }
        return result;
    }

    /** Индекс: спокойный участок, потом волатильный и обвал, чтобы нашлись окна всех трёх категорий. */
    private Map<LocalDate, Bar> index(Random random) {
        Map<LocalDate, Bar> result = new LinkedHashMap<>();
        BigDecimal step = new BigDecimal("0.01");
        BigDecimal previous = new BigDecimal("3000");
        for (int i = 0; i < days.size(); i++) {
            double volatility = i < 70 ? 0.004 : i < 120 ? 0.009 : 0.02;
            double drift = i >= 120 && i < 135 ? -0.012 : 0.0005;
            BigDecimal close = toStep(previous.doubleValue() * (1 + drift + random.nextGaussian() * volatility), step);
            BigDecimal high =
                    close.max(previous).multiply(new BigDecimal("1.002")).setScale(2, RoundingMode.HALF_UP);
            BigDecimal low =
                    close.min(previous).multiply(new BigDecimal("0.998")).setScale(2, RoundingMode.HALF_UP);
            result.put(days.get(i), new Bar(previous, high, low, close, 1_000_000));
            previous = close;
        }
        return result;
    }

    private static BigDecimal toStep(double value, BigDecimal step) {
        return BigDecimal.valueOf(value).divide(step, 0, RoundingMode.HALF_UP).multiply(step);
    }

    /** SQL для ClickHouse в порядке выполнения (таблицы, данные, готовность снапшота). */
    void statements(Consumer<String> sink) {
        String s = "'" + SNAPSHOT + "'";
        sink.accept("CREATE TABLE snapshots_ready (snapshot_id UUID) ENGINE = MergeTree() ORDER BY snapshot_id");
        sink.accept(
                "CREATE TABLE securities (snapshot_id UUID, secid String, sector String, lot_size Int64, "
                        + "price_step Decimal(18, 6), name String DEFAULT '') ENGINE = MergeTree() ORDER BY (snapshot_id, secid)");
        sink.accept("CREATE TABLE candles (snapshot_id UUID, secid LowCardinality(String), interval_sec UInt32, "
                + "ts_open DateTime64(3, 'UTC'), available_at DateTime64(3, 'UTC'), open Decimal(18, 6), "
                + "high Decimal(18, 6), low Decimal(18, 6), close Decimal(18, 6), volume Int64, "
                + "value Decimal(18, 6)) ENGINE = MergeTree() ORDER BY (snapshot_id, secid, interval_sec, ts_open)");
        sink.accept("CREATE TABLE trading_calendar (snapshot_id UUID, date Date, is_trading UInt8) "
                + "ENGINE = MergeTree() ORDER BY (snapshot_id, date)");
        sink.accept("CREATE TABLE ruonia (snapshot_id UUID, date Date, rate Decimal(18, 6)) "
                + "ENGINE = MergeTree() ORDER BY (snapshot_id, date)");
        sink.accept("CREATE TABLE corporate_actions (snapshot_id UUID, secid String, date Date, kind String, "
                + "before Int64, after Int64) ENGINE = MergeTree() ORDER BY (snapshot_id, secid, date)");

        StringBuilder securities = new StringBuilder();
        for (Paper p : PAPERS) {
            if (!securities.isEmpty()) {
                securities.append(", ");
            }
            securities.append(String.format(
                    "(%s, '%s', '%s', %d, %s, '%s')",
                    s, p.secid(), p.sector(), p.lotSize(), p.priceStep().toPlainString(), p.name()));
        }
        // индекс — бенчмарк и основа классификации периодов; в каталог бумаг он не попадает (как у импортёра)
        securities.append(String.format(", (%s, '%s', '', 1, 0.01, 'Индекс МосБиржи')", s, INDEX));
        sink.accept(
                "INSERT INTO securities (snapshot_id, secid, sector, lot_size, price_step, name) VALUES " + securities);

        StringBuilder candles = new StringBuilder();
        for (Map.Entry<String, Map<LocalDate, Bar>> paper : bars.entrySet()) {
            for (Map.Entry<LocalDate, Bar> e : paper.getValue().entrySet()) {
                // дневная свеча: 00:00 МСК = 21:00 UTC предыдущих суток, доступна через сутки
                LocalDateTime open = e.getKey().minusDays(1).atTime(21, 0);
                Bar b = e.getValue();
                BigDecimal value =
                        b.close().multiply(BigDecimal.valueOf(b.volume())).setScale(2, RoundingMode.HALF_UP);
                if (!candles.isEmpty()) {
                    candles.append(", ");
                }
                candles.append(String.format(
                        "(%s, '%s', 86400, '%s', '%s', %s, %s, %s, %s, %d, %s)",
                        s,
                        paper.getKey(),
                        TS.format(open),
                        TS.format(open.plusDays(1)),
                        b.open().toPlainString(),
                        b.high().toPlainString(),
                        b.low().toPlainString(),
                        b.close().toPlainString(),
                        b.volume(),
                        value.toPlainString()));
            }
        }
        sink.accept("INSERT INTO candles (snapshot_id, secid, interval_sec, ts_open, available_at, open, high, low, "
                + "close, volume, value) VALUES " + candles);

        StringBuilder calendar = new StringBuilder();
        StringBuilder ruonia = new StringBuilder();
        for (LocalDate d = days.getFirst().minusDays(10);
                !d.isAfter(days.getLast().plusDays(5));
                d = d.plusDays(1)) {
            if (!ruonia.isEmpty()) {
                ruonia.append(", ");
            }
            ruonia.append(String.format("(%s, '%s', 15.000000)", s, d));
        }
        for (LocalDate d : days) {
            if (!calendar.isEmpty()) {
                calendar.append(", ");
            }
            calendar.append(String.format("(%s, '%s', 1)", s, d));
        }
        sink.accept("INSERT INTO trading_calendar (snapshot_id, date, is_trading) VALUES " + calendar);
        sink.accept("INSERT INTO ruonia (snapshot_id, date, rate) VALUES " + ruonia);
        sink.accept("INSERT INTO snapshots_ready (snapshot_id) VALUES (" + s + ")");
    }
}
