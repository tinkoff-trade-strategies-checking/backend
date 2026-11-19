package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import ru.tinvest.trainer.api.NotFoundException;
import ru.tinvest.trainer.marketdata.ArchiveCatalog.ArchivePaper;
import ru.tinvest.trainer.worker.PeriodCategory;
import ru.tinvest.trainer.worker.PeriodClassification;
import ru.tinvest.trainer.worker.PeriodWindow;
import ru.tinvest.trainer.worker.SimulationWorkerClient;

/**
 * Подбор периода и бумаг для запуска ручной сессии (backend#23, decisions.md: «Запуск сессии и прогона»,
 * «Стресс-сценарии»). Не создаёт сессию и ничего не пишет: возвращает {@link LaunchPlan} или {@link LaunchRejection}.
 * Категорию окна считает core ({@code ClassifyPeriods}); недоступность воркера или ClickHouse — исключение
 * {@link ru.tinvest.trainer.api.DependencyUnavailableException} (503).
 *
 * <p>Случайный выбор (слепая сессия) детерминирован по {@code seed}: окна категории перемешиваются, берётся первое,
 * где набирается нужное число подходящих бумаг, то есть окно равномерно среди допустимых. В сессии сохраняется
 * выбранный период, а не seed.
 */
public class LaunchPlanner {

    static final Set<Integer> DURATIONS = Set.of(20, 60, 120, 250);
    static final int MIN_PAPERS = 1;
    static final int MAX_PAPERS = 5;
    /** Свечи с этим интервалом core агрегирует в любой шаг (core/AGENTS.md, {@code ClickHouseBarSource}). */
    private static final int BASE_INTERVAL_SEC = 60;

    private final Snapshots snapshots;
    private final ArchiveCatalog archive;
    private final SimulationWorkerClient worker;
    private final Set<String> pool;
    private final int maxBars;
    private final LongSupplier seeds;

    LaunchPlanner(
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

    /** Подбор по последнему готовому снапшоту, случайность — из генератора приложения. */
    public LaunchResult plan(LaunchRequest request) {
        return plan(request, null, seeds.getAsLong());
    }

    /**
     * @param snapshotId снапшот или {@code null} — последний готовый
     * @param seed зерно случайного выбора окна и бумаг слепой сессии
     * @throws NotFoundException снапшот с этим id не найден или не готов
     */
    public LaunchResult plan(LaunchRequest request, @Nullable UUID snapshotId, long seed) {
        LaunchRejection invalid = validate(request);
        if (invalid != null) {
            return invalid;
        }
        int barsCount = request.durationDays() * request.timeframe().candlesPerDay();
        if (barsCount > maxBars) {
            return new LaunchRejection(LaunchRejectReason.TOO_MANY_BARS, "durationDays", barsCount, maxBars);
        }
        Optional<Snapshot> found = resolveSnapshot(snapshotId);
        if (found.isEmpty()) {
            return LaunchRejection.of(LaunchRejectReason.NO_MATCHING_PERIOD, "regime");
        }
        Snapshot snapshot = found.get();
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
        Context ctx = new Context(request, snapshot, source, barsCount, length, days);
        return request.startMode() == StartMode.CUSTOM ? planCustom(ctx, papers) : planRandom(ctx, papers, seed);
    }

    /** Бумаги пула в снапшоте (для экрана выбора бумаг при своей дате); {@code null} — последний готовый. */
    public List<PlannedPaper> papers(@Nullable UUID snapshotId) {
        Snapshot snapshot = resolveSnapshot(snapshotId).orElseThrow(() -> new NotFoundException("Нет данных"));
        return poolPapers(snapshot.snapshotKey()).stream()
                .map(LaunchPlanner::planned)
                .toList();
    }

    private record Context(
            LaunchRequest request,
            Snapshot snapshot,
            int sourceInterval,
            int barsCount,
            int length,
            List<LocalDate> days) {}

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
        Set<String> splits = archive.withCorporateActions(key, window.startDate(), window.endDate());
        if (chosen.stream().anyMatch(p -> splits.contains(p.secid()))) {
            return LaunchRejection.of(LaunchRejectReason.CORPORATE_ACTION_ON_PERIOD, "paperIds");
        }
        Set<String> covered = covered(ctx, window);
        if (!covered.containsAll(chosen.stream().map(ArchivePaper::secid).toList())) {
            return LaunchRejection.of(LaunchRejectReason.NO_MATCHING_PERIOD, "paperIds");
        }
        return plan(ctx, window, classification, chosen, false);
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
        for (PeriodWindow window : windows) {
            Set<String> covered = covered(ctx, window);
            Set<String> splits = archive.withCorporateActions(key, window.startDate(), window.endDate());
            List<ArchivePaper> eligible = withSector.stream()
                    .filter(p -> covered.contains(p.secid()) && !splits.contains(p.secid()))
                    .collect(Collectors.toCollection(ArrayList::new));
            if (eligible.size() >= count) {
                Collections.shuffle(eligible, random);
                return plan(ctx, window, classification, eligible.subList(0, count), true);
            }
        }
        return LaunchRejection.of(LaunchRejectReason.NO_MATCHING_PERIOD, "regime");
    }

    private Set<String> covered(Context ctx, PeriodWindow window) {
        Map<String, Integer> days = archive.daysWithCandles(
                ctx.snapshot().snapshotKey(), ctx.sourceInterval(), window.startDate(), window.endDate());
        Set<String> covered = new HashSet<>();
        days.forEach((secid, n) -> {
            if (n >= ctx.length()) {
                covered.add(secid);
            }
        });
        return covered;
    }

    private static LaunchPlan plan(
            Context ctx,
            PeriodWindow window,
            PeriodClassification classification,
            List<ArchivePaper> chosen,
            boolean blind) {
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
                ctx.barsCount(),
                chosen.stream().map(LaunchPlanner::planned).toList(),
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
        if (capital.signum() <= 0 || capital.stripTrailingZeros().scale() > 2) {
            return LaunchRejection.of(LaunchRejectReason.INVALID_PARAMS, "initialCapital");
        }
        if (r.startMode() == StartMode.RANDOM) {
            Integer count = r.paperCount();
            if (count == null || count < MIN_PAPERS || count > MAX_PAPERS) {
                return LaunchRejection.of(LaunchRejectReason.INVALID_PARAMS, "paperCount");
            }
        } else {
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

    private Optional<Snapshot> resolveSnapshot(@Nullable UUID snapshotId) {
        if (snapshotId != null) {
            Snapshot snapshot = snapshots.find(snapshotId).orElseThrow(() -> new NotFoundException("Нет данных"));
            if (!archive.isReady(snapshot.snapshotKey())) {
                throw new NotFoundException("Нет данных");
            }
            return Optional.of(snapshot);
        }
        return snapshots.findAllNewestFirst().stream()
                .filter(s -> archive.isReady(s.snapshotKey()))
                .findFirst();
    }

    private List<ArchivePaper> poolPapers(String key) {
        return archive.securities(key).stream()
                .filter(p -> pool.isEmpty() || pool.contains(p.secid()))
                .toList();
    }
}
