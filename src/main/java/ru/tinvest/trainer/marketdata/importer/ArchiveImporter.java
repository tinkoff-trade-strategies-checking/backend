package ru.tinvest.trainer.marketdata.importer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tinvest.trainer.marketdata.Snapshot;
import ru.tinvest.trainer.marketdata.Snapshots;

/**
 * Импорт архива MOEX ISS в ClickHouse как неизменяемый снапшот (UC-26).
 *
 * <p>Идентификатор снапшота детерминирован параметрами ({@link Params#snapshotId()}), поэтому после обрыва тот же
 * запуск докачивает недостающее: каждая таблица и каждый кусок (бумага × месяц) вставляются одним атомарным INSERT, и
 * загруженное не грузится заново. Снапшот становится видимым для {@code core} записью в {@code snapshots_ready} и
 * только затем регистрируется в PostgreSQL ({@link Snapshots#register}). Уже зарегистрированный снапшот не
 * меняется (INV-5): повторный запуск с теми же параметрами ничего не делает, новый снапшот — другие параметры или
 * {@code label}.
 */
public final class ArchiveImporter {

    private static final Logger log = LoggerFactory.getLogger(ArchiveImporter.class);

    static final String SCHEMA_VERSION = "1";
    static final int MINUTE = 60;
    static final int DAY = 86400;
    static final String BENCHMARK = "IMOEX";
    static final String POOL_INDEX = "IMOEX";

    /** Отраслевые индексы MOEX → сектор для слепого режима ({@code decisions.md}, «Слепой режим»). */
    static final Map<String, String> SECTOR_INDEXES = Map.ofEntries(
            Map.entry("MOEXOG", "Нефть и газ"),
            Map.entry("MOEXEU", "Электроэнергетика"),
            Map.entry("MOEXTL", "Телекоммуникации"),
            Map.entry("MOEXMM", "Металлы и добыча"),
            Map.entry("MOEXFN", "Финансы"),
            Map.entry("MOEXCN", "Потребительский сектор"),
            Map.entry("MOEXCH", "Химия и нефтехимия"),
            Map.entry("MOEXTN", "Транспорт"),
            Map.entry("MOEXIT", "Информационные технологии"),
            Map.entry("MOEXRE", "Строительство и недвижимость"));

    /** Шаг цены не меньше 1e-6 ₽ ({@code decisions.md}, «Деньги и округление», п. 12). */
    private static final BigDecimal MIN_PRICE_STEP = new BigDecimal("0.000001");

    /** Запас календаря и ставок до {@code from}: ядру нужен день перед первым днём шкалы. */
    private static final int LOOKBACK_DAYS = 14;

    /**
     * {@code allowGaps} не входит в идентификатор снапшота: разрешает пометить снапшот готовым, если у бумаги нет свечей
     * в какие-то торговые дни (например, торги бумагой приостанавливали); пробелы всё равно попадут в лог.
     */
    public record Params(LocalDate from, LocalDate to, List<String> tickers, String label, boolean allowGaps) {

        public Params(LocalDate from, LocalDate to, List<String> tickers, String label) {
            this(from, to, tickers, label, false);
        }

        public Params {
            if (from == null || to == null || to.isBefore(from)) {
                throw new IllegalArgumentException("Период импорта задан неверно: " + from + "…" + to);
            }
            tickers = tickers == null
                    ? List.of()
                    : tickers.stream().map(String::trim).sorted().distinct().toList();
            label = label == null ? "" : label;
        }

        /** UUID по имени: те же параметры — тот же снапшот. */
        public UUID snapshotId() {
            String name =
                    "iss-v" + SCHEMA_VERSION + "|" + from + "|" + to + "|" + String.join(",", tickers) + "|" + label;
            return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        }
    }

    public record Result(UUID snapshotId, boolean alreadyLoaded, Snapshot snapshot) {}

    private final IssClient iss;
    private final CbrRuonia cbr;
    private final ClickHouseArchive archive;
    private final Snapshots snapshots;
    private final Clock clock;
    private final SnapshotLocks locks;

    ArchiveImporter(
            IssClient iss,
            CbrRuonia cbr,
            ClickHouseArchive archive,
            Snapshots snapshots,
            Clock clock,
            SnapshotLocks locks) {
        this.locks = locks;
        this.iss = iss;
        this.cbr = cbr;
        this.archive = archive;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    public Result run(Params p) {
        UUID id = p.snapshotId();
        try (SnapshotLocks.Held lock = locks.acquire(id.toString())) {
            return runLocked(p, id);
        }
    }

    private Result runLocked(Params p, UUID id) {
        var existing = snapshots.findByKey(id.toString());
        if (existing.isPresent()) {
            log.info(
                    "Снапшот {} уже загружен и неизменяем, импорт пропущен (для нового — другие параметры или label)",
                    id);
            return new Result(id, true, existing.get());
        }
        archive.createSchema();
        if (!archive.isReady(id)) {
            load(p, id);
            archive.markReady(id);
        }
        List<String> loaded = archive.secids(id);
        Snapshot s = snapshots.register(id.toString(), description(p, loaded));
        log.info("Снапшот {} зарегистрирован, бумаг: {}", id, loaded.size());
        return new Result(id, false, s);
    }

    private void load(Params p, UUID id) {
        loadBenchmark(p, id);
        loadSecurities(p, id);
        List<String> secids = archive.secids(id);
        for (String secid : secids) {
            loadCandles(p, id, secid);
        }
        loadCalendar(p, id);
        loadActions(id, secids);
        loadRuonia(p, id);
        verifyCoverage(p, id, secids);
    }

    /**
     * Перед {@code snapshots_ready}: у каждой бумаги должны быть минутные свечи в каждый торговый день календаря между
     * её первой и последней свечой (кусок загружается целиком или не загружается, но пустая страница ISS не должна
     * превращаться в дыру). Пробелы — отчёт в лог и {@link ImportException}, если не {@code allowGaps}.
     */
    private void verifyCoverage(Params p, UUID id, List<String> secids) {
        List<LocalDate> trading = archive.tradingDays(id).stream()
                .filter(d -> !d.isBefore(p.from()) && !d.isAfter(p.to()))
                .toList();
        Map<String, List<LocalDate>> gaps = new TreeMap<>();
        for (String secid : secids) {
            Set<LocalDate> have = new HashSet<>(archive.candleDates(id, secid, MINUTE));
            if (have.isEmpty()) {
                gaps.put(secid, trading);
                continue;
            }
            LocalDate first = Collections.min(have);
            LocalDate last = Collections.max(have);
            List<LocalDate> missing = trading.stream()
                    .filter(d -> !d.isBefore(first) && !d.isAfter(last) && !have.contains(d))
                    .toList();
            if (!missing.isEmpty()) {
                gaps.put(secid, missing);
            }
        }
        if (gaps.isEmpty()) {
            return;
        }
        StringBuilder report = new StringBuilder("Пробелы в минутных свечах (торговые дни без свечей):");
        gaps.forEach((secid, days) -> report.append("\n  ")
                .append(secid)
                .append(": ")
                .append(days.size())
                .append(" дн., первые ")
                .append(days.stream().limit(20).toList()));
        if (!p.allowGaps()) {
            log.error("{}", report);
            throw new ImportException(
                    report + "\nСнапшот не помечен готовым; --import.allow-gaps=true принимает пробелы");
        }
        log.warn("{}\nПробелы приняты (allow-gaps)", report);
    }

    private void loadBenchmark(Params p, UUID id) {
        LocalDate start = p.from().minusDays(LOOKBACK_DAYS);
        for (YearMonth ym = YearMonth.from(start); !ym.isAfter(YearMonth.from(p.to())); ym = ym.plusYears(1)) {
            LocalDate from = max(start, ym.atDay(1));
            LocalDate till = min(p.to(), ym.plusMonths(11).atEndOfMonth());
            if (archive.countCandles(id, BENCHMARK, DAY, startOf(from), startOf(till.plusDays(1))) > 0) {
                continue;
            }
            List<Candle> candles = inRange(iss.indexDailyCandles(BENCHMARK, from, till), from, till);
            archive.insertCandles(id, BENCHMARK, DAY, candles);
            log.info("{} дневные {}…{}: {} свечей", BENCHMARK, from, till, candles.size());
        }
    }

    private void loadSecurities(Params p, UUID id) {
        if (archive.countRows("securities", id) > 0) {
            return;
        }
        List<String> tickers = p.tickers();
        IssClient.IndexComposition imoex = null;
        if (tickers.isEmpty()) {
            imoex = iss.composition(POOL_INDEX, p.to());
            tickers = imoex.secids().stream().sorted().distinct().toList();
            log.info("Пул {} на {}: {} бумаг", POOL_INDEX, imoex.date(), tickers.size());
        }
        LocalDate asOf = imoex != null ? imoex.date() : p.to();
        Map<String, List<String>> sectors = new TreeMap<>();
        for (Map.Entry<String, String> e : new TreeMap<>(SECTOR_INDEXES).entrySet()) {
            for (String secid : iss.composition(e.getKey(), asOf).secids()) {
                sectors.computeIfAbsent(secid, k -> new ArrayList<>()).add(e.getValue());
            }
        }
        List<ClickHouseArchive.SecurityRow> rows = new ArrayList<>();
        for (String secid : tickers) {
            IssTable info = iss.securityInfo(secid);
            if (info.isEmpty()) {
                log.warn("{}: нет на доске TQBR, пропущена", secid);
                continue;
            }
            List<tools.jackson.databind.JsonNode> r = info.rows().get(0);
            long lot = info.get(r, "LOTSIZE").asLong();
            BigDecimal step;
            try {
                step = IssParsers.price(info.get(r, "MINSTEP"));
            } catch (ImportException e) {
                // шаг с больше чем шестью знаками меньше 1e-6 ₽ или не представим в Decimal(18, 6)
                log.warn("{}: шаг цены не проходит условие импорта ({}), пропущена", secid, e.getMessage());
                continue;
            }
            if (step.compareTo(MIN_PRICE_STEP) < 0 || lot < 1) {
                log.warn("{}: шаг цены {} или лот {} не проходят условие импорта, пропущена", secid, step, lot);
                continue;
            }
            List<String> own = sectors.getOrDefault(secid, List.of());
            if (own.size() != 1) {
                log.warn(
                        "{}: секторов по отраслевым индексам {} ({}), сектор — {}",
                        secid,
                        own.size(),
                        own,
                        own.isEmpty() ? "пустая строка" : own.get(0));
            }
            rows.add(new ClickHouseArchive.SecurityRow(secid, own.isEmpty() ? "" : own.get(0), lot, step));
        }
        if (rows.isEmpty()) {
            throw new ImportException("Ни одна бумага не прошла проверку, снапшот пуст");
        }
        archive.insertSecurities(id, rows);
    }

    private void loadCandles(Params p, UUID id, String secid) {
        int total = 0;
        for (YearMonth ym = YearMonth.from(p.from()); !ym.isAfter(YearMonth.from(p.to())); ym = ym.plusMonths(1)) {
            LocalDate from = max(p.from(), ym.atDay(1));
            LocalDate till = min(p.to(), ym.atEndOfMonth());
            if (archive.countCandles(id, secid, MINUTE, startOf(from), startOf(till.plusDays(1))) > 0) {
                continue;
            }
            List<Candle> candles = inRange(iss.shareCandles(secid, 1, from, till), from, till);
            archive.insertCandles(id, secid, MINUTE, candles);
            total += candles.size();
            log.info("{} {}: {} свечей", secid, ym, candles.size());
        }
        log.info("{}: загружено {} минутных свечей", secid, total);
    }

    /** Торговые дни — дни, на которые есть дневная свеча IMOEX; остальные даты периода — неторговые. */
    private void loadCalendar(Params p, UUID id) {
        if (archive.countRows("trading_calendar", id) > 0) {
            return;
        }
        Set<LocalDate> trading = new HashSet<>(archive.candleDates(id, BENCHMARK, DAY));
        Map<LocalDate, Boolean> days = new TreeMap<>();
        for (LocalDate d = p.from().minusDays(LOOKBACK_DAYS); !d.isAfter(p.to()); d = d.plusDays(1)) {
            days.put(d, trading.contains(d));
        }
        archive.insertCalendar(id, days);
    }

    /** Сплиты и консолидации бумаг снапшота (по всей истории ISS: нужны и для окна прогрева до {@code from}). */
    private void loadActions(UUID id, List<String> secids) {
        if (archive.countRows("corporate_actions", id) > 0) {
            return;
        }
        IssTable t = iss.splits();
        List<ClickHouseArchive.ActionRow> rows = new ArrayList<>();
        for (List<tools.jackson.databind.JsonNode> r : t.rows()) {
            String secid = t.get(r, "secid").asString();
            if (secids.contains(secid)) {
                long before = t.get(r, "before").asLong();
                long after = t.get(r, "after").asLong();
                rows.add(new ClickHouseArchive.ActionRow(
                        secid,
                        LocalDate.parse(t.get(r, "tradedate").asString()),
                        after > before ? "split" : "consolidation",
                        before,
                        after));
            }
        }
        if (!rows.isEmpty()) {
            archive.insertActions(id, rows);
        }
        log.info("Корпоративные действия: {}", rows.size());
    }

    private void loadRuonia(Params p, UUID id) {
        if (archive.countRows("ruonia", id) > 0) {
            return;
        }
        Map<LocalDate, BigDecimal> rates = cbr.rates(p.from().minusDays(LOOKBACK_DAYS), p.to());
        if (rates.isEmpty()) {
            throw new ImportException("ЦБ РФ не вернул ставок RUONIA за период " + p.from() + "…" + p.to());
        }
        archive.insertRuonia(id, rates);
        log.info("RUONIA: {} ставок", rates.size());
    }

    private String description(Params p, List<String> secids) {
        return "источник: MOEX ISS (свечи interval=1, IMOEX interval=24), ЦБ РФ (RUONIA); тикеры: "
                + String.join(",", secids) + "; интервал: 1 мин; период: " + p.from() + "…" + p.to()
                + "; получен: " + Instant.now(clock) + "; версия схемы: " + SCHEMA_VERSION
                + (p.label().isEmpty() ? "" : "; метка: " + p.label());
    }

    /** Свечи с датой МСК внутри периода (ISS отдаёт даты включительно, но проверяем сами). */
    private static List<Candle> inRange(List<Candle> candles, LocalDate from, LocalDate till) {
        Map<Instant, Candle> unique = new TreeMap<>();
        for (Candle c : candles) {
            LocalDate d = IssParsers.mskDate(c);
            if (!d.isBefore(from) && !d.isAfter(till)) {
                unique.put(c.tsOpen(), c);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static Instant startOf(LocalDate d) {
        return d.atStartOfDay().toInstant(IssParsers.MSK);
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
