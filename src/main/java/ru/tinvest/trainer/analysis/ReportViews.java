package ru.tinvest.trainer.analysis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import ru.tinvest.trainer.sessions.DerivedIds;
import ru.tinvest.trainer.sessions.PaperRecord;
import ru.tinvest.trainer.sessions.SessionArchive;
import ru.tinvest.trainer.sessions.SessionRecord;
import ru.tinvest.trainer.sessions.ViewFormat;
import ru.tinvest.trainer.worker.WorkerTypes.BarData;
import ru.tinvest.trainer.worker.WorkerTypes.DivergenceData;
import ru.tinvest.trainer.worker.WorkerTypes.DivergenceKind;
import ru.tinvest.trainer.worker.WorkerTypes.DivergenceOutput;
import ru.tinvest.trainer.worker.WorkerTypes.EquityPointData;
import ru.tinvest.trainer.worker.WorkerTypes.FillState;
import ru.tinvest.trainer.worker.WorkerTypes.MetricsData;
import ru.tinvest.trainer.worker.WorkerTypes.MissedProfitableTradeData;
import ru.tinvest.trainer.worker.WorkerTypes.OrderKind;
import ru.tinvest.trainer.worker.WorkerTypes.OrderState;
import ru.tinvest.trainer.worker.WorkerTypes.ReplayResult;
import ru.tinvest.trainer.worker.WorkerTypes.ReportData;
import ru.tinvest.trainer.worker.WorkerTypes.RoundTripData;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyRunOutput;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyRunStatus;
import ru.tinvest.trainer.worker.WorkerTypes.UnderwaterPointData;

/**
 * Раскрытый отчёт завершённой сессии ({@code SessionReport}). Backend ничего не считает: значения берутся из отчёта
 * ядра; единственное преобразование — доли ядра в проценты контракта (×100, без округления, до шести знаков) и
 * шаг → время бара. Производные величины (общая сумма издержек, издержки исполнений, кривая просадки, длительность
 * просадки в днях, разбор расхождений ФТ-18…20) считает core.
 */
final class ReportViews {

    private ReportViews() {}

    record BackgroundAnalysis(String algorithmName, DivergenceOutput output) {}

    /** Раскрытый отчёт ({@code SessionReport}) без фонового алгоритма (для старых сессий до V18). */
    static Map<String, Object> report(SessionArchive.CompletedReplay finished) {
        return report(finished, null);
    }

    /** Раскрытый отчёт ({@code SessionReport}) по журналу завершённой сессии и разбору расхождений. */
    static Map<String, Object> report(SessionArchive.CompletedReplay finished, @Nullable BackgroundAnalysis analysis) {
        SessionRecord session = finished.session();
        ReplayResult replay = finished.replay();
        ReportData report = replay.report();
        Map<Long, Long> times = stepTimes(replay);
        ViewFormat.StepIndex index = new ViewFormat.StepIndex();
        replay.history().forEach(b -> index.add(b.step(), b.tradingDay()));
        index.build();

        StrategyRunOutput algoRun =
                analysis == null || analysis.output().algorithmPortfolios().isEmpty()
                        ? null
                        : analysis.output().algorithmPortfolios().getFirst().result();
        ReportData algoReport = algoRun == null ? null : algoRun.report();

        Map<String, Object> period = new LinkedHashMap<>();
        period.put("startDate", session.periodStart().toString());
        period.put("endDate", session.periodEnd().toString());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", session.id());
        out.put("blind", session.blind());
        out.put("finishedEarly", "finished_early".equals(session.status()));
        out.put("replayAvailable", true);
        out.put("playedDays", finished.playedDays());
        out.put("totalDays", session.tradingDays());
        out.put("timeframe", session.timeframe().code());
        out.put("regime", session.regime().code());
        out.put("createdAt", ViewFormat.instant(session.createdAt().toInstant().toEpochMilli()));
        out.put("backgroundAlgorithmName", analysis == null ? null : analysis.algorithmName());
        out.put("period", period);
        out.put("papers", papers(session, replay));
        out.put("humanMetrics", metrics(session, report, "bankrupt".equals(session.status())));
        out.put(
                "algoMetrics",
                algoReport == null
                        ? null
                        : metrics(session, algoReport, algoRun.status() == StrategyRunStatus.BANKRUPT_STOP));
        out.put("comparison", algoReport == null ? null : comparison(report, algoReport));
        out.put("equityCurve", equityCurve(report, algoReport, times));
        out.put("underwaterCurve", underwaterCurve(report, algoReport, times));
        if (analysis == null) {
            out.put("divergenceSummary", null);
            out.put("divergences", null);
            out.put("reverseCheckTrades", null);
        } else {
            DivergenceViews divViews = divergences(session, analysis.output(), times, index);
            out.put("divergenceSummary", divViews.summary());
            out.put("divergences", divViews.items());
            out.put("reverseCheckTrades", reverseCheckTrades(session, analysis.output(), times, index));
        }
        out.put("roundTrips", roundTrips(report, times));
        out.put("fills", fills(session, replay, index));
        return out;
    }

    /** Итог для истории: {@code total_return} ядра в процентах контракта. */
    static String netReturnPct(ReportData report) {
        return pct(report.metrics().totalReturn());
    }

    private static List<Object> papers(SessionRecord session, ReplayResult replay) {
        List<Object> papers = new ArrayList<>();
        for (PaperRecord paper : session.papers()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", paper.paperId());
            out.put("blindLabel", ViewFormat.label(paper.paperIndex()));
            out.put("ticker", paper.secid());
            // названий в архиве нет: name = тикер, как в GET /papers
            out.put("name", paper.displayName());
            out.put("sector", paper.sector());
            out.put("startPrice", ViewFormat.number(startPrice(session, paper, replay)));
            out.put("lotSize", paper.lotSize());
            papers.add(out);
        }
        return papers;
    }

    /** Цена открытия первого бара сессии (шаг 0) — стартовая цена бумаги. */
    private static BigDecimal startPrice(SessionRecord session, PaperRecord paper, ReplayResult replay) {
        return replay.history().stream()
                .filter(b -> b.paperKey().equals(paper.secid()) && b.step() >= 0)
                .findFirst()
                .map(BarData::open)
                .orElseThrow(() -> new IllegalStateException("Нет баров бумаги в отчёте сессии " + session.id()));
    }

    private static Map<String, Object> metrics(SessionRecord session, ReportData report, boolean bankrupt) {
        MetricsData m = report.metrics();
        Map<String, Object> costs = new LinkedHashMap<>();
        // общую сумму издержек считает core (core#54), backend не складывает
        costs.put("total", ViewFormat.money(report.costs().total()));
        costs.put("commission", ViewFormat.money(report.costs().commission()));
        costs.put("exchangeFee", ViewFormat.money(report.costs().exchangeFee()));
        costs.put("spread", ViewFormat.money(report.costs().spread()));
        costs.put("slippage", ViewFormat.money(report.costs().slippage()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("initialCapital", ViewFormat.money(session.initialCapital()));
        out.put("finalCapital", ViewFormat.money(report.finalEquity()));
        out.put("totalReturnPct", pct(m.totalReturn()));
        out.put("cagrPct", pctOrNull(m.cagr()));
        out.put("grossReturnPct", pct(m.grossReturn()));
        // отдельного «чистого» значения ядро не отдаёт: total_return — после издержек (decisions.md, «Метрики отчёта»)
        out.put("netReturnPct", pct(m.totalReturn()));
        out.put("maxDrawdownPct", pct(m.maxDrawdown()));
        out.put("maxDrawdownDurationDays", m.maxDrawdownDays());
        out.put("maxDrawdownDurationSteps", m.maxDrawdownSteps());
        out.put("volatilityPct", pctOrNull(m.volatility()));
        out.put("sharpe", ratioOrNull(m.sharpe()));
        out.put("sortino", ratioOrNull(m.sortino()));
        out.put("turnover", ratio(m.turnover()));
        out.put("tradesCount", m.roundTrips());
        out.put("winRatePct", pctOrNull(m.winRate()));
        out.put("rejectedOrdersCount", m.rejections());
        out.put("partialFillsCount", m.partialFills());
        out.put("stopTakeSameBarCount", m.stopTakeSameBar());
        out.put("hhi", ratio(m.hhiAvg()));
        out.put("largestPositionPct", pct(m.maxPositionShare()));
        out.put("bankrupt", bankrupt);
        out.put("costs", costs);
        out.put("benchmarkImoexPct", pctOrNull(m.benchmarkReturn()));
        return out;
    }

    private static Map<String, Object> comparison(ReportData human, ReportData algo) {
        BigDecimal hCap = human.finalEquity();
        BigDecimal aCap = algo.finalEquity();
        Map<String, Object> finalCapital = new LinkedHashMap<>();
        finalCapital.put("human", ViewFormat.money(hCap));
        finalCapital.put("algo", ViewFormat.money(aCap));
        finalCapital.put("winner", winner(hCap.compareTo(aCap)));
        finalCapital.put("diff", ViewFormat.money(hCap.subtract(aCap)));

        BigDecimal hRet = pctDecimal(human.metrics().totalReturn());
        BigDecimal aRet = pctDecimal(algo.metrics().totalReturn());
        Map<String, Object> netReturn = new LinkedHashMap<>();
        netReturn.put("humanPct", ViewFormat.number(hRet));
        netReturn.put("algoPct", ViewFormat.number(aRet));
        netReturn.put("winner", winner(hRet.compareTo(aRet)));
        netReturn.put("diffPctPoints", ViewFormat.number(hRet.subtract(aRet)));

        BigDecimal hDd = pctDecimal(human.metrics().maxDrawdown());
        BigDecimal aDd = pctDecimal(algo.metrics().maxDrawdown());
        Map<String, Object> maxDrawdown = new LinkedHashMap<>();
        maxDrawdown.put("humanPct", ViewFormat.number(hDd));
        maxDrawdown.put("algoPct", ViewFormat.number(aDd));
        // для просадки лучше меньшее значение
        maxDrawdown.put("winner", winner(aDd.compareTo(hDd)));
        maxDrawdown.put("diffPctPoints", ViewFormat.number(hDd.subtract(aDd)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("finalCapital", finalCapital);
        out.put("netReturn", netReturn);
        out.put("maxDrawdown", maxDrawdown);
        return out;
    }

    private static String winner(int cmp) {
        return cmp > 0 ? "human" : (cmp < 0 ? "algo" : "tie");
    }

    private static List<Object> equityCurve(ReportData report, @Nullable ReportData algoReport, Map<Long, Long> times) {
        Map<Long, BigDecimal> benchmark = new TreeMap<>();
        for (EquityPointData p : report.benchmarkEquity()) {
            benchmark.put(p.step(), p.equity());
        }
        Map<Long, BigDecimal> algo = new TreeMap<>();
        if (algoReport != null) {
            for (EquityPointData p : algoReport.equity()) {
                algo.put(p.step(), p.equity());
            }
        }
        List<Object> points = new ArrayList<>(report.equity().size());
        for (EquityPointData p : report.equity()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("step", p.step());
            out.put("date", date(times, p.step()));
            out.put("humanCapital", ViewFormat.money(p.equity()));
            BigDecimal algoCap = algo.get(p.step());
            out.put("algoCapital", algoCap == null ? null : ViewFormat.money(algoCap));
            BigDecimal bench = benchmark.get(p.step());
            out.put("benchmarkCapital", bench == null ? null : ViewFormat.money(bench));
            points.add(out);
        }
        return points;
    }

    /** Кривая просадки ядра: точка на каждую точку капитала. */
    private static List<Object> underwaterCurve(
            ReportData report, @Nullable ReportData algoReport, Map<Long, Long> times) {
        Map<Long, BigDecimal> algo = new TreeMap<>();
        if (algoReport != null) {
            for (UnderwaterPointData p : algoReport.underwater()) {
                algo.put(p.step(), p.drawdownPct());
            }
        }
        List<Object> points = new ArrayList<>(report.underwater().size());
        for (UnderwaterPointData p : report.underwater()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("step", p.step());
            out.put("date", date(times, p.step()));
            out.put("humanDrawdownPct", ViewFormat.number(p.drawdownPct()));
            BigDecimal algoDd = algo.get(p.step());
            out.put("algoDrawdownPct", algoDd == null ? null : ViewFormat.number(algoDd));
            points.add(out);
        }
        return points;
    }

    private record DivergenceViews(Map<String, Object> summary, List<Object> items) {}

    private static DivergenceViews divergences(
            SessionRecord session, DivergenceOutput output, Map<Long, Long> times, ViewFormat.StepIndex index) {
        Map<String, String> sectors = new LinkedHashMap<>();
        for (PaperRecord p : session.papers()) {
            sectors.put(p.secid(), p.sector());
        }
        List<Object> items = new ArrayList<>(output.divergences().size());
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalCostPct = BigDecimal.ZERO;
        UUID worstId = null;
        String worstType = null;
        Integer worstDay = null;
        BigDecimal worstCost = null;
        for (DivergenceData d : output.divergences()) {
            UUID id = DerivedIds.divergence(session.id(), d.seq());
            long step = d.humanStep() >= 0 ? d.humanStep() : d.algoStep();
            int day = index.at(step)[0];
            String type = divergenceType(d.type());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("step", step);
            item.put("day", day);
            item.put("date", date(times, step));
            item.put("paperTicker", d.paperKey());
            item.put("sector", sectors.getOrDefault(d.paperKey(), ""));
            item.put("type", type);
            item.put("deltaBars", d.type() == DivergenceKind.TIMING ? d.humanStep() - d.algoStep() : null);
            item.put("horizonStep", d.horizonStep());
            item.put("horizonDay", index.at(d.horizonStep())[0]);
            item.put("humanAction", tradeAction(d.humanLots(), d.humanOrderType()));
            item.put("algoAction", tradeAction(d.algoLots(), d.algoOrderType()));
            item.put("errorCost", ViewFormat.money(d.cost()));
            item.put("errorCostPct", ViewFormat.number(d.costPercent()));
            item.put("because", d.reason().isEmpty() ? null : d.reason());
            items.add(item);

            totalCost = totalCost.add(d.cost());
            totalCostPct = totalCostPct.add(d.costPercent());
            if (d.cost().compareTo(BigDecimal.ZERO) > 0
                    && (worstCost == null || d.cost().compareTo(worstCost) > 0)) {
                worstId = id;
                worstType = type;
                worstDay = day;
                worstCost = d.cost();
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalErrorCost", ViewFormat.money(totalCost));
        summary.put("totalErrorCostPct", ViewFormat.number(totalCostPct));
        summary.put("worstDivergenceId", worstId);
        summary.put("worstType", worstType);
        summary.put("worstDay", worstDay);
        summary.put("worstErrorCost", worstCost == null ? null : ViewFormat.money(worstCost));
        return new DivergenceViews(summary, items);
    }

    private static List<Object> reverseCheckTrades(
            SessionRecord session, DivergenceOutput output, Map<Long, Long> times, ViewFormat.StepIndex index) {
        List<Object> trades = new ArrayList<>(output.missedProfitable().size());
        for (MissedProfitableTradeData m : output.missedProfitable()) {
            if (m.divergenceSeqs().isEmpty()) {
                throw new IllegalStateException(
                        "Сделка обратной проверки без входного расхождения в сессии " + session.id());
            }
            long entryDivSeq = m.divergenceSeqs().getFirst();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", DerivedIds.reverseCheckTrade(session.id(), m.seq()));
            item.put("paperTicker", m.paperKey());
            item.put("entryStep", m.entryStep());
            item.put("entryDay", index.at(m.entryStep())[0]);
            item.put("entryDate", date(times, m.entryStep()));
            item.put("exitStep", m.exitStep());
            item.put("exitDay", index.at(m.exitStep())[0]);
            item.put("exitDate", date(times, m.exitStep()));
            item.put("lots", m.lots());
            item.put("entryPrice", ViewFormat.number(m.entryPrice()));
            item.put("exitPrice", ViewFormat.number(m.exitPrice()));
            item.put("netProfit", ViewFormat.money(m.netPnl()));
            item.put("entryDivergenceId", DerivedIds.divergence(session.id(), entryDivSeq));
            trades.add(item);
        }
        return trades;
    }

    private static String divergenceType(DivergenceKind kind) {
        return switch (kind) {
            case EXCESS -> "extra";
            case MISSED -> "missed";
            case SIZE -> "size";
            case TIMING -> "timing";
        };
    }

    private static Map<String, Object> tradeAction(long signedLots, @Nullable OrderKind orderType) {
        Map<String, Object> action = new LinkedHashMap<>();
        if (signedLots == 0) {
            action.put("kind", "none");
            action.put("side", null);
            action.put("lots", null);
            action.put("orderType", null);
            return action;
        }
        action.put("kind", "trade");
        action.put("side", signedLots > 0 ? "buy" : "sell");
        action.put("lots", Math.abs(signedLots));
        action.put("orderType", orderType == null ? null : ViewFormat.legType(orderType));
        return action;
    }

    private static List<Object> roundTrips(ReportData report, Map<Long, Long> times) {
        List<Object> trips = new ArrayList<>(report.roundTrips().size());
        for (RoundTripData t : report.roundTrips()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("paperTicker", t.paperKey());
            out.put("entryStep", t.entryStep());
            out.put("exitStep", t.exitStep());
            out.put("entryDate", date(times, t.entryStep()));
            out.put("exitDate", date(times, t.exitStep()));
            out.put("lots", t.lots());
            out.put("entryPrice", ViewFormat.number(t.entryPrice()));
            out.put("exitPrice", ViewFormat.number(t.exitPrice()));
            out.put("grossPnl", ViewFormat.money(t.grossPnl()));
            out.put("costs", ViewFormat.money(t.costs()));
            out.put("netPnl", ViewFormat.money(t.netPnl()));
            trips.add(out);
        }
        return trips;
    }

    private static List<Object> fills(SessionRecord session, ReplayResult replay, ViewFormat.StepIndex index) {
        Map<Long, OrderState> orders = new LinkedHashMap<>();
        replay.orders().forEach(o -> orders.put(o.id(), o));
        Map<Long, Long> times = stepTimes(replay);
        List<Object> fills = new ArrayList<>(replay.fills().size());
        for (FillState fill : replay.fills()) {
            OrderState order = orders.get(fill.orderId());
            if (order == null) {
                throw new IllegalStateException("Исполнение без заявки в отчёте сессии " + session.id());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", DerivedIds.fill(session.id(), fill.seq()));
            out.put("day", index.at(fill.step())[0]);
            out.put("date", date(times, fill.step()));
            out.put("paperTicker", fill.paperKey());
            out.put("side", ViewFormat.side(fill.side()));
            out.put("orderType", ViewFormat.legType(order.spec().kind()));
            out.put("lots", fill.lots());
            out.put("price", ViewFormat.number(fill.price()));
            // общие издержки исполнения считает core (core#54)
            out.put("cost", ViewFormat.money(fill.cost()));
            out.put("partial", fill.lots() != order.spec().lots());
            fills.add(out);
        }
        return fills;
    }

    /** Время бара по шагу: у каждого шага шкалы есть бар хотя бы одной бумаги. */
    private static Map<Long, Long> stepTimes(ReplayResult replay) {
        Map<Long, Long> times = new TreeMap<>();
        for (BarData bar : replay.history()) {
            times.putIfAbsent(bar.step(), bar.timeUnixMs());
        }
        return times;
    }

    private static String date(Map<Long, Long> times, long step) {
        Long time = times.get(step);
        if (time == null) {
            throw new IllegalStateException("Нет бара на шаге " + step + " отчёта");
        }
        return ViewFormat.instant(time);
    }

    /**
     * Доля ядра (0,12) в проценте контракта (12.00): сдвиг запятой без округления значения ядра (до шести знаков
     * процента, это точность {@code Pct}), финансового расчёта нет — исключение из «Кто считает», решение владельца
     * продукта.
     */
    static String pct(double fraction) {
        return ViewFormat.number(pctDecimal(fraction));
    }

    private static BigDecimal pctDecimal(double fraction) {
        return BigDecimal.valueOf(finite(fraction)).movePointRight(2).setScale(6, RoundingMode.HALF_UP);
    }

    static @Nullable String pctOrNull(@Nullable Double fraction) {
        return fraction == null ? null : pct(fraction);
    }

    static String ratio(double value) {
        return ViewFormat.number(BigDecimal.valueOf(finite(value)).setScale(6, RoundingMode.HALF_UP));
    }

    /** Ядро обязано отдавать необязательные метрики как {@code optional}; NaN и бесконечность — ошибка ядра. */
    private static double finite(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalStateException("Метрика отчёта ядра не число: " + value);
        }
        return value;
    }

    static @Nullable String ratioOrNull(@Nullable Double value) {
        return value == null ? null : ratio(value);
    }
}
