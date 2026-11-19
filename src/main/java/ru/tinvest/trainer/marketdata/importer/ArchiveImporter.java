package ru.tinvest.trainer.marketdata.importer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
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

    /** 2: сектор по наибольшему весу, столбец {@code securities.name}, хэш снапшота (backend#21). */
    static final String SCHEMA_VERSION = "2";
    /** Версия схемы до backend#21: загрузки с ней докачивать нельзя ({@link #rejectLegacyLoad}). */
    static final String LEGACY_SCHEMA_VERSION = "1";

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

    private static final int MIN_MONTH_SHARE_PERCENT = 50;
    private static final int EDGE_DAYS = 10;
    /** Сколько дат пробелов одной бумаги показывать в {@code description}: при массовых пробелах — начало и конец. */
    static final int DESCRIPTION_DATES = 5;

    /**
     * {@code allowGaps} не входит в идентификатор снапшота: разрешает пометить снапшот готовым, если у бумаги нет свечей
     * в какие-то торговые дни (например, торги бумагой приостанавливали) или календарь IMOEX подозрительно редок
     * (остановка торгов на бирже); и то и другое попадёт в лог и {@code description}.
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
            return snapshotId(SCHEMA_VERSION);
        }

        UUID snapshotId(String schemaVersion) {
            String name =
                    "iss-v" + schemaVersion + "|" + from + "|" + to + "|" + String.join(",", tickers) + "|" + label;
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
    private final Duration endOfDayTolerance;
    /** Принятые при {@code allow-gaps} проблемы этого запуска: бумага → дата → описание. */
    private final Map<String, Map<LocalDate, String>> acceptedProblems = new TreeMap<>();

    private final Map<String, Optional<IssClient.Listing>> listings = new HashMap<>();
    private final Map<String, Map<LocalDate, LocalTime>> sessionEnds = new HashMap<>();

    ArchiveImporter(
            IssClient iss,
            CbrRuonia cbr,
            ClickHouseArchive archive,
            Snapshots snapshots,
            Clock clock,
            SnapshotLocks locks,
            Duration endOfDayTolerance) {
        this.locks = locks;
        this.endOfDayTolerance = endOfDayTolerance;
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
        acceptedProblems.clear();
        listings.clear();
        sessionEnds.clear();
        var existing = snapshots.findByKey(id.toString());
        if (existing.isPresent()) {
            log.info(
                    "Снапшот {} уже загружен и неизменяем, импорт пропущен (для нового — другие параметры или label)",
                    id);
            return new Result(id, true, existing.get());
        }
        archive.createSchema();
        rejectLegacyLoad(p);
        if (!archive.isReady(id)) {
            load(p, id);
            archive.markReady(id);
        }
        List<String> loaded = archive.secids(id);
        String hash = archive.dataHash(id, SCHEMA_VERSION);
        Snapshot s = snapshots.register(id.toString(), description(p, id, loaded, hash), hash);
        log.info("Снапшот {} зарегистрирован, бумаг: {}", id, loaded.size());
        return new Result(id, false, s);
    }

    /**
     * Загрузка с теми же параметрами по схеме версии {@value #LEGACY_SCHEMA_VERSION} (сектор «первый по коду», нет
     * названий) имеет другой идентификатор, поэтому новый код начал бы новую загрузку с нуля, а недокачанные данные
     * старой остались бы в ClickHouse. Чтобы это не прошло молча, запуск прекращается: старую загрузку нужно удалить.
     */
    private void rejectLegacyLoad(Params p) {
        UUID legacy = p.snapshotId(LEGACY_SCHEMA_VERSION);
        if (snapshots.findByKey(legacy.toString()).isEmpty() && archive.countRows("securities", legacy) > 0) {
            throw new ImportException("В ClickHouse есть недогруженный снапшот " + legacy + " по схеме версии "
                    + LEGACY_SCHEMA_VERSION + ": сектор и названия там считаются по старым правилам, докачать его"
                    + " нельзя. Удалите его строки (DELETE … WHERE snapshot_id = '" + legacy + "' во всех таблицах"
                    + " архива) и запустите импорт заново: новый снапшот грузится с нуля");
        }
    }

    private void load(Params p, UUID id) {
        loadBenchmark(p, id);
        loadSecurities(p, id);
        List<String> secids = archive.secids(id);
        Set<LocalDate> trading = new HashSet<>(archive.candleDates(id, BENCHMARK, DAY));
        for (String secid : secids) {
            loadCandles(p, id, secid, trading);
        }
        loadCalendar(p, id);
        loadActions(id, secids);
        loadRuonia(p, id);
    }

    private void loadBenchmark(Params p, UUID id) {
        for (LocalDate[] chunk : benchmarkChunks(p)) {
            LocalDate from = chunk[0];
            LocalDate till = chunk[1];
            if (archive.countCandles(id, BENCHMARK, DAY, startOf(from), startOf(till.plusDays(1))) > 0) {
                continue;
            }
            List<Candle> candles = inRange(iss.indexDailyCandles(BENCHMARK, from, till), from, till);
            checkCalendar(candles, from, till, LocalDate.now(clock), p.allowGaps());
            archive.insertCandles(id, BENCHMARK, DAY, candles);
            log.info("{} дневные {}…{}: {} свечей", BENCHMARK, from, till, candles.size());
        }
    }

    /** Годовые куски дневного ряда IMOEX: от {@code from} минус запас до {@code to}. */
    private static List<LocalDate[]> benchmarkChunks(Params p) {
        LocalDate start = p.from().minusDays(LOOKBACK_DAYS);
        List<LocalDate[]> chunks = new ArrayList<>();
        for (YearMonth ym = YearMonth.from(start); !ym.isAfter(YearMonth.from(p.to())); ym = ym.plusYears(1)) {
            chunks.add(new LocalDate[] {
                max(start, ym.atDay(1)), min(p.to(), ym.plusMonths(11).atEndOfMonth())
            });
        }
        return chunks;
    }

    /**
     * Дневной ряд IMOEX — основа календаря и проверки покрытия. Пустой ответ — всегда ошибка: иначе неизменяемый
     * снапшот получит пустой календарь. Подозрительно редкий ряд ({@link #calendarWarnings}) бывает и на настоящих
     * событиях — остановка торгов 28.02–23.03.2022, — поэтому без {@code allowGaps} это ошибка с объяснением, а с ним
     * предупреждение в логе и запись в {@code description}.
     */
    static void checkCalendar(
            List<Candle> candles, LocalDate from, LocalDate till, LocalDate today, boolean allowGaps) {
        TreeSet<LocalDate> days = new TreeSet<>();
        candles.forEach(c -> days.add(IssParsers.mskDate(c)));
        if (days.isEmpty()) {
            throw new ImportException("IMOEX " + from + "…" + till + ": ISS отдал пустой дневной ряд");
        }
        List<String> warnings = calendarWarnings(days, from, till, today);
        if (warnings.isEmpty()) {
            return;
        }
        String report = "Календарь IMOEX " + from + "…" + till + " подозрительно редок: " + String.join(", ", warnings);
        if (!allowGaps) {
            throw new ImportException(report
                    + ".\nВозможна остановка торгов на бирже (как 28.02–23.03.2022) или усечённый ответ ISS. Если дни"
                    + " действительно нерабочие — перезапустите с --import.allow-gaps=true: календарь будет принят и"
                    + " записан в description снапшота");
        }
        log.warn("{}\nКалендарь принят (allow-gaps): возможна остановка торгов", report);
    }

    /**
     * Признаки усечённого ряда в куске {@code from}…{@code till}: в месяце, целиком лежащем внутри куска, торговых
     * дней меньше {@value #MIN_MONTH_SHARE_PERCENT} % будней (неполные месяцы на краях дают ложно малую долю, поэтому
     * не считаются); первый или последний день ряда дальше {@value #EDGE_DAYS} дней от границы куска (последний —
     * только для прошедших дат). Праздники не срабатывают: в январе РФ торгуется около 74 % будней, новогодний перерыв
     * короче {@value #EDGE_DAYS} дней. Доли за весь кусок нет: на коротком периоде с январём она ложно мала.
     */
    static List<String> calendarWarnings(SortedSet<LocalDate> days, LocalDate from, LocalDate till, LocalDate today) {
        List<String> warnings = new ArrayList<>();
        for (YearMonth ym = YearMonth.from(from); !ym.isAfter(YearMonth.from(till)); ym = ym.plusMonths(1)) {
            if (ym.atDay(1).isBefore(from) || ym.atEndOfMonth().isAfter(till)) {
                continue;
            }
            int weekdays = 0;
            int trading = 0;
            for (LocalDate d = ym.atDay(1); !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) {
                if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                    weekdays++;
                    trading += days.contains(d) ? 1 : 0;
                }
            }
            if (trading * 100 < weekdays * MIN_MONTH_SHARE_PERCENT) {
                warnings.add(ym + " " + trading + " из " + weekdays + " будних");
            }
        }
        if (days.first().isAfter(from.plusDays(EDGE_DAYS))) {
            warnings.add("ряд начинается " + days.first() + ", ожидалось около " + from);
        }
        if (till.isBefore(today) && days.last().isBefore(till.minusDays(EDGE_DAYS))) {
            warnings.add("ряд кончается " + days.last() + ", ожидалось около " + till);
        }
        return warnings;
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
        // бумага → отраслевой индекс → вес; сектор выбирает sectorIndex
        Map<String, Map<String, BigDecimal>> sectors = new TreeMap<>();
        for (String index : new TreeMap<>(SECTOR_INDEXES).keySet()) {
            iss.composition(index, asOf)
                    .weights()
                    .forEach((secid, weight) ->
                            sectors.computeIfAbsent(secid, k -> new TreeMap<>()).put(index, weight));
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
            Map<String, BigDecimal> own = sectors.getOrDefault(secid, Map.of());
            Optional<String> index = sectorIndex(own);
            if (own.size() != 1) {
                log.warn(
                        "{}: отраслевых индексов {} ({}), сектор — {}",
                        secid,
                        own.size(),
                        own,
                        index.map(SECTOR_INDEXES::get).orElse("пустая строка"));
            }
            rows.add(new ClickHouseArchive.SecurityRow(
                    secid, index.map(SECTOR_INDEXES::get).orElse(""), lot, step, name(info, r)));
        }
        if (rows.isEmpty()) {
            throw new ImportException("Ни одна бумага не прошла проверку, снапшот пуст");
        }
        archive.insertSecurities(id, rows);
    }

    /** Название бумаги: {@code SHORTNAME} («Сбербанк»), нет — {@code SECNAME}, нет обоих — пустая строка. */
    private static String name(IssTable info, List<tools.jackson.databind.JsonNode> row) {
        for (String column : List.of("SHORTNAME", "SECNAME")) {
            tools.jackson.databind.JsonNode v = info.get(row, column);
            if (v != null && !v.isNull() && !v.asString().isBlank()) {
                return v.asString().trim();
            }
        }
        return "";
    }

    /**
     * Отраслевой индекс, где вес бумаги наибольший ({@code decisions.md}, «Слепой режим»). Равные веса — индекс с
     * меньшим кодом по алфавиту (детерминированно, не зависит от порядка ответа ISS). Нет индексов — пусто: бумага без
     * сектора в слепой пул не попадает.
     */
    static Optional<String> sectorIndex(Map<String, BigDecimal> weightsByIndex) {
        return weightsByIndex.entrySet().stream()
                .max(Map.Entry.<String, BigDecimal>comparingByValue()
                        .thenComparing(
                                Map.Entry.<String, BigDecimal>comparingByKey().reversed()))
                .map(Map.Entry::getKey);
    }

    private void loadCandles(Params p, UUID id, String secid, Set<LocalDate> trading) {
        int total = 0;
        for (YearMonth ym = YearMonth.from(p.from()); !ym.isAfter(YearMonth.from(p.to())); ym = ym.plusMonths(1)) {
            LocalDate from = max(p.from(), ym.atDay(1));
            LocalDate till = min(p.to(), ym.atEndOfMonth());
            if (archive.countCandles(id, secid, MINUTE, startOf(from), startOf(till.plusDays(1))) > 0) {
                continue;
            }
            List<Candle> candles = inRange(iss.shareCandles(secid, 1, from, till), from, till);
            // листинг нужен только для куска, который грузим; загруженные куски при докачке его не запрашивают
            Map<LocalDate, String> problems = coverageProblems(from, till, candles, trading, listing(secid));
            if (!problems.isEmpty()) {
                String report = report(secid + " " + ym, problems);
                if (!p.allowGaps()) {
                    log.error("{}", report);
                    throw new ImportException(
                            report
                                    + "\nКусок не вставлен, повторный запуск перекачает его; --import.allow-gaps=true принимает пробелы");
                }
                log.warn("{}\nПробелы приняты (allow-gaps)", report);
                acceptedProblems.computeIfAbsent(secid, k -> new TreeMap<>()).putAll(problems);
            }
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

    /**
     * Покрытие куска ДО вставки. Ожидаемые дни — торговые дни календаря IMOEX внутри куска и периода торгов бумаги
     * (ISS, {@code listed_from}…{@code listed_till}; нет данных — весь кусок, тогда пропуск в начале или конце
     * периода — пробел). Для каждого ожидаемого дня: свечи есть, и последняя не раньше конца сессии IMOEX в этот день
     * минус допуск (обрыв посреди дня). Не проверяется: пропуск минут внутри дня и день, где бумага торговалась
     * меньше, чем индекс (допуск {@code end-of-day-tolerance}); день, на который у ISS нет внутридневных свечей IMOEX,
     * проверяется только на наличие свечей.
     */
    private Map<LocalDate, String> coverageProblems(
            LocalDate from,
            LocalDate till,
            List<Candle> candles,
            Set<LocalDate> trading,
            Optional<IssClient.Listing> listing) {
        Map<LocalDate, Instant> lastOfDay = new HashMap<>();
        for (Candle c : candles) {
            lastOfDay.merge(IssParsers.mskDate(c), c.tsOpen(), (a, b) -> a.isAfter(b) ? a : b);
        }
        Map<LocalDate, String> problems = new TreeMap<>();
        Map<LocalDate, LocalTime> ends = null;
        for (LocalDate d = from; !d.isAfter(till); d = d.plusDays(1)) {
            if (!trading.contains(d) || !tradesOn(listing, d)) {
                continue;
            }
            Instant last = lastOfDay.get(d);
            if (last == null) {
                problems.put(d, "нет свечей");
                continue;
            }
            if (ends == null) {
                ends = sessionEnds(from, till);
            }
            LocalTime end = ends.get(d);
            LocalTime lastTime = last.atOffset(IssParsers.MSK).toLocalTime();
            if (end != null && lastTime.isBefore(end.minus(endOfDayTolerance))) {
                problems.put(d, "оборван день: последняя свеча " + lastTime + ", сессия индекса до " + end);
            }
        }
        return problems;
    }

    private static boolean tradesOn(Optional<IssClient.Listing> listing, LocalDate d) {
        return listing.map(l -> !d.isBefore(l.from()) && !d.isAfter(l.till())).orElse(true);
    }

    private Optional<IssClient.Listing> listing(String secid) {
        return listings.computeIfAbsent(secid, iss::listing);
    }

    /** Начало последней 10-минутной свечи IMOEX за каждый день (МСК): не раньше этого времени обязана торговаться бумага. */
    private Map<LocalDate, LocalTime> sessionEnds(LocalDate from, LocalDate till) {
        return sessionEnds.computeIfAbsent(from + "|" + till, k -> {
            Map<LocalDate, LocalTime> ends = new HashMap<>();
            for (Candle c : iss.indexCandles(BENCHMARK, 10, from, till)) {
                ends.merge(
                        IssParsers.mskDate(c),
                        c.tsOpen().atOffset(IssParsers.MSK).toLocalTime(),
                        (a, b) -> a.isAfter(b) ? a : b);
            }
            return ends;
        });
    }

    private static String report(String what, Map<LocalDate, String> problems) {
        StringBuilder sb = new StringBuilder("Покрытие свечей " + what + ": " + problems.size() + " дн. с проблемами");
        problems.entrySet().stream()
                .limit(20)
                .forEach(e -> sb.append("\n  ").append(e.getKey()).append(": ").append(e.getValue()));
        return sb.toString();
    }

    /** Пропущенные торговые дни, уже лежащие в снапшоте (включая принятые в прошлых запусках). */
    private Map<LocalDate, String> storedMissingDays(Params p, UUID id, String secid, Set<LocalDate> trading) {
        Set<LocalDate> have = new HashSet<>(archive.candleDates(id, secid, MINUTE));
        Map<LocalDate, String> missing = new TreeMap<>();
        for (LocalDate d = p.from(); !d.isAfter(p.to()); d = d.plusDays(1)) {
            if (trading.contains(d) && !have.contains(d)) {
                missing.put(d, "нет свечей");
            }
        }
        if (!missing.isEmpty()) {
            // период торгов уточняем только если есть кандидаты в пробелы: лишнего запроса к ISS при целом снапшоте нет
            Optional<IssClient.Listing> listing = listing(secid);
            missing.keySet().removeIf(d -> !tradesOn(listing, d));
        }
        return missing;
    }

    private String description(Params p, UUID id, List<String> secids, String hash) {
        String base = "источник: MOEX ISS (свечи interval=1, IMOEX interval=24), ЦБ РФ (RUONIA); тикеры: "
                + String.join(",", secids) + "; интервал: 1 мин; период: " + p.from() + "…" + p.to()
                + "; получен: " + Instant.now(clock) + "; хэш: sha256:" + hash + "; версия схемы: " + SCHEMA_VERSION
                + (p.label().isEmpty() ? "" : "; метка: " + p.label());
        TreeSet<LocalDate> trading = new TreeSet<>(archive.candleDates(id, BENCHMARK, DAY));
        // календарь, как и пробелы, — по данным ClickHouse: кусок, принятый прошлым запуском, тоже попадёт в описание
        List<String> calendar = new ArrayList<>();
        for (LocalDate[] chunk : benchmarkChunks(p)) {
            SortedSet<LocalDate> inChunk = trading.subSet(chunk[0], true, chunk[1], true);
            if (!inChunk.isEmpty()) {
                calendar.addAll(calendarWarnings(inChunk, chunk[0], chunk[1], LocalDate.now(clock)));
            }
        }
        StringBuilder gaps = new StringBuilder();
        for (String secid : secids) {
            Map<LocalDate, String> all = new TreeMap<>(storedMissingDays(p, id, secid, trading));
            all.putAll(acceptedProblems.getOrDefault(secid, Map.of()));
            if (!all.isEmpty()) {
                gaps.append(gaps.length() == 0 ? "" : "; ")
                        .append(secid)
                        .append(": ")
                        .append(all.size())
                        .append(" дн. ")
                        .append(dates(all.keySet()));
            }
        }
        // календарь и пробелы пишем всегда, по данным ClickHouse; флаг лишь разрешает пометить снапшот готовым
        return base
                + (calendar.isEmpty() ? "" : "; календарь: " + String.join(", ", calendar))
                + (gaps.length() == 0 ? "" : "; пробелы: " + gaps)
                + (p.allowGaps() ? "; allow-gaps: включён" : "");
    }

    /** Даты по порядку; больше {@value #DESCRIPTION_DATES} — первые и последние через «…»: описание не разрастается. */
    static String dates(Set<LocalDate> sorted) {
        List<String> all = sorted.stream().map(LocalDate::toString).toList();
        if (all.size() <= DESCRIPTION_DATES) {
            return all.toString();
        }
        int head = (DESCRIPTION_DATES + 1) / 2;
        List<String> shown = new ArrayList<>(all.subList(0, head));
        shown.add("…");
        shown.addAll(all.subList(all.size() - (DESCRIPTION_DATES - head), all.size()));
        return shown.toString();
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
