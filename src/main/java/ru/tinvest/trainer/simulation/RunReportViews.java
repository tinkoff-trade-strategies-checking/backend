package ru.tinvest.trainer.simulation;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import ru.tinvest.trainer.worker.WorkerTypes.BarData;
import ru.tinvest.trainer.worker.WorkerTypes.EquityPointData;
import ru.tinvest.trainer.worker.WorkerTypes.FillState;
import ru.tinvest.trainer.worker.WorkerTypes.MetricsData;
import ru.tinvest.trainer.worker.WorkerTypes.OrderKind;
import ru.tinvest.trainer.worker.WorkerTypes.OrderState;
import ru.tinvest.trainer.worker.WorkerTypes.ReportData;
import ru.tinvest.trainer.worker.WorkerTypes.RoundTripData;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyFinding;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyRunOutput;
import ru.tinvest.trainer.worker.WorkerTypes.UnderwaterPointData;

/**
 * Построение DTO отчёта {@code AlgoRunReport} и состояния {@code RunInfo} по контракту {@code api/openapi.yaml}.
 * Значения берутся из ответа воркера без пересчёта денег и метрик (INV-6); слепой прогон до завершения не раскрывает
 * период и бумаги (INV-7).
 */
final class RunReportViews {

    private RunReportViews() {}

    record FindingView(String code, String level, String message, long step) {}

    static Map<String, Object> algoRunReport(
            RunRecord run,
            String algoName,
            String status,
            int playedDays,
            @Nullable Boolean repeatMatch,
            StrategyRunOutput output) {
        ReportData report = output.report();
        Map<Long, Long> times = stepTimes(output.history());
        Map<Long, Integer> stepDays = stepDays(output.history());
        String fallbackInstant = RunFormat.instant(run.createdAt().toInstant().toEpochMilli());

        Map<String, Object> period = new LinkedHashMap<>();
        period.put("startDate", run.periodStart().toString());
        period.put("endDate", run.periodEnd().toString());

        List<FindingView> findings = new ArrayList<>();
        if (report != null) {
            for (StrategyFinding f : report.findings()) {
                findings.add(new FindingView(f.code(), f.level().name(), f.message(), f.step()));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.id());
        out.put("strategyVersionId", run.strategyVersionId());
        out.put("algoName", algoName);
        out.put("blind", run.blind());
        out.put("status", status);
        out.put("playedDays", playedDays);
        out.put("totalDays", run.tradingDays());
        out.put("timeframe", run.timeframe().code());
        out.put("regime", run.regime().code());
        out.put("createdAt", fallbackInstant);
        out.put("repeatOfRunId", run.repeatOfRunId());
        out.put("repeatMatch", repeatMatch);
        out.put("level", output.level().name());
        out.put("findings", findings);
        out.put("diagnostics", output.diagnostics());
        out.put("period", period);
        out.put("papers", papers(run, output.history()));
        out.put("metrics", report == null ? null : metrics(run, status, report));
        out.put("equityCurve", report == null ? List.of() : equityCurve(report, times, fallbackInstant));
        out.put("underwaterCurve", report == null ? List.of() : underwaterCurve(report, times, fallbackInstant));
        out.put("roundTrips", report == null ? List.of() : roundTrips(report, times, fallbackInstant));
        out.put("fills", fills(run, output, times, stepDays, fallbackInstant));
        out.put("orders", orders(output.orders()));
        return out;
    }

    static Map<String, Object> failedReport(RunRecord run, String algoName) {
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("startDate", run.periodStart().toString());
        period.put("endDate", run.periodEnd().toString());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.id());
        out.put("strategyVersionId", run.strategyVersionId());
        out.put("algoName", algoName);
        out.put("blind", run.blind());
        out.put("status", "failed");
        out.put("playedDays", run.playedDays() == null ? 0 : run.playedDays());
        out.put("totalDays", run.tradingDays());
        out.put("timeframe", run.timeframe().code());
        out.put("regime", run.regime().code());
        out.put("createdAt", RunFormat.instant(run.createdAt().toInstant().toEpochMilli()));
        out.put("repeatOfRunId", run.repeatOfRunId());
        out.put("repeatMatch", null);
        out.put("level", "ERROR");
        out.put("findings", List.of());
        out.put("diagnostics", List.of());
        out.put("period", period);
        out.put("papers", papers(run, List.of()));
        out.put("metrics", null);
        out.put("equityCurve", List.of());
        out.put("underwaterCurve", List.of());
        out.put("roundTrips", List.of());
        out.put("fills", List.of());
        out.put("orders", List.of());
        return out;
    }

    static List<Map<String, Object>> papers(RunRecord run, List<BarData> history) {
        List<Map<String, Object>> list = new ArrayList<>(run.papers().size());
        for (RunPaperRecord paper : run.papers()) {
            BigDecimal startPrice = history.stream()
                    .filter(b -> b.paperKey().equals(paper.secid()) && b.step() >= 0)
                    .findFirst()
                    .map(BarData::open)
                    .orElse(BigDecimal.ZERO);
            list.add(paperView(paper, RunFormat.number(startPrice)));
        }
        return list;
    }

    static Map<String, Object> paperView(RunPaperRecord paper, String startPrice) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", paper.paperId());
        out.put("blindLabel", RunFormat.label(paper.paperIndex()));
        out.put("ticker", paper.secid());
        out.put("name", paper.displayName());
        out.put("sector", paper.sector());
        out.put("startPrice", startPrice);
        out.put("lotSize", paper.lotSize());
        return out;
    }

    private static Map<String, Object> metrics(RunRecord run, String status, ReportData report) {
        MetricsData m = report.metrics();
        Map<String, Object> costs = new LinkedHashMap<>();
        costs.put("total", RunFormat.money(report.costs().total()));
        costs.put("commission", RunFormat.money(report.costs().commission()));
        costs.put("exchangeFee", RunFormat.money(report.costs().exchangeFee()));
        costs.put("spread", RunFormat.money(report.costs().spread()));
        costs.put("slippage", RunFormat.money(report.costs().slippage()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("initialCapital", RunFormat.money(run.initialCapital()));
        out.put("finalCapital", RunFormat.money(report.finalEquity()));
        out.put("totalReturnPct", RunFormat.pct(m.totalReturn()));
        out.put("cagrPct", RunFormat.pctOrNull(m.cagr()));
        out.put("grossReturnPct", RunFormat.pct(m.grossReturn()));
        out.put("netReturnPct", RunFormat.pct(m.totalReturn()));
        out.put("maxDrawdownPct", RunFormat.pct(m.maxDrawdown()));
        out.put("maxDrawdownDurationDays", m.maxDrawdownDays());
        out.put("maxDrawdownDurationSteps", m.maxDrawdownSteps());
        out.put("volatilityPct", RunFormat.pctOrNull(m.volatility()));
        out.put("sharpe", RunFormat.ratioOrNull(m.sharpe()));
        out.put("sortino", RunFormat.ratioOrNull(m.sortino()));
        out.put("turnover", RunFormat.ratio(m.turnover()));
        out.put("tradesCount", m.roundTrips());
        out.put("winRatePct", RunFormat.pctOrNull(m.winRate()));
        out.put("rejectedOrdersCount", m.rejections());
        out.put("partialFillsCount", m.partialFills());
        out.put("stopTakeSameBarCount", m.stopTakeSameBar());
        out.put("hhi", RunFormat.ratio(m.hhiAvg()));
        out.put("largestPositionPct", RunFormat.pct(m.maxPositionShare()));
        out.put("bankrupt", "bankrupt".equals(status));
        out.put("costs", costs);
        out.put("benchmarkImoexPct", RunFormat.pctOrNull(m.benchmarkReturn()));
        return out;
    }

    private static List<Map<String, Object>> equityCurve(
            ReportData report, Map<Long, Long> times, String fallbackInstant) {
        Map<Long, BigDecimal> benchmark = new TreeMap<>();
        for (EquityPointData p : report.benchmarkEquity()) {
            benchmark.put(p.step(), p.equity());
        }
        List<Map<String, Object>> points = new ArrayList<>(report.equity().size());
        for (EquityPointData p : report.equity()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("step", p.step());
            out.put("date", date(times, p.step(), fallbackInstant));
            out.put("capital", RunFormat.money(p.equity()));
            BigDecimal bench = benchmark.get(p.step());
            out.put("benchmarkCapital", bench == null ? null : RunFormat.money(bench));
            points.add(out);
        }
        return points;
    }

    private static List<Map<String, Object>> underwaterCurve(
            ReportData report, Map<Long, Long> times, String fallbackInstant) {
        List<Map<String, Object>> points = new ArrayList<>(report.underwater().size());
        for (UnderwaterPointData p : report.underwater()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("step", p.step());
            out.put("date", date(times, p.step(), fallbackInstant));
            out.put("drawdownPct", RunFormat.number(p.drawdownPct()));
            points.add(out);
        }
        return points;
    }

    private static List<Map<String, Object>> roundTrips(
            ReportData report, Map<Long, Long> times, String fallbackInstant) {
        List<Map<String, Object>> trips = new ArrayList<>(report.roundTrips().size());
        for (RoundTripData t : report.roundTrips()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("paperTicker", t.paperKey());
            out.put("entryStep", t.entryStep());
            out.put("exitStep", t.exitStep());
            out.put("entryDate", date(times, t.entryStep(), fallbackInstant));
            out.put("exitDate", date(times, t.exitStep(), fallbackInstant));
            out.put("lots", t.lots());
            out.put("entryPrice", RunFormat.number(t.entryPrice()));
            out.put("exitPrice", RunFormat.number(t.exitPrice()));
            out.put("grossPnl", RunFormat.money(t.grossPnl()));
            out.put("costs", RunFormat.money(t.costs()));
            out.put("netPnl", RunFormat.money(t.netPnl()));
            trips.add(out);
        }
        return trips;
    }

    private static List<Map<String, Object>> fills(
            RunRecord run,
            StrategyRunOutput output,
            Map<Long, Long> times,
            Map<Long, Integer> stepDays,
            String fallbackInstant) {
        Map<Long, OrderState> orderById = new LinkedHashMap<>();
        output.orders().forEach(o -> orderById.put(o.id(), o));
        List<Map<String, Object>> fills = new ArrayList<>(output.fills().size());
        for (FillState fill : output.fills()) {
            OrderState order = orderById.get(fill.orderId());
            if (order == null) {
                throw new IllegalStateException("Исполнение без заявки в прогоне " + run.id());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", fillUuid(run.id(), fill.seq()));
            out.put("day", stepDays.getOrDefault(fill.step(), 0));
            out.put("date", date(times, fill.step(), fallbackInstant));
            out.put("paperTicker", fill.paperKey());
            out.put("side", RunFormat.side(fill.side()));
            out.put("orderType", RunFormat.legType(order.spec().kind()));
            out.put("lots", fill.lots());
            out.put("price", RunFormat.number(fill.price()));
            out.put("cost", RunFormat.money(fill.cost()));
            out.put("partial", fill.lots() != order.spec().lots());
            fills.add(out);
        }
        return fills;
    }

    private static List<Map<String, Object>> orders(List<OrderState> orders) {
        List<Map<String, Object>> out = new ArrayList<>(orders.size());
        for (OrderState o : orders) {
            OrderKind kind = o.spec().kind();
            BigDecimal limit =
                    (kind == OrderKind.LIMIT || kind == OrderKind.STOP_LIMIT || kind == OrderKind.TAKE_PROFIT)
                            ? o.spec().limitPrice()
                            : null;
            BigDecimal stop = (kind == OrderKind.STOP || kind == OrderKind.STOP_LIMIT)
                    ? o.spec().stopPrice()
                    : null;
            BigDecimal trailing = kind == OrderKind.TRAILING_STOP ? o.spec().trailingPercent() : null;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", o.id());
            item.put("paperTicker", o.spec().paperKey());
            item.put("side", RunFormat.side(o.spec().side()));
            item.put("orderType", RunFormat.legType(kind));
            item.put("lots", o.spec().lots());
            item.put("status", RunFormat.orderStatus(o.phase()));
            item.put("placedStep", o.placedStep());
            item.put("closedStep", o.closedStep() >= 0 ? o.closedStep() : null);
            item.put("limitPrice", RunFormat.numberOrNull(limit));
            item.put("stopPrice", RunFormat.numberOrNull(stop));
            item.put("trailingPct", RunFormat.numberOrNull(trailing));
            item.put("ruleName", o.ruleName());
            item.put("because", o.because());
            out.add(item);
        }
        return out;
    }

    private static Map<Long, Long> stepTimes(List<BarData> history) {
        Map<Long, Long> times = new TreeMap<>();
        for (BarData bar : history) {
            times.putIfAbsent(bar.step(), bar.timeUnixMs());
        }
        return times;
    }

    private static Map<Long, Integer> stepDays(List<BarData> history) {
        Map<Long, Integer> days = new TreeMap<>();
        for (BarData bar : history) {
            days.putIfAbsent(bar.step(), bar.tradingDay());
        }
        return days;
    }

    private static String date(Map<Long, Long> times, long step, String fallbackInstant) {
        Long time = times.get(step);
        return time == null ? fallbackInstant : RunFormat.instant(time);
    }

    private static UUID fillUuid(UUID runId, long seq) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(ByteBuffer.allocate(16)
                    .putLong(runId.getMostSignificantBits())
                    .putLong(runId.getLeastSignificantBits())
                    .array());
            sha1.update(("fill:" + seq).getBytes(StandardCharsets.UTF_8));
            byte[] hash = sha1.digest();
            hash[6] = (byte) ((hash[6] & 0x0F) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3F) | 0x80);
            ByteBuffer buffer = ByteBuffer.wrap(hash, 0, 16);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 недоступен", e);
        }
    }
}
