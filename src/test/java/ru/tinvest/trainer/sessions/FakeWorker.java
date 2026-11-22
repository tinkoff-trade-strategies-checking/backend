package ru.tinvest.trainer.sessions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import ru.tinvest.trainer.api.DependencyUnavailableException;
import ru.tinvest.trainer.worker.PeriodClassification;
import ru.tinvest.trainer.worker.SimulationWorkerClient;
import ru.tinvest.trainer.worker.WorkerTypes.AlgorithmInput;
import ru.tinvest.trainer.worker.WorkerTypes.AlgorithmPortfolioData;
import ru.tinvest.trainer.worker.WorkerTypes.BarData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindBarData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindFillData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindOrderData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindPaperData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindPortfolioData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindViewData;
import ru.tinvest.trainer.worker.WorkerTypes.CheckLevel;
import ru.tinvest.trainer.worker.WorkerTypes.Command;
import ru.tinvest.trainer.worker.WorkerTypes.CommandOutcome;
import ru.tinvest.trainer.worker.WorkerTypes.CostTotalsData;
import ru.tinvest.trainer.worker.WorkerTypes.DivergenceData;
import ru.tinvest.trainer.worker.WorkerTypes.DivergenceKind;
import ru.tinvest.trainer.worker.WorkerTypes.DivergenceOutput;
import ru.tinvest.trainer.worker.WorkerTypes.EquityPointData;
import ru.tinvest.trainer.worker.WorkerTypes.EstimateResult;
import ru.tinvest.trainer.worker.WorkerTypes.FillState;
import ru.tinvest.trainer.worker.WorkerTypes.InstrumentChangeData;
import ru.tinvest.trainer.worker.WorkerTypes.JournalRecord;
import ru.tinvest.trainer.worker.WorkerTypes.Market;
import ru.tinvest.trainer.worker.WorkerTypes.MetricsData;
import ru.tinvest.trainer.worker.WorkerTypes.MissedProfitableTradeData;
import ru.tinvest.trainer.worker.WorkerTypes.OrderKind;
import ru.tinvest.trainer.worker.WorkerTypes.OrderParams;
import ru.tinvest.trainer.worker.WorkerTypes.OrderPhase;
import ru.tinvest.trainer.worker.WorkerTypes.OrderState;
import ru.tinvest.trainer.worker.WorkerTypes.PortfolioState;
import ru.tinvest.trainer.worker.WorkerTypes.RejectCode;
import ru.tinvest.trainer.worker.WorkerTypes.ReplayResult;
import ru.tinvest.trainer.worker.WorkerTypes.ReportData;
import ru.tinvest.trainer.worker.WorkerTypes.RoundTripData;
import ru.tinvest.trainer.worker.WorkerTypes.RunState;
import ru.tinvest.trainer.worker.WorkerTypes.StepBars;
import ru.tinvest.trainer.worker.WorkerTypes.StepReplay;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyRunOutput;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyRunStatus;
import ru.tinvest.trainer.worker.WorkerTypes.TradeSide;
import ru.tinvest.trainer.worker.WorkerTypes.UnderwaterPointData;

/**
 * Воркер для тестов арены: без состояния, как настоящий (журнал приходит целиком). Отвечает заранее заданными
 * значениями. Правила: больше {@link #MAX_LOTS} лотов — {@code INSUFFICIENT_CASH}; слепая цена в журнале —
 * {@code INVALID_PARAMS}; перевод слепой цены в рубли — умножение на 2 (обратно — деление); слепая цена 999 не
 * переводится (нет {@code resolved_order}); рыночная заявка исполняется на следующем шаге.
 */
final class FakeWorker implements SimulationWorkerClient {

    static final int MAX_LOTS = 1000;
    static final BigDecimal BLIND_TO_RUBLES = new BigDecimal("2");
    static final BigDecimal UNCONVERTIBLE = new BigDecimal("999");
    static final long TOTAL_STEPS = 10;
    static final long WARMUP = 2;
    static final BigDecimal EQUITY_PER_STEP = new BigDecimal("100.00");
    static final long BASE_TIME_MS = 1_647_241_200_000L; // 2022-03-14T07:00:00Z

    volatile boolean down;
    /** Шаг банкротства: курсор дальше него останавливается на нём, статус {@code BANKRUPT}; {@code -1} — нет. */
    volatile long bankruptAt = -1;
    /** Бумаги без баров до курсора (в раскрытом виде их свечей нет). */
    volatile Set<String> noBars = Set.of();
    /** Если задан, {@code replaySession} сообщает о входе в {@code entered} и ждёт {@code gate}. */
    volatile CountDownLatch entered;

    volatile CountDownLatch gate;
    volatile Market lastMarket;

    final List<List<JournalRecord>> replayJournals = new CopyOnWriteArrayList<>();
    final List<List<JournalRecord>> estimateJournals = new CopyOnWriteArrayList<>();
    final List<OrderParams> estimated = new CopyOnWriteArrayList<>();
    final java.util.concurrent.atomic.AtomicInteger reportAttempts = new java.util.concurrent.atomic.AtomicInteger();
    final List<Long> dayRequests = new CopyOnWriteArrayList<>();
    final List<Long> replayUpToSteps = new CopyOnWriteArrayList<>();
    final List<ReportRequest> reportRequests = new CopyOnWriteArrayList<>();
    final List<DivergenceCall> divergenceRequests = new CopyOnWriteArrayList<>();

    void reset() {
        down = false;
        bankruptAt = -1;
        historyCalls.set(0);
        stepCalls.set(0);
        slowSnapshot = null;
        slowEntered = null;
        slowGate = null;
        noBars = Set.of();
        entered = null;
        gate = null;
        lastMarket = null;

        replayJournals.clear();
        estimateJournals.clear();
        estimated.clear();
        reportRequests.clear();
        divergenceRequests.clear();
        replayUpToSteps.clear();
        dayRequests.clear();
        reportAttempts.set(0);
    }

    @Override
    public String ping() {
        return "fake";
    }

    @Override
    public PeriodClassification classifyPeriods(
            String snapshotKey, int windowLength, LocalDate from, LocalDate to, LocalDate startDate) {
        throw new UnsupportedOperationException();
    }

    @Override
    public EstimateResult estimateOrder(Market market, List<JournalRecord> journal, long upToStep, OrderParams order) {
        checkUp();
        estimated.add(order);
        estimateJournals.add(List.copyOf(journal));
        OrderParams resolved = null;
        RejectCode reason = null;
        String field = "";
        if (order.blindPrice()) {
            if (UNCONVERTIBLE.equals(order.limitPrice())) {
                return result(false, RejectCode.INVALID_PARAMS, "limitPrice", null);
            }
            resolved = new OrderParams(
                    order.paperKey(),
                    order.side(),
                    order.kind(),
                    order.lots(),
                    times(order.limitPrice()),
                    times(order.stopPrice()),
                    order.trailingPercent(),
                    order.tif(),
                    false);
        }
        boolean canPlace = order.lots() <= MAX_LOTS;
        if (!canPlace) {
            reason = RejectCode.INSUFFICIENT_CASH;
        }
        return result(canPlace, reason, field, resolved);
    }

    private static EstimateResult result(boolean canPlace, RejectCode reason, String field, OrderParams resolved) {
        return new EstimateResult(
                canPlace,
                reason,
                field,
                new BigDecimal("200.00"),
                new BigDecimal("2000.00"),
                new BigDecimal("6.00"),
                new BigDecimal("0.20"),
                new BigDecimal("2006.20"),
                new BigDecimal("0.40"),
                new BigDecimal("0.01"),
                new BigDecimal("0.01"),
                new BigDecimal("0.41"),
                resolved);
    }

    private static BigDecimal times(BigDecimal price) {
        return price == null ? null : price.multiply(BLIND_TO_RUBLES);
    }

    private static BigDecimal half(BigDecimal price) {
        return price == null ? null : price.divide(BLIND_TO_RUBLES);
    }

    /** Сколько раз воркера просили полную историю ({@code replaySession} с историей). */
    final AtomicInteger historyCalls = new AtomicInteger();
    /** Сколько раз просили свечи пачки шагов ({@code replaySteps}). */
    final AtomicInteger stepCalls = new AtomicInteger();
    /** Если задан, воспроизведение сессии с этим {@code snapshotId} сообщает о входе и ждёт {@link #slowGate}. */
    volatile String slowSnapshot;

    volatile CountDownLatch slowEntered;
    volatile CountDownLatch slowGate;

    @Override
    public ReplayResult replaySession(
            Market market, List<JournalRecord> journal, long upToStep, boolean includeHistory, boolean blind) {
        if (includeHistory) {
            historyCalls.incrementAndGet();
        }
        return replay(market, journal, upToStep, includeHistory, blind);
    }

    @Override
    public StepReplay replaySteps(
            Market market, List<JournalRecord> journal, long upToStep, long fromStep, boolean blind) {
        stepCalls.incrementAndGet();
        ReplayResult full = replay(market, journal, upToStep, true, blind);
        List<BarData> bars =
                full.history().stream().filter(b -> b.step() > fromStep).toList();
        List<BlindBarData> blindBars = full.blindView() == null
                ? List.of()
                : full.blindView().history().stream()
                        .filter(b -> b.step() > fromStep)
                        .toList();
        BlindViewData view = full.blindView() == null
                ? null
                : new BlindViewData(
                        full.blindView().papers(),
                        List.of(),
                        full.blindView().portfolio(),
                        full.blindView().orders(),
                        full.blindView().fills());
        ReplayResult trimmed = new ReplayResult(
                full.totalSteps(),
                full.state(),
                full.lastStep(),
                List.of(),
                full.portfolio(),
                full.orders(),
                full.fills(),
                full.commandResults(),
                full.warmupSteps(),
                view,
                null,
                full.currentDay(),
                full.instrumentChanges());
        return new StepReplay(trimmed, new StepBars(bars, blindBars));
    }

    private ReplayResult replay(
            Market market, List<JournalRecord> journal, long upToStep, boolean includeHistory, boolean blind) {
        checkUp();
        waitAtSlowGate(market);
        lastMarket = market;
        waitAtGate();
        replayJournals.add(List.copyOf(journal));
        // конец периода — последний шаг шкалы; банкротство останавливает раньше
        long lastStep = Math.min(upToStep, TOTAL_STEPS - 1);
        RunState runState = lastStep >= TOTAL_STEPS - 1 ? RunState.FINISHED_END : RunState.RUNNING;
        if (bankruptAt >= 0 && lastStep >= bankruptAt) {
            lastStep = bankruptAt;
            runState = RunState.BANKRUPT;
        }
        replayUpToSteps.add(upToStep);
        Map<Long, OrderState> orders = new TreeMap<>();
        List<CommandOutcome> outcomes = new ArrayList<>();
        for (JournalRecord entry : journal) {
            switch (entry.command()) {
                case Command.Place place -> outcomes.add(place(market, orders, entry.seq(), entry.step(), place));
                case Command.Cancel cancel -> {
                    OrderState target = orders.get(cancel.orderId());
                    if (target == null || target.phase() != OrderPhase.ACTIVE) {
                        outcomes.add(rejected(entry.seq(), RejectCode.ORDER_NOT_ACTIVE, ""));
                    } else {
                        close(orders, target, RejectCode.CANCELLED_BY_USER, entry.step());
                        outcomes.add(new CommandOutcome(entry.seq(), true, null, "", List.of()));
                    }
                }
                case Command.Replace replace -> {
                    OrderState target = orders.get(replace.orderId());
                    if (target == null || target.phase() != OrderPhase.ACTIVE) {
                        outcomes.add(rejected(entry.seq(), RejectCode.ORDER_NOT_ACTIVE, ""));
                    } else {
                        close(orders, target, RejectCode.REPLACED, entry.step());
                        outcomes.add(place(market, orders, entry.seq(), entry.step(), replace.replacement()));
                    }
                }
            }
        }

        List<FillState> fills = new ArrayList<>();
        List<BlindFillData> blindFills = new ArrayList<>();
        for (OrderState order : orders.values()) {
            if (order.phase() == OrderPhase.FILLED && order.closedStep() <= upToStep) {
                int index = indexOf(market, order.spec().paperKey());
                BigDecimal price = rubles(index, order.closedStep());
                fills.add(new FillState(
                        order.id(),
                        order.closedStep(),
                        order.spec().paperKey(),
                        order.spec().side(),
                        order.spec().lots(),
                        price,
                        price.multiply(BigDecimal.valueOf(order.spec().lots() * 10)),
                        new BigDecimal("6.00"),
                        new BigDecimal("0.20"),
                        new BigDecimal("1.00"),
                        new BigDecimal("0.50"),
                        false,
                        fills.size(),
                        new BigDecimal("7.70"),
                        new BigDecimal("0.08"),
                        new BigDecimal("2.00")));
                blindFills.add(new BlindFillData(
                        order.id(),
                        order.closedStep(),
                        index,
                        order.spec().side(),
                        order.spec().lots(),
                        blindPrice(order.closedStep()),
                        new BigDecimal("0.40"),
                        new BigDecimal("0.01"),
                        new BigDecimal("0.01"),
                        new BigDecimal("0.02"),
                        new BigDecimal("0.01"),
                        false,
                        new BigDecimal("0.08"),
                        blindFills.size()));
            }
        }
        // исполненная после курсора заявка пока активна
        List<OrderState> visible = new ArrayList<>();
        for (OrderState order : orders.values()) {
            visible.add(
                    order.phase() == OrderPhase.FILLED && order.closedStep() > upToStep
                            ? new OrderState(
                                    order.id(),
                                    order.spec(),
                                    order.placedStep(),
                                    OrderPhase.ACTIVE,
                                    null,
                                    "",
                                    0,
                                    order.ocoSiblingId(),
                                    null,
                                    -1)
                            : order);
        }

        List<BarData> history = new ArrayList<>();
        List<BlindBarData> blindHistory = new ArrayList<>();
        if (includeHistory) {
            for (long step = -WARMUP; step <= lastStep; step++) {
                for (int i = 0; i < market.papers().size(); i++) {
                    if (noBars.contains(market.papers().get(i).key())) {
                        continue;
                    }
                    int day = step < 0 ? 0 : (int) (1 + step / 3);
                    history.add(new BarData(
                            step,
                            market.papers().get(i).key(),
                            BASE_TIME_MS + step * 3_600_000L,
                            day,
                            rubles(i, step),
                            rubles(i, step).add(BigDecimal.ONE),
                            rubles(i, step).subtract(BigDecimal.ONE),
                            rubles(i, step),
                            1000 + step,
                            false));
                    blindHistory.add(new BlindBarData(
                            step,
                            i,
                            day,
                            blindPrice(step),
                            blindPrice(step).add(BigDecimal.ONE),
                            blindPrice(step).subtract(BigDecimal.ONE),
                            blindPrice(step),
                            new BigDecimal("100.00"),
                            false));
                }
            }
        }
        PortfolioState portfolio = new PortfolioState(
                market.initialCash(),
                market.initialCash(),
                List.of(),
                new BigDecimal("0.00"),
                new BigDecimal("0.08"),
                new BigDecimal("0.00"),
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                new BigDecimal("7.70"));
        List<InstrumentChangeData> changes = new ArrayList<>();
        for (int i = 0; i < market.papers().size(); i++) {
            if (!noBars.contains(market.papers().get(i).key())) {
                changes.add(new InstrumentChangeData(
                        market.papers().get(i).key(), new BigDecimal("1.23"), new BigDecimal("4.56")));
            }
        }
        BlindViewData blindView = null;
        if (blind) {
            List<BlindPaperData> papers = new ArrayList<>();
            List<BlindOrderData> blindOrders = new ArrayList<>();
            for (int i = 0; i < market.papers().size(); i++) {
                boolean bars = !noBars.contains(market.papers().get(i).key());
                papers.add(new BlindPaperData(
                        i,
                        new BigDecimal("5.00"),
                        bars ? new BigDecimal("1.23") : null,
                        bars ? new BigDecimal("4.56") : null));
            }
            for (OrderState order : visible) {
                blindOrders.add(new BlindOrderData(
                        order.id(),
                        indexOf(market, order.spec().paperKey()),
                        half(order.spec().limitPrice()),
                        half(order.spec().stopPrice()),
                        null));
            }
            blindView = new BlindViewData(
                    papers,
                    blindHistory,
                    new BlindPortfolioData(
                            new BigDecimal("100.00"),
                            new BigDecimal("100.00"),
                            BigDecimal.ZERO,
                            List.of(),
                            new BigDecimal("0.00"),
                            new BigDecimal("0.08")),
                    blindOrders,
                    blindFills);
        }
        return new ReplayResult(
                TOTAL_STEPS,
                runState,
                lastStep,
                history,
                portfolio,
                visible,
                fills,
                outcomes,
                WARMUP,
                blindView,
                null,
                lastStep < 0 ? 0 : (int) (1 + lastStep / 3),
                changes);
    }

    /** Отчёт по заранее заданным значениям: капитал растёт на {@link #EQUITY_PER_STEP} за шаг, бенчмарк — на концах дней. */
    @Override
    public ReplayResult replayWithReport(Market market, List<JournalRecord> journal, long upToStep) {
        reportAttempts.incrementAndGet();
        ReplayResult base = replaySession(market, journal, upToStep, true, false);
        reportRequests.add(new ReportRequest(List.copyOf(journal), upToStep));
        List<EquityPointData> equity = new ArrayList<>();
        List<EquityPointData> benchmark = new ArrayList<>();
        for (long step = 0; step <= upToStep; step++) {
            equity.add(new EquityPointData(
                    step, market.initialCash().add(EQUITY_PER_STEP.multiply(BigDecimal.valueOf(step)))));
            if (step % 3 == 2) {
                benchmark.add(new EquityPointData(step, market.initialCash().add(BigDecimal.valueOf(step * 5))));
            }
        }
        List<RoundTripData> trips = upToStep >= 3
                ? List.of(new RoundTripData(
                        market.papers().get(0).key(),
                        1,
                        3,
                        2,
                        new BigDecimal("200.00"),
                        new BigDecimal("210.00"),
                        new BigDecimal("200.00"),
                        new BigDecimal("12.00"),
                        new BigDecimal("188.00"),
                        0))
                : List.of();
        List<UnderwaterPointData> underwater = new ArrayList<>();
        for (EquityPointData point : equity) {
            underwater.add(new UnderwaterPointData(point.step(), new BigDecimal("0.00")));
        }
        ReportData report = new ReportData(
                new MetricsData(
                        0.0123,
                        0.0125,
                        null,
                        0.004,
                        3,
                        null,
                        null,
                        null,
                        1.5,
                        2,
                        trips.size(),
                        trips.isEmpty() ? null : 1.0,
                        1,
                        0,
                        0.5,
                        0.3,
                        null,
                        20,
                        0,
                        2),
                new CostTotalsData(
                        new BigDecimal("6.00"),
                        new BigDecimal("0.20"),
                        new BigDecimal("1.00"),
                        new BigDecimal("0.50"),
                        new BigDecimal("7.70")),
                equity,
                market.initialCash().add(EQUITY_PER_STEP.multiply(BigDecimal.valueOf(upToStep))),
                benchmark,
                trips,
                underwater);
        return new ReplayResult(
                base.totalSteps(),
                base.state(),
                base.lastStep(),
                base.history(),
                base.portfolio(),
                base.orders(),
                base.fills(),
                base.commandResults(),
                base.warmupSteps(),
                null,
                report,
                base.currentDay(),
                base.instrumentChanges());
    }

    /** Номер дня на курсоре: как в {@code replaySession} (прогрев — 0). */
    @Override
    public int tradingDaysAt(Market market, long upToStep) {
        checkUp();
        dayRequests.add(upToStep);
        return upToStep < 0 ? 0 : (int) (1 + upToStep / 3);
    }

    @Override
    public DivergenceOutput analyzeDivergences(
            Market market, List<JournalRecord> journal, long lastStep, List<AlgorithmInput> algorithms) {
        checkUp();
        divergenceRequests.add(new DivergenceCall(List.copyOf(journal), lastStep, List.copyOf(algorithms)));
        List<EquityPointData> equity = new ArrayList<>();
        List<UnderwaterPointData> underwater = new ArrayList<>();
        BigDecimal algoPerStep = new BigDecimal("200.00");
        for (long step = 0; step <= lastStep; step++) {
            equity.add(new EquityPointData(
                    step, market.initialCash().add(algoPerStep.multiply(BigDecimal.valueOf(step)))));
            underwater.add(new UnderwaterPointData(step, new BigDecimal("0.00")));
        }
        ReportData algoReport = new ReportData(
                new MetricsData(
                        0.0246, 0.025, null, 0.002, 1, null, null, null, 1.2, 2, 1, 1.0, 0, 0, 0.4, 0.25, null, 20, 0,
                        1),
                new CostTotalsData(
                        new BigDecimal("5.00"),
                        new BigDecimal("0.15"),
                        new BigDecimal("0.80"),
                        new BigDecimal("0.40"),
                        new BigDecimal("6.35")),
                equity,
                market.initialCash().add(algoPerStep.multiply(BigDecimal.valueOf(lastStep))),
                List.of(),
                List.of(),
                underwater);
        List<AlgorithmPortfolioData> portfolios = new ArrayList<>(algorithms.size());
        for (AlgorithmInput alg : algorithms) {
            portfolios.add(new AlgorithmPortfolioData(
                    alg.id(),
                    new StrategyRunOutput(
                            StrategyRunStatus.COMPLETED,
                            CheckLevel.OK,
                            List.of(),
                            algoReport,
                            List.of(),
                            List.of(),
                            List.of(),
                            12345L,
                            lastStep,
                            TOTAL_STEPS,
                            List.of())));
        }
        List<DivergenceData> divergences = new ArrayList<>();
        List<MissedProfitableTradeData> missedProfitable = new ArrayList<>();
        if (lastStep >= 2 && !algorithms.isEmpty()) {
            String algId = algorithms.getFirst().id();
            JournalRecord humanPlace = journal.stream()
                    .filter(j -> j.step() < lastStep && j.command() instanceof Command.Place)
                    .findFirst()
                    .orElse(null);
            if (humanPlace != null) {
                Command.Place place = (Command.Place) humanPlace.command();
                long signedLots = place.order().side() == TradeSide.BUY
                        ? place.order().lots()
                        : -place.order().lots();
                divergences.add(new DivergenceData(
                        algId,
                        place.order().paperKey(),
                        DivergenceKind.EXCESS,
                        humanPlace.step(),
                        -1,
                        signedLots,
                        0,
                        Math.min(lastStep, humanPlace.step() + 2),
                        new BigDecimal("150.00"),
                        new BigDecimal("0.03"),
                        "алгоритм здесь не действовал бы",
                        0,
                        place.order().kind(),
                        null));
                divergences.add(new DivergenceData(
                        algId,
                        market.papers().getFirst().key(),
                        DivergenceKind.MISSED,
                        -1,
                        1,
                        0,
                        1,
                        Math.min(lastStep, 3),
                        new BigDecimal("-50.00"),
                        new BigDecimal("-0.01"),
                        "SMA(20) пересекла SMA(50) снизу вверх",
                        1,
                        null,
                        OrderKind.MARKET));
                if (lastStep >= 3) {
                    missedProfitable.add(new MissedProfitableTradeData(
                            place.order().paperKey(),
                            1,
                            3,
                            place.order().lots(),
                            new BigDecimal("188.00"),
                            List.of(algId),
                            new BigDecimal("200.00"),
                            new BigDecimal("210.00"),
                            0,
                            List.of(0L)));
                }
            } else {
                divergences.add(new DivergenceData(
                        algId,
                        market.papers().getFirst().key(),
                        DivergenceKind.MISSED,
                        -1,
                        1,
                        0,
                        1,
                        Math.min(lastStep, 3),
                        new BigDecimal("120.00"),
                        new BigDecimal("0.02"),
                        "SMA(20) пересекла SMA(50) снизу вверх",
                        0,
                        null,
                        OrderKind.MARKET));
            }
        }
        return new DivergenceOutput(divergences, missedProfitable, List.of(), portfolios);
    }

    record ReportRequest(List<JournalRecord> journal, long upToStep) {}

    record DivergenceCall(List<JournalRecord> journal, long lastStep, List<AlgorithmInput> algorithms) {}

    private static BigDecimal rubles(int paperIndex, long step) {
        return BigDecimal.valueOf(20000 + 1000L * paperIndex + 100L * step, 2);
    }

    private static BigDecimal blindPrice(long step) {
        return BigDecimal.valueOf(10000 + 50L * step, 2);
    }

    private static int indexOf(Market market, String key) {
        for (int i = 0; i < market.papers().size(); i++) {
            if (market.papers().get(i).key().equals(key)) {
                return i;
            }
        }
        throw new IllegalStateException("нет бумаги " + key);
    }

    private static CommandOutcome rejected(long seq, RejectCode reason, String field) {
        return new CommandOutcome(seq, false, reason, field, List.of());
    }

    private static CommandOutcome place(
            Market market, Map<Long, OrderState> orders, long seq, long step, Command.Place place) {
        for (OrderParams spec : specs(place)) {
            if (spec.blindPrice()) {
                return rejected(seq, RejectCode.INVALID_PARAMS, "isBlindPrice");
            }
            if (spec.lots() > MAX_LOTS) {
                return rejected(seq, RejectCode.INSUFFICIENT_CASH, "");
            }
        }
        List<Long> ids = new ArrayList<>();
        boolean oco = place.ocoWith() != null;
        long first = seq * 2;
        orders.put(first, order(first, place.order(), step, oco ? first + 1 : -1));
        ids.add(first);
        if (oco) {
            orders.put(first + 1, order(first + 1, place.ocoWith(), step, first));
            ids.add(first + 1);
        }
        return new CommandOutcome(seq, true, null, "", ids);
    }

    private static List<OrderParams> specs(Command.Place place) {
        return place.ocoWith() == null ? List.of(place.order()) : List.of(place.order(), place.ocoWith());
    }

    private static OrderState order(long id, OrderParams spec, long step, long sibling) {
        boolean market = spec.kind() == OrderKind.MARKET;
        return new OrderState(
                id,
                spec,
                step,
                market ? OrderPhase.FILLED : OrderPhase.ACTIVE,
                null,
                "",
                market ? spec.lots() : 0,
                sibling,
                null,
                market ? step + 1 : -1);
    }

    private static void close(Map<Long, OrderState> orders, OrderState target, RejectCode reason, long step) {
        List<Long> ids = new ArrayList<>();
        ids.add(target.id());
        if (target.ocoSiblingId() >= 0) {
            ids.add(target.ocoSiblingId());
        }
        for (long id : ids) {
            OrderState o = orders.get(id);
            orders.put(
                    id,
                    new OrderState(
                            o.id(),
                            o.spec(),
                            o.placedStep(),
                            OrderPhase.CANCELLED,
                            reason,
                            "",
                            o.filledLots(),
                            o.ocoSiblingId(),
                            null,
                            step));
        }
    }

    private void waitAtSlowGate(Market market) {
        CountDownLatch g = slowGate;
        if (g == null || !market.snapshotId().equals(slowSnapshot)) {
            return;
        }
        slowEntered.countDown();
        try {
            if (!g.await(5, TimeUnit.MINUTES)) {
                throw new IllegalStateException("slowGate не открыт");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void waitAtGate() {
        CountDownLatch g = gate;
        if (g == null) {
            return;
        }
        entered.countDown();
        try {
            if (!g.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("gate не открыт");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void checkUp() {
        if (down) {
            throw new DependencyUnavailableException("Simulation Worker недоступен: UNAVAILABLE", null);
        }
    }
}
