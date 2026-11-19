package ru.tinvest.trainer.analysis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import ru.tinvest.trainer.sessions.DerivedIds;
import ru.tinvest.trainer.sessions.PaperRecord;
import ru.tinvest.trainer.sessions.SessionArchive;
import ru.tinvest.trainer.sessions.SessionRecord;
import ru.tinvest.trainer.sessions.ViewFormat;
import ru.tinvest.trainer.worker.WorkerTypes.BarData;
import ru.tinvest.trainer.worker.WorkerTypes.EquityPointData;
import ru.tinvest.trainer.worker.WorkerTypes.FillState;
import ru.tinvest.trainer.worker.WorkerTypes.MetricsData;
import ru.tinvest.trainer.worker.WorkerTypes.OrderState;
import ru.tinvest.trainer.worker.WorkerTypes.ReplayResult;
import ru.tinvest.trainer.worker.WorkerTypes.ReportData;
import ru.tinvest.trainer.worker.WorkerTypes.RoundTripData;
import ru.tinvest.trainer.worker.WorkerTypes.UnderwaterPointData;

/**
 * Раскрытый отчёт завершённой сессии ({@code SessionReport}). Backend ничего не считает: значения берутся из отчёта
 * ядра; единственное преобразование — доли ядра в проценты контракта (×100, без округления, до шести знаков) и
 * шаг → время бара. Производные величины (общая сумма издержек, издержки исполнений, кривая просадки, длительность
 * просадки в днях) считает core (core#54), они уже ×100 там, где это проценты. Чего нет (алгоритм, сравнение, разбор
 * расхождений — ФТ-18…20), в ответе {@code null}.
 */
final class ReportViews {

    private ReportViews() {}

    /** Раскрытый отчёт ({@code SessionReport}) по журналу завершённой сессии, проигранному воркером. */
    static Map<String, Object> report(SessionArchive.CompletedReplay finished) {
        SessionRecord session = finished.session();
        ReplayResult replay = finished.replay();
        ReportData report = replay.report();
        Map<Long, Long> times = stepTimes(replay);
        ViewFormat.StepIndex index = new ViewFormat.StepIndex();
        replay.history().forEach(b -> index.add(b.step(), b.tradingDay()));
        index.build();

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
        // фоновый алгоритм и всё, что с ним сравнивается, ядро пока не отдаёт
        out.put("backgroundAlgorithmName", null);
        out.put("period", period);
        out.put("papers", papers(session, replay));
        out.put("humanMetrics", metrics(session, report));
        out.put("algoMetrics", null);
        out.put("comparison", null);
        out.put("equityCurve", equityCurve(report, times));
        out.put("underwaterCurve", underwaterCurve(report, times));
        // разбор расхождений и обратная проверка — после решения по DSL: null, а не пустой массив — «не посчитано» не
        // должно читаться как «нет расхождений»
        out.put("divergenceSummary", null);
        out.put("divergences", null);
        out.put("reverseCheckTrades", null);
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

    private static Map<String, Object> metrics(SessionRecord session, ReportData report) {
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
        out.put("bankrupt", "bankrupt".equals(session.status()));
        out.put("costs", costs);
        out.put("benchmarkImoexPct", pctOrNull(m.benchmarkReturn()));
        return out;
    }

    private static List<Object> equityCurve(ReportData report, Map<Long, Long> times) {
        Map<Long, BigDecimal> benchmark = new TreeMap<>();
        for (EquityPointData p : report.benchmarkEquity()) {
            benchmark.put(p.step(), p.equity());
        }
        List<Object> points = new ArrayList<>(report.equity().size());
        for (EquityPointData p : report.equity()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("step", p.step());
            out.put("date", date(times, p.step()));
            out.put("humanCapital", ViewFormat.money(p.equity()));
            out.put("algoCapital", null);
            BigDecimal bench = benchmark.get(p.step());
            out.put("benchmarkCapital", bench == null ? null : ViewFormat.money(bench));
            points.add(out);
        }
        return points;
    }

    /** Кривая просадки ядра: точка на каждую точку капитала; доля фонового алгоритма — {@code null} (ФТ-18…20). */
    private static List<Object> underwaterCurve(ReportData report, Map<Long, Long> times) {
        List<Object> points = new ArrayList<>(report.underwater().size());
        for (UnderwaterPointData p : report.underwater()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("step", p.step());
            out.put("date", date(times, p.step()));
            out.put("humanDrawdownPct", ViewFormat.number(p.drawdownPct()));
            out.put("algoDrawdownPct", null);
            points.add(out);
        }
        return points;
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
        return ViewFormat.number(
                BigDecimal.valueOf(finite(fraction)).movePointRight(2).setScale(6, RoundingMode.HALF_UP));
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
