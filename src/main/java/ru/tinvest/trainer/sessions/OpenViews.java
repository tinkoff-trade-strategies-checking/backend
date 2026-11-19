package ru.tinvest.trainer.sessions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.tinvest.trainer.worker.WorkerTypes.BarData;
import ru.tinvest.trainer.worker.WorkerTypes.EstimateResult;
import ru.tinvest.trainer.worker.WorkerTypes.FillState;
import ru.tinvest.trainer.worker.WorkerTypes.OrderState;
import ru.tinvest.trainer.worker.WorkerTypes.PositionState;
import ru.tinvest.trainer.worker.WorkerTypes.ReplayResult;

/**
 * Ответы раскрытой сессии (схемы {@code Open*} контракта): тикеры, названия, реальное время и рубли. Вызывается только
 * из {@link BlindMasking}, который решает, какой вид отдать.
 */
final class OpenViews {

    private OpenViews() {}

    static Map<String, Object> snapshot(ArenaState state) {
        SessionRecord session = state.session();
        ReplayResult replay = state.replay();
        ViewFormat.StepIndex index = new ViewFormat.StepIndex();
        replay.history().forEach(b -> index.add(b.step(), b.tradingDay()));
        index.build();

        Map<String, List<Object>> candles = new HashMap<>();
        Map<String, BigDecimal> lastClose = new HashMap<>();
        for (BarData bar : replay.history()) {
            candles.computeIfAbsent(bar.paperKey(), k -> new ArrayList<>()).add(candle(bar, index.at(bar.step())[1]));
            lastClose.put(bar.paperKey(), bar.close());
        }

        List<Object> papers = new ArrayList<>();
        for (PaperRecord paper : session.papers()) {
            Map<String, Object> stat = new LinkedHashMap<>();
            // изменение за день считает core; пока не отдаёт — null
            stat.put("changePct", null);
            // бумага без бара до курсора: цены ещё нет (null), заявку на неё ядро отклоняет до первого бара
            stat.put("lastPrice", ViewFormat.numberOrNull(lastClose.get(paper.secid())));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", paper.paperId());
            out.put("instrumentIndex", paper.paperIndex());
            out.put("label", ViewFormat.label(paper.paperIndex()));
            out.put("sector", paper.sector());
            out.put("ticker", paper.secid());
            // названий в архиве нет: name = тикер, как в GET /papers
            out.put("name", paper.secid());
            out.put("lotSize", paper.lotSize());
            out.put("candles", candles.getOrDefault(paper.secid(), List.of()));
            out.put("stat", stat);
            papers.add(out);
        }

        List<Object> fills = new ArrayList<>();
        for (FillState fill : replay.fills()) {
            fills.add(fill(state, fill, index, replay.history()));
        }
        List<Object> orders = new ArrayList<>();
        for (OrderState order : replay.orders()) {
            orders.add(order(state, order));
        }

        Map<String, Object> out = ViewFormat.snapshotBase(state, index.at(state.step())[0]);
        out.put("mode", "OPEN");
        out.put("papers", papers);
        out.put("portfolio", portfolio(state));
        out.put("fills", fills);
        out.put("orders", orders);
        return out;
    }

    /** Результат шага раскрытой сессии ({@code OpenStepResult}); правила набора — {@link BlindMasking#stepResult}. */
    static Map<String, Object> stepResult(
            ArenaState state, long fromStep, List<BarData> bars, Map<Long, int[]> positions, int day) {
        ViewFormat.StepIndex index = ViewFormat.StepIndex.of(positions);
        long step = state.step();

        Map<String, List<Object>> candles = new LinkedHashMap<>();
        Map<String, BigDecimal> lastClose = new HashMap<>();
        List<BarData> batch = bars.stream()
                .filter(b -> b.step() > fromStep && b.step() <= step)
                .sorted(java.util.Comparator.comparingLong(BarData::step))
                .toList();
        for (BarData bar : batch) {
            lastClose.put(bar.paperKey(), bar.close());
            candles.computeIfAbsent(paper(state, bar.paperKey()).paperId().toString(), k -> new ArrayList<>())
                    .add(candle(bar, index.at(bar.step())[1]));
        }
        Map<String, Object> paperStats = new LinkedHashMap<>();
        for (PaperRecord paper : state.session().papers()) {
            if (!lastClose.containsKey(paper.secid())) {
                continue;
            }
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("changePct", null);
            stat.put("lastPrice", ViewFormat.number(lastClose.get(paper.secid())));
            paperStats.put(paper.paperId().toString(), stat);
        }
        List<Object> fills = new ArrayList<>();
        for (FillState fill : state.replay().fills()) {
            if (fill.step() > fromStep && fill.step() <= step) {
                fills.add(fill(state, fill, index, batch));
            }
        }
        List<Object> orders = new ArrayList<>();
        for (OrderState order : ViewFormat.changedOrders(state, fromStep)) {
            orders.add(order(state, order));
        }
        Map<String, Object> out = ViewFormat.stepBase(state, day);
        out.put("mode", "OPEN");
        out.put("bars", candles);
        out.put("paperStats", paperStats);
        out.put("portfolio", portfolio(state));
        out.put("fills", fills);
        out.put("orders", orders);
        return out;
    }

    private static Map<String, Object> candle(BarData bar, int candleInDay) {
        Map<String, Object> candle = new LinkedHashMap<>();
        candle.put("step", bar.step());
        candle.put("day", bar.tradingDay());
        candle.put("candleInDay", candleInDay);
        candle.put("openedAt", ViewFormat.instant(bar.timeUnixMs()));
        candle.put("open", ViewFormat.number(bar.open()));
        candle.put("high", ViewFormat.number(bar.high()));
        candle.put("low", ViewFormat.number(bar.low()));
        candle.put("close", ViewFormat.number(bar.close()));
        candle.put("volume", bar.volumeLots());
        candle.put("afterHalt", bar.afterHalt());
        return candle;
    }

    static Map<String, Object> commandResult(
            Map<String, Object> base, ArenaState state, ArenaState.CommandReply reply) {
        List<Object> shown = new ArrayList<>();
        for (long id : reply.shownIds()) {
            state.replay().orders().stream()
                    .filter(o -> o.id() == id)
                    .findFirst()
                    .ifPresent(o -> shown.add(order(state, o)));
        }
        base.put("mode", "OPEN");
        base.put("order", shown.isEmpty() ? null : shown.get(0));
        base.put("orders", shown);
        base.put("portfolio", portfolio(state));
        return base;
    }

    static Map<String, Object> estimate(Map<String, Object> base, EstimateResult amounts) {
        base.put("mode", "OPEN");
        rubles(base, amounts);
        return base;
    }

    /** Рублёвые поля оценки: на верхнем уровне и в каждой ноге. */
    static void rubles(Map<String, Object> target, EstimateResult amounts) {
        target.put("referencePrice", ViewFormat.number(amounts.referencePrice()));
        target.put("amount", ViewFormat.money(amounts.amount()));
        target.put("commission", ViewFormat.money(amounts.commission()));
        target.put("exchangeFee", ViewFormat.money(amounts.exchangeFee()));
        target.put("requiredCash", ViewFormat.money(amounts.requiredCash()));
    }

    static Map<String, Object> order(ArenaState state, OrderState order) {
        BigDecimal[] levels = ViewFormat.levels(
                order.spec().kind(), order.spec().limitPrice(), order.spec().stopPrice());
        Map<String, Object> out = ViewFormat.orderBase(state, order, levels, order.trailingStopLevel());
        out.put("mode", "OPEN");
        out.put("paperId", paper(state, order.spec().paperKey()).paperId());
        return out;
    }

    static Map<String, Object> fill(
            ArenaState state, FillState fill, ViewFormat.StepIndex index, List<BarData> bars) {
        OrderState order = state.replay().orders().stream()
                .filter(o -> o.id() == fill.orderId())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Исполнение без заявки в состоянии сессии"));
        int[] position = index.at(fill.step());
        long openedAt = bars.stream()
                .filter(b -> b.step() == fill.step())
                .filter(b -> b.paperKey().equals(fill.paperKey()))
                .findFirst()
                .or(() -> bars.stream().filter(b -> b.step() == fill.step()).findFirst())
                .map(BarData::timeUnixMs)
                .orElseThrow(() -> new IllegalStateException("Исполнение без бара на своём шаге"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", DerivedIds.fill(state.session().id(), fill.orderId(), fill.step()));
        out.put("orderId", state.orderIds().get(fill.orderId()));
        out.put("paperId", paper(state, fill.paperKey()).paperId());
        out.put("step", fill.step());
        out.put("day", position[0]);
        out.put("candleInDay", position[1]);
        out.put("executedAt", ViewFormat.instant(openedAt));
        out.put("side", ViewFormat.side(fill.side()));
        out.put("type", ViewFormat.legType(order.spec().kind()));
        out.put("lots", fill.lots());
        out.put("price", ViewFormat.number(fill.price()));
        // проценты и общие издержки сделки считает core (core#54), backend ничего не складывает; пока не отдаёт — null
        out.put("amountPct", null);
        out.put("costPct", null);
        out.put("amount", ViewFormat.money(fill.value()));
        out.put("cost", null);
        out.put("stopAndTakeSameBar", fill.stopAndTakeSameBar());
        return out;
    }

    static Map<String, Object> portfolio(ArenaState state) {
        var portfolio = state.replay().portfolio();
        List<Object> positions = new ArrayList<>();
        for (PositionState p : portfolio.positions()) {
            Map<String, Object> position = new LinkedHashMap<>();
            position.put("paperId", paper(state, p.paperKey()).paperId());
            position.put("lots", p.lots());
            position.put("avgPrice", ViewFormat.number(p.avgPrice()));
            position.put("pnlPct", null);
            position.put("valuePct", null);
            position.put("marketValue", ViewFormat.money(p.marketValue()));
            positions.add(position);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "OPEN");
        out.put("equityPct", null);
        out.put("cashPct", null);
        out.put("investedPct", null);
        out.put("pnlPct", null);
        out.put("costsPct", null);
        out.put("equity", ViewFormat.money(portfolio.equity()));
        out.put("cash", ViewFormat.money(portfolio.cash()));
        out.put("invested", null);
        out.put("pnl", null);
        out.put("costs", null);
        out.put("positions", positions);
        return out;
    }

    private static PaperRecord paper(ArenaState state, String paperKey) {
        return state.session().papers().stream()
                .filter(p -> p.secid().equals(paperKey))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Бумага ответа воркера не входит в сессию"));
    }
}
