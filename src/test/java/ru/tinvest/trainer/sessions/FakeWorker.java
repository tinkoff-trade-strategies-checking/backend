package ru.tinvest.trainer.sessions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import ru.tinvest.trainer.api.DependencyUnavailableException;
import ru.tinvest.trainer.worker.PeriodClassification;
import ru.tinvest.trainer.worker.SimulationWorkerClient;
import ru.tinvest.trainer.worker.WorkerTypes.BarData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindBarData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindFillData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindOrderData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindPaperData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindPortfolioData;
import ru.tinvest.trainer.worker.WorkerTypes.BlindViewData;
import ru.tinvest.trainer.worker.WorkerTypes.Command;
import ru.tinvest.trainer.worker.WorkerTypes.CommandOutcome;
import ru.tinvest.trainer.worker.WorkerTypes.EstimateResult;
import ru.tinvest.trainer.worker.WorkerTypes.FillState;
import ru.tinvest.trainer.worker.WorkerTypes.JournalRecord;
import ru.tinvest.trainer.worker.WorkerTypes.Market;
import ru.tinvest.trainer.worker.WorkerTypes.OrderKind;
import ru.tinvest.trainer.worker.WorkerTypes.OrderParams;
import ru.tinvest.trainer.worker.WorkerTypes.OrderPhase;
import ru.tinvest.trainer.worker.WorkerTypes.OrderState;
import ru.tinvest.trainer.worker.WorkerTypes.PortfolioState;
import ru.tinvest.trainer.worker.WorkerTypes.RejectCode;
import ru.tinvest.trainer.worker.WorkerTypes.ReplayResult;
import ru.tinvest.trainer.worker.WorkerTypes.RunState;

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
    static final long BASE_TIME_MS = 1_647_241_200_000L; // 2022-03-14T07:00:00Z

    volatile boolean down;
    final List<List<JournalRecord>> replayJournals = new CopyOnWriteArrayList<>();
    final List<List<JournalRecord>> estimateJournals = new CopyOnWriteArrayList<>();
    final List<OrderParams> estimated = new CopyOnWriteArrayList<>();

    void reset() {
        down = false;
        replayJournals.clear();
        estimateJournals.clear();
        estimated.clear();
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

    @Override
    public ReplayResult replaySession(
            Market market, List<JournalRecord> journal, long upToStep, boolean includeHistory, boolean blind) {
        checkUp();
        replayJournals.add(List.copyOf(journal));
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
                        false));
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
                        false));
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
            for (long step = -WARMUP; step <= upToStep; step++) {
                for (int i = 0; i < market.papers().size(); i++) {
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
        PortfolioState portfolio = new PortfolioState(market.initialCash(), market.initialCash(), List.of());
        BlindViewData blindView = null;
        if (blind) {
            List<BlindPaperData> papers = new ArrayList<>();
            List<BlindOrderData> blindOrders = new ArrayList<>();
            for (int i = 0; i < market.papers().size(); i++) {
                papers.add(new BlindPaperData(i, new BigDecimal("5.00")));
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
                            new BigDecimal("100.00"), new BigDecimal("100.00"), BigDecimal.ZERO, List.of()),
                    blindOrders,
                    blindFills);
        }
        return new ReplayResult(
                TOTAL_STEPS,
                RunState.RUNNING,
                upToStep,
                history,
                portfolio,
                visible,
                fills,
                outcomes,
                WARMUP,
                blindView);
    }

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

    private void checkUp() {
        if (down) {
            throw new DependencyUnavailableException("Simulation Worker недоступен: UNAVAILABLE", null);
        }
    }
}
