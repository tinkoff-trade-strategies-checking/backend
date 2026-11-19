package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
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

    private final Snapshots snapshots;
    private final ArchiveCatalog archive;
    private final SimulationWorkerClient worker;
    private final Set<String> pool;
    private final int maxBars;
    private final LongSupplier seeds;

    DefaultLaunchPlanner(
            Snapshots snapshots,
            ArchiveCatalog archive,
            SimulationWorkerClient worker,
            Set<String> pool,
            int maxBars,
            LongSupplier seeds) {
        this.snapshots = snapshots;
        this.archive = archive;
        this.worker = worker;
        this.pool = pool;
        this.maxBars = maxBars;
        this.seeds = seeds;
    }

    @Override
    public LaunchResult plan(LaunchRequest request) {
        return plan(request, null, seeds.getAsLong());
    }

    @Override
    public LaunchResult plan(LaunchRequest request, @Nullable UUID snapshotId, long seed) {
        LaunchRejection invalid = validate(request);
        if (invalid != null) {
            return invalid;
        }
        // ранний отсев по нижней границе числа свечей; точное число шагов — по архиву после выбора окна и бумаг
        int minBars = request.durationDays() * request.timeframe().minCandlesPerDay();
        if (minBars > maxBars) {
            return new LaunchRejection(LaunchRejectReason.TOO_MANY_BARS, "durationDays", minBars, maxBars);
        }
        Snapshot snapshot = resolveSnapshot(snapshotId);
        String key = snapshot.snapshotKey();

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
        Context ctx = new Context(request, snapshot, source, length, days);
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

    private record Context(
            LaunchRequest request, Snapshot snapshot, int sourceInterval, int length, List<LocalDate> days) {}

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
        PeriodClassification classification = worker.classifyPeriods(
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
        if (chosen.stream().anyMatch(p -> hasAction(actions, p.secid(), window))) {
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
        PeriodClassification classification = worker.classifyPeriods(
                key, ctx.length(), ctx.days().getFirst(), ctx.days().getLast(), null);
        List<PeriodWindow> windows = new ArrayList<>(classification.windows().stream()
                .filter(w -> regime(w.category()) == request.regime())
                .toList());
        Random random = new Random(seed);
        Collections.shuffle(windows, random);
        // слепая сессия: бумаги без сектора в пул не попадают (decisions.md, «Слепой режим», 19.11.2026)
        List<ArchivePaper> withSector = pool.stream()
                .filter(p -> !p.sector().isBlank())
                .sorted(Comparator.comparing(ArchivePaper::secid))
                .toList();
        if (withSector.size() < count) {
            // дело в числе бумаг, а не в категории: смена категории не поможет (решение лида)
            return LaunchRejection.of(LaunchRejectReason.INVALID_PARAMS, "paperCount");
        }
        Map<String, List<LocalDate>> actions = archive.corporateActions(key);
        Map<String, Set<LocalDate>> candleDays = archive.candleDays(key, ctx.sourceInterval());
        int overLimit = 0;
        for (PeriodWindow window : windows) {
            List<LocalDate> windowDays = windowDays(ctx, window);
            List<ArchivePaper> eligible = withSector.stream()
                    .filter(p -> !hasAction(actions, p.secid(), window) && covers(candleDays, p.secid(), windowDays))
                    .collect(Collectors.toCollection(ArrayList::new));
            if (eligible.size() < count) {
                continue;
            }
            Collections.shuffle(eligible, random);
            List<ArchivePaper> chosen = eligible.subList(0, count);
            int steps = steps(ctx, windowDays, chosen);
            if (steps <= maxBars) {
                return plan(ctx, window, classification, chosen, true, steps);
            }
            // окно, где шагов больше лимита, не подходит: пропускаем, а не отказываем (иначе результат зависит от seed)
            if (++overLimit >= MAX_LIMIT_CHECKS) {
                break;
            }
        }
        if (overLimit > 0) {
            // подходящих окон не осталось из-за лимита; свойства скрытых окон (число шагов) клиенту не раскрываем
            return new LaunchRejection(LaunchRejectReason.TOO_MANY_BARS, "durationDays", null, maxBars);
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

    private static boolean hasAction(Map<String, List<LocalDate>> actions, String secid, PeriodWindow window) {
        return actions.getOrDefault(secid, List.of()).stream()
                .anyMatch(d -> !d.isBefore(window.startDate()) && !d.isAfter(window.endDate()));
    }

    /** Точное число шагов шкалы сессии по архиву и сравнение с лимитом ручной сессии (на одну бумагу). */
    private LaunchResult finish(
            Context ctx,
            PeriodWindow window,
            PeriodClassification classification,
            List<ArchivePaper> chosen,
            boolean blind) {
        int steps = steps(ctx, windowDays(ctx, window), chosen);
        if (steps > maxBars) {
            return new LaunchRejection(LaunchRejectReason.TOO_MANY_BARS, "durationDays", steps, maxBars);
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
        return new PlannedPaper(Papers.id(p.secid()), p.secid(), p.sector(), p.lotSize(), p.priceStep());
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
            if (count == null || count < MIN_PAPERS || count > MAX_PAPERS) {
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
                    || ids.size() > MAX_PAPERS
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

    private List<ArchivePaper> poolPapers(String key) {
        return archive.securities(key).stream()
                .filter(p -> pool.isEmpty() || pool.contains(p.secid()))
                .toList();
    }
}
