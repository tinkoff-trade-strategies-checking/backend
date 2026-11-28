package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import ru.tinvest.trainer.api.DependencyUnavailableException;
import ru.tinvest.trainer.api.NotFoundException;
import ru.tinvest.trainer.marketdata.ArchiveCatalog.ArchivePaper;
import ru.tinvest.trainer.worker.PeriodCategory;
import ru.tinvest.trainer.worker.PeriodClassification;
import ru.tinvest.trainer.worker.PeriodWindow;
import ru.tinvest.trainer.worker.SimulationWorkerClient;

/**
 * Подбор периода и бумаг для запуска ручной сессии (backend#23, decisions.md: «Запуск сессии и прогона»,
 * «Стресс-сценарии»). Не создаёт сессию и ничего не пишет: возвращает {@link LaunchPlan} или {@link LaunchRejection}.
 * Категорию окна считает core ({@code ClassifyPeriods}); недоступность воркера или ClickHouse и отсутствие готового
 * снапшота — {@link DependencyUnavailableException} (503).
 *
 * <p>Случайный выбор (слепая сессия) детерминирован по {@code seed}: окна категории перемешиваются, берётся первое,
 * где набирается нужное число подходящих бумаг, то есть окно равномерно среди допустимых. В сессии сохраняется
 * выбранный период, а не seed.
 */
class DefaultLaunchPlanner implements LaunchPlanner {

    /** Сколько окон, превысивших лимит шагов, проверяем до отказа: каждая проверка — запрос к ClickHouse. */
    private static final int MAX_LIMIT_CHECKS = 50;

    /** Свечи с этим интервалом core агрегирует в любой шаг (core/AGENTS.md, {@code ClickHouseBarSource}). */
    private static final int BASE_INTERVAL_SEC = 60;

    /** Размер LRU-кэша результатов {@code classifyPeriods} по неизменяемому снапшоту (INV-5). */
    private static final int MAX_CLASSIFY_CACHE_ENTRIES = 16;

    private record ClassifyKey(
            String snapshotKey,
            int windowLength,
            LocalDate from,
            LocalDate to,
            @Nullable LocalDate startDate) {}

    private final Snapshots snapshots;
    private final ArchiveCatalog archive;
    private final SimulationWorkerClient worker;
    private final Set<String> pool;
    private final int maxBars;
    private final int runMaxBars;
    private final LongSupplier seeds;
    private final Map<ClassifyKey, PeriodClassification> classifyCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ClassifyKey, PeriodClassification> eldest) {
            return size() > MAX_CLASSIFY_CACHE_ENTRIES;
        }
    };

    DefaultLaunchPlanner(
            Snapshots snapshots,
            ArchiveCatalog archive,
            SimulationWorkerClient worker,
            Set<String> pool,
            int maxBars,
            LongSupplier seeds) {
        this(snapshots, archive, worker, pool, maxBars, maxBars, seeds);
    }

    DefaultLaunchPlanner(
            Snapshots snapshots,
            ArchiveCatalog archive,
            SimulationWorkerClient worker,
            Set<String> pool,
            int maxBars,
            int runMaxBars,
            LongSupplier seeds) {
        this.snapshots = snapshots;
        this.archive = archive;
        this.worker = worker;
        this.pool = pool;
        this.maxBars = maxBars;
        this.runMaxBars = runMaxBars;
        this.seeds = seeds;
    }

    @Override
    public LaunchResult plan(LaunchRequest request) {
        return planWithLimit(request, null, seeds.getAsLong(), maxBars);
    }

    @Override
    public LaunchResult plan(LaunchRequest request, @Nullable UUID snapshotId, long seed) {
        return planWithLimit(request, snapshotId, seed, maxBars);
    }

    @Override
    public LaunchResult planForRun(LaunchRequest request) {
        return planWithLimit(request, null, seeds.getAsLong(), runMaxBars);
    }

    @Override
    public LaunchResult planForRun(LaunchRequest request, @Nullable UUID snapshotId, long seed) {
        return planWithLimit(request, snapshotId, seed, runMaxBars);
    }

    private LaunchResult planWithLimit(LaunchRequest request, @Nullable UUID snapshotId, long seed, int barsLimit) {
        LaunchRejection invalid = validate(request);
        if (invalid != null) {
            return invalid;
        }
        // ранний отсев по нижней границе числа свечей; точное число шагов — по архиву после выбора окна и бумаг
        int minBars = request.durationDays() * request.timeframe().minCandlesPerDay();
        if (minBars > barsLimit) {
            return new LaunchRejection(LaunchRejectReason.TOO_MANY_BARS, "durationDays", minBars, barsLimit);
        }
        Snapshot snapshot = resolveSnapshot(snapshotId);
        String key = snapshot.snapshotKey();
        if (!archive.hasBenchmark(key)) {
            // снапшот версии схемы импортёра до 3: без IMOEX в securities воркер ответил бы FAILED_PRECONDITION
            throw new DependencyUnavailableException(
                    "Снапшот " + snapshot.id() + " загружен старой версией импортёра (нет IMOEX в securities): "
                            + "перезагрузите архив с новой меткой",
                    null);
        }

        Set<Integer> intervals = archive.intervals(key);
        int source;
        if (intervals.contains(request.timeframe().seconds())) {
            source = request.timeframe().seconds();
        } else if (intervals.contains(BASE_INTERVAL_SEC)) {
            source = BASE_INTERVAL_SEC;
        } else {
            return LaunchRejection.of(LaunchRejectReason.INVALID_PARAMS, "timeframe");
        }

        int length = request.durationDays();
        List<LocalDate> days = archive.tradingDays(key);
        if (days.size() < length) {
            return LaunchRejection.of(LaunchRejectReason.NO_MATCHING_PERIOD, "durationDays");
        }
        List<ArchivePaper> papers = poolPapers(key);
        Context ctx = new Context(request, snapshot, source, length, days, barsLimit);
        return request.startMode() == StartMode.CUSTOM ? planCustom(ctx, papers) : planRandom(ctx, papers, seed);
    }

    @Override
    public void warmUp() {
        Snapshot snapshot = resolveSnapshot(null);
        String key = snapshot.snapshotKey();
        archive.tradingDays(key);
        archive.securities(key);
        archive.corporateActions(key);
        for (int interval : archive.intervals(key)) {
            archive.candleDays(key, interval);
        }
        maxDurations(null);
    }

    @Override
    public List<PlannedPaper> papers(@Nullable UUID snapshotId) {
        Snapshot snapshot = resolveSnapshot(snapshotId);
        return poolPapers(snapshot.snapshotKey()).stream()
                .map(DefaultLaunchPlanner::planned)
                .toList();
    }

    @Override
    public List<Timeframe> availableTimeframes(@Nullable UUID snapshotId) {
        Set<Integer> intervals = archive.intervals(resolveSnapshot(snapshotId).snapshotKey());
        return Arrays.stream(Timeframe.values())
                .filter(t -> intervals.contains(t.seconds()) || intervals.contains(BASE_INTERVAL_SEC))
                .toList();
    }

    @Override
    public Map<Timeframe, Integer> maxDurations(@Nullable UUID snapshotId) {
        return maxDurationsWithLimit(snapshotId, maxBars);
    }

    @Override
    public Map<Timeframe, Integer> runMaxDurations(@Nullable UUID snapshotId) {
        return maxDurationsWithLimit(snapshotId, runMaxBars);
    }

    private Map<Timeframe, Integer> maxDurationsWithLimit(@Nullable UUID snapshotId, int barsLimit) {
        String key = resolveSnapshot(snapshotId).snapshotKey();
        Set<Integer> intervals = archive.intervals(key);
        Map<Timeframe, Integer> result = new LinkedHashMap<>();
        // шаги группируем по источнику свечей: один проход по свечам на источник
        Map<Integer, List<Timeframe>> bySource = new LinkedHashMap<>();
        for (Timeframe t : availableTimeframes(snapshotId)) {
            int source = intervals.contains(t.seconds()) ? t.seconds() : BASE_INTERVAL_SEC;
            bySource.computeIfAbsent(source, k -> new ArrayList<>()).add(t);
        }
        Map<Timeframe, Integer> perDay = new HashMap<>();
        bySource.forEach((source, frames) -> {
            Map<Integer, Integer> steps = archive.maxStepsPerDay(
                    key, source, frames.stream().map(Timeframe::seconds).toList());
            frames.forEach(t -> perDay.put(t, steps.getOrDefault(t.seconds(), 0)));
        });
        for (Timeframe t : availableTimeframes(snapshotId)) {
            int steps = perDay.get(t);
            result.put(t, steps <= 0 ? barsLimit : barsLimit / steps);
        }
        return result;
    }

    @Override
    public @Nullable DateRange archiveDateRange(@Nullable UUID snapshotId) {
        String key = resolveSnapshot(snapshotId).snapshotKey();
        List<LocalDate> days = archive.tradingDays(key);
        if (days.isEmpty()) {
            return null;
        }
        return new DateRange(days.getFirst(), days.getLast());
    }

    private record Context(
            LaunchRequest request,
            Snapshot snapshot,
            int sourceInterval,
            int length,
            List<LocalDate> days,
            int barsLimit) {}

    private LaunchResult planCustom(Context ctx, List<ArchivePaper> pool) {
        LaunchRequest request = ctx.request();
        LocalDate date = request.customStartDate();
        int index = Collections.binarySearch(ctx.days(), date);
        if (index < 0 || index + ctx.length() > ctx.days().size()) {
            return LaunchRejection.of(LaunchRejectReason.INVALID_PARAMS, "customStartDate");
        }
        Map<UUID, ArchivePaper> byId = pool.stream().collect(Collectors.toMap(p -> Papers.id(p.secid()), p -> p));
        List<ArchivePaper> chosen = new ArrayList<>();
        for (UUID id : request.paperIds()) {
            ArchivePaper paper = byId.get(id);
            if (paper == null) {
                return LaunchRejection.of(LaunchRejectReason.INVALID_PARAMS, "paperIds");
            }
            chosen.add(paper);
        }
        String key = ctx.snapshot().snapshotKey();
        PeriodClassification classification = classifyCached(
                key, ctx.length(), ctx.days().getFirst(), ctx.days().getLast(), date);
        PeriodWindow window = classification.windows().stream()
                .filter(w -> w.startDate().equals(date))
                .findFirst()
                .orElse(null);
        if (window == null || regime(window.category()) != request.regime()) {
            return LaunchRejection.of(LaunchRejectReason.NO_MATCHING_PERIOD, "customStartDate");
        }
        List<LocalDate> windowDays = windowDays(ctx, window);
        Map<String, List<LocalDate>> actions = archive.corporateActions(key);
        if (chosen.stream().anyMatch(p -> hasAction(actions, p.secid(), window, warmupStart(ctx, window)))) {
            return LaunchRejection.of(LaunchRejectReason.CORPORATE_ACTION_ON_PERIOD, "paperIds");
        }
        Map<String, Set<LocalDate>> candleDays = archive.candleDays(key, ctx.sourceInterval());
        if (!chosen.stream().allMatch(p -> covers(candleDays, p.secid(), windowDays))) {
            return LaunchRejection.of(LaunchRejectReason.NO_MATCHING_PERIOD, "paperIds");
        }
        return finish(ctx, window, classification, chosen, false);
    }

    private LaunchResult planRandom(Context ctx, List<ArchivePaper> pool, long seed) {
        LaunchRequest request = ctx.request();
        String key = ctx.snapshot().snapshotKey();
        int count = request.paperCount();
        PeriodClassification classification = classifyCached(
                key, ctx.length(), ctx.days().getFirst(), ctx.days().getLast(), null);
        List<PeriodWindow> windows = new ArrayList<>(classification.windows().stream()
                .filter(w -> regime(w.category()) == request.regime())
                .toList());
        Random random = new Random(seed);
        Collections.shuffle(windows, random);
        if (pool.size() < count) {
            // дело в числе бумаг, а не в категории: смена категории не поможет
            return LaunchRejection.of(LaunchRejectReason.INVALID_PARAMS, "paperCount");
        }
        // слепая сессия: приоритет у бумаг с сектором; если их не хватает на count, дополняем бумагами без сектора
        List<ArchivePaper> withSector = pool.stream()
                .filter(p -> !p.sector().isBlank())
                .sorted(Comparator.comparing(ArchivePaper::secid))
                .toList();
        List<ArchivePaper> withoutSector = pool.stream()
                .filter(p -> p.sector().isBlank())
                .sorted(Comparator.comparing(ArchivePaper::secid))
                .toList();
        Map<String, List<LocalDate>> actions = archive.corporateActions(key);
        Map<String, Set<LocalDate>> candleDays = archive.candleDays(key, ctx.sourceInterval());
        int overLimit = 0;
        for (PeriodWindow window : windows) {
            List<LocalDate> windowDays = windowDays(ctx, window);
            LocalDate warmupStart = warmupStart(ctx, window);
            List<ArchivePaper> eligibleWithSector = withSector.stream()
                    .filter(p -> !hasAction(actions, p.secid(), window, warmupStart)
                            && covers(candleDays, p.secid(), windowDays))
                    .collect(Collectors.toCollection(ArrayList::new));
            List<ArchivePaper> chosen;
            if (eligibleWithSector.size() >= count) {
                Collections.shuffle(eligibleWithSector, random);
                chosen = eligibleWithSector.subList(0, count);
            } else {
                List<ArchivePaper> eligibleWithoutSector = withoutSector.stream()
                        .filter(p -> !hasAction(actions, p.secid(), window, warmupStart)
                                && covers(candleDays, p.secid(), windowDays))
                        .collect(Collectors.toCollection(ArrayList::new));
                if (eligibleWithSector.size() + eligibleWithoutSector.size() < count) {
                    continue;
                }
                Collections.shuffle(eligibleWithoutSector, random);
                chosen = new ArrayList<>(count);
                chosen.addAll(eligibleWithSector);
                chosen.addAll(eligibleWithoutSector.subList(0, count - eligibleWithSector.size()));
                Collections.shuffle(chosen, random);
            }
            int steps = steps(ctx, windowDays, chosen);
            if (steps <= ctx.barsLimit()) {
                return plan(ctx, window, classification, chosen, true, steps);
            }
            // окно, где шагов больше лимита, не подходит: пропускаем, а не отказываем (иначе результат зависит от seed)
            if (++overLimit >= MAX_LIMIT_CHECKS) {
                break;
            }
        }
        if (overLimit > 0) {
            // подходящих окон не осталось из-за лимита; свойства скрытых окон (число шагов) клиенту не раскрываем
            return new LaunchRejection(LaunchRejectReason.TOO_MANY_BARS, "durationDays", null, ctx.barsLimit());
        }
        return LaunchRejection.of(LaunchRejectReason.NO_MATCHING_PERIOD, "regime");
    }

    private int steps(Context ctx, List<LocalDate> windowDays, List<ArchivePaper> chosen) {
        return archive.sessionSteps(
                ctx.snapshot().snapshotKey(),
                ctx.sourceInterval(),
                ctx.request().timeframe().seconds(),
                chosen.stream().map(ArchivePaper::secid).toList(),
                windowDays);
    }

    /** Торговые дни окна по календарю (граничные даты окна — торговые дни календаря). */
    private static List<LocalDate> windowDays(Context ctx, PeriodWindow window) {
        int from = Collections.binarySearch(ctx.days(), window.startDate());
        int to = Collections.binarySearch(ctx.days(), window.endDate());
        if (from < 0 || to < from) {
            throw new IllegalStateException("Окно core не совпало с торговым календарём снапшота");
        }
        return ctx.days().subList(from, to + 1);
    }

    /** Бумага покрывает окно, если свечи есть в каждый торговый день окна (а не просто N дней со свечами). */
    private static boolean covers(Map<String, Set<LocalDate>> candleDays, String secid, List<LocalDate> windowDays) {
        Set<LocalDate> dates = candleDays.get(secid);
        return dates != null && dates.containsAll(windowDays);
    }

    private static boolean hasAction(
            Map<String, List<LocalDate>> actions, String secid, PeriodWindow window, LocalDate warmupStart) {
        return actions.getOrDefault(secid, List.of()).stream()
                .anyMatch(d -> !d.isBefore(warmupStart) && !d.isAfter(window.endDate()));
    }

    /**
     * Начало окна прогрева: торговый день, с которого могут взяться {@link LaunchPlanner#WARMUP_BARS} шагов до начала
     * периода. Консервативно — по нижней границе числа свечей в дне ({@code Timeframe.minCandlesPerDay}) и с запасом в
     * один день; не дальше первого дня календаря.
     */
    private static LocalDate warmupStart(Context ctx, PeriodWindow window) {
        int perDay = ctx.request().timeframe().minCandlesPerDay();
        int warmupDays = (LaunchPlanner.WARMUP_BARS + perDay - 1) / perDay + 1;
        int from = Collections.binarySearch(ctx.days(), window.startDate());
        return ctx.days().get(Math.max(0, from - warmupDays));
    }

    /** Точное число шагов шкалы сессии по архиву и сравнение с лимитом ручной сессии (на одну бумагу). */
    private LaunchResult finish(
            Context ctx,
            PeriodWindow window,
            PeriodClassification classification,
            List<ArchivePaper> chosen,
            boolean blind) {
        int steps = steps(ctx, windowDays(ctx, window), chosen);
        if (steps > ctx.barsLimit()) {
            return new LaunchRejection(LaunchRejectReason.TOO_MANY_BARS, "durationDays", steps, ctx.barsLimit());
        }
        return plan(ctx, window, classification, chosen, blind, steps);
    }

    private static LaunchPlan plan(
            Context ctx,
            PeriodWindow window,
            PeriodClassification classification,
            List<ArchivePaper> chosen,
            boolean blind,
            int barsCount) {
        return new LaunchPlan(
                ctx.snapshot(),
                blind,
                window.startDate(),
                window.endDate(),
                ctx.length(),
                regime(window.category()),
                window.sigma(),
                window.mdd(),
                classification.p33(),
                classification.p80(),
                ctx.request().timeframe(),
                barsCount,
                chosen.stream().map(DefaultLaunchPlanner::planned).toList(),
                ctx.request().initialCapital());
    }

    private static PlannedPaper planned(ArchivePaper p) {
        return new PlannedPaper(Papers.id(p.secid()), p.secid(), p.sector(), p.lotSize(), p.priceStep(), p.name());
    }

    private static Regime regime(PeriodCategory category) {
        return switch (category) {
            case CALM -> Regime.CALM;
            case MEDIUM -> Regime.MEDIUM;
            case STRESS -> Regime.STRESS;
        };
    }

    private static @Nullable LaunchRejection validate(LaunchRequest r) {
        if (!DURATIONS.contains(r.durationDays())) {
            return LaunchRejection.of(LaunchRejectReason.INVALID_PARAMS, "durationDays");
        }
        BigDecimal capital = r.initialCapital();
        if (capital.compareTo(MIN_CAPITAL) < 0
                || capital.compareTo(MAX_CAPITAL) > 0
                || capital.stripTrailingZeros().scale() > 2) {
            return LaunchRejection.of(LaunchRejectReason.INVALID_PARAMS, "initialCapital");
        }
        if (r.startMode() == StartMode.RANDOM) {
            if (r.paperIds() != null) {
                return LaunchRejection.of(LaunchRejectReason.INVALID_PARAMS, "paperIds");
            }
            if (r.customStartDate() != null) {
                return LaunchRejection.of(LaunchRejectReason.INVALID_PARAMS, "customStartDate");
            }
            Integer count = r.paperCount();
            if (count == null || count < MIN_PAPERS) {
                return LaunchRejection.of(LaunchRejectReason.INVALID_PARAMS, "paperCount");
            }
        } else {
            if (r.paperCount() != null) {
                return LaunchRejection.of(LaunchRejectReason.INVALID_PARAMS, "paperCount");
            }
            if (r.customStartDate() == null) {
                return LaunchRejection.of(LaunchRejectReason.INVALID_PARAMS, "customStartDate");
            }
            List<UUID> ids = r.paperIds();
            if (ids == null
                    || ids.size() < MIN_PAPERS
                    || ids.stream().anyMatch(java.util.Objects::isNull)
                    || new HashSet<>(ids).size() != ids.size()) {
                return LaunchRejection.of(LaunchRejectReason.INVALID_PARAMS, "paperIds");
            }
        }
        return null;
    }

    private Snapshot resolveSnapshot(@Nullable UUID snapshotId) {
        if (snapshotId != null) {
            Snapshot snapshot = snapshots.find(snapshotId).orElseThrow(() -> new NotFoundException("Нет данных"));
            if (!archive.isReady(snapshot.snapshotKey())) {
                throw new NotFoundException("Нет данных");
            }
            return snapshot;
        }
        // в контракте нет подходящей причины отказа, а пользователь ничего не может изменить: 503
        return snapshots.findAllNewestFirst().stream()
                .filter(s -> archive.isReady(s.snapshotKey()))
                .findFirst()
                .orElseThrow(() -> new DependencyUnavailableException(
                        "Архив данных не готов: нет ни одного готового снапшота", null));
    }

    private PeriodClassification classifyCached(
            String snapshotKey, int windowLength, LocalDate from, LocalDate to, @Nullable LocalDate startDate) {
        ClassifyKey exactKey = new ClassifyKey(snapshotKey, windowLength, from, to, startDate);
        synchronized (classifyCache) {
            PeriodClassification hit = classifyCache.get(exactKey);
            if (hit != null) {
                return hit;
            }
            if (startDate != null) {
                PeriodClassification full =
                        classifyCache.get(new ClassifyKey(snapshotKey, windowLength, from, to, null));
                if (full != null) {
                    return full;
                }
            }
        }
        PeriodClassification loaded = worker.classifyPeriods(snapshotKey, windowLength, from, to, startDate);
        synchronized (classifyCache) {
            classifyCache.put(exactKey, loaded);
        }
        return loaded;
    }

    private List<ArchivePaper> poolPapers(String key) {
        return archive.securities(key).stream()
                .filter(p -> pool.isEmpty() || pool.contains(p.secid()))
                .toList();
    }
}
