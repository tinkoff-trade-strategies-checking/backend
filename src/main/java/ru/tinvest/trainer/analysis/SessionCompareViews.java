package ru.tinvest.trainer.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.tinvest.trainer.sessions.SessionArchive;
import ru.tinvest.trainer.sessions.SessionRecord;
import ru.tinvest.trainer.sessions.ViewFormat;
import ru.tinvest.trainer.worker.WorkerTypes.AlgorithmPortfolioData;
import ru.tinvest.trainer.worker.WorkerTypes.ConsensusItemData;
import ru.tinvest.trainer.worker.WorkerTypes.DivergenceData;
import ru.tinvest.trainer.worker.WorkerTypes.DivergenceOutput;
import ru.tinvest.trainer.worker.WorkerTypes.ReplayResult;
import ru.tinvest.trainer.worker.WorkerTypes.ReportData;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyRunOutput;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyRunStatus;
import ru.tinvest.trainer.workshop.StrategyVersionView;

/**
 * Представление ответа {@code SessionCompareResponse} (UC-9 п. 1а/1б). Повторно использует форматирование и
 * маппинг из {@link ReportViews}; финансовые расчёты не выполняет (INV-6, INV-8).
 */
final class SessionCompareViews {

    private SessionCompareViews() {}

    static Map<String, Object> compare(
            SessionArchive.CompletedReplay completed, List<StrategyVersionView> versions, DivergenceOutput output) {
        SessionRecord session = completed.session();
        ReplayResult replay = completed.replay();
        ReportData humanReport = replay.report();
        if (humanReport == null) {
            throw new IllegalStateException("Воркер не вернул отчёт завершённой сессии " + session.id());
        }
        Map<Long, Long> times = ReportViews.stepTimes(replay);
        ViewFormat.StepIndex index = ReportViews.stepIndex(replay);

        Map<String, StrategyRunOutput> portfolios =
                new LinkedHashMap<>(output.algorithmPortfolios().size());
        for (AlgorithmPortfolioData p : output.algorithmPortfolios()) {
            portfolios.put(p.algorithmId(), p.result());
        }

        Map<String, List<DivergenceData>> divergencesByAlg = new LinkedHashMap<>();
        Map<Long, String> divergenceAlgorithms = new LinkedHashMap<>();
        for (DivergenceData d : output.divergences()) {
            divergencesByAlg
                    .computeIfAbsent(d.algorithmId(), k -> new ArrayList<>())
                    .add(d);
            divergenceAlgorithms.put(d.seq(), d.algorithmId());
        }

        List<Object> algorithms = new ArrayList<>(versions.size());
        for (StrategyVersionView version : versions) {
            String algKey = version.versionId().toString();
            StrategyRunOutput algoRun = portfolios.get(algKey);
            if (algoRun == null || algoRun.report() == null) {
                throw new IllegalStateException("Воркер не вернул отчёт алгоритма " + algKey);
            }
            ReportData algoReport = algoRun.report();
            List<DivergenceData> algDivergences = divergencesByAlg.getOrDefault(algKey, List.of());
            ReportViews.DivergenceViews divViews = ReportViews.divergences(session, algDivergences, times, index);
            List<Object> reverseTrades = ReportViews.reverseCheckTrades(
                    session, output.missedProfitable(), algKey, divergenceAlgorithms, times, index);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("algorithmId", version.strategyId());
            entry.put("strategyVersionId", version.versionId());
            entry.put("algorithmName", version.strategyName());
            entry.put("version", version.version());
            entry.put(
                    "algoMetrics",
                    ReportViews.metrics(session, algoReport, algoRun.status() == StrategyRunStatus.BANKRUPT_STOP));
            entry.put("comparison", ReportViews.comparison(humanReport, algoReport));
            entry.put("equityCurve", ReportViews.equityCurve(humanReport, algoReport, times));
            entry.put("underwaterCurve", ReportViews.underwaterCurve(humanReport, algoReport, times));
            entry.put("divergenceSummary", divViews.summary());
            entry.put("divergences", divViews.items());
            entry.put("reverseCheckTrades", reverseTrades);
            algorithms.add(entry);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", session.id());
        out.put("humanMetrics", ReportViews.metrics(session, humanReport, "bankrupt".equals(session.status())));
        out.put("humanEquityCurve", ReportViews.humanEquityCurve(humanReport, times));
        out.put("algorithms", algorithms);
        out.put("consensus", consensus(output.consensus(), times, index));
        return out;
    }

    private static List<Object> consensus(
            List<ConsensusItemData> items, Map<Long, Long> times, ViewFormat.StepIndex index) {
        List<Object> out = new ArrayList<>(items.size());
        for (ConsensusItemData c : items) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("step", c.step());
            item.put("day", index.at(c.step())[0]);
            item.put("date", ReportViews.date(times, c.step()));
            item.put("paperTicker", c.paperKey());
            item.put("side", ViewFormat.side(c.side()));
            item.put("humanAction", c.humanAction());
            item.put("agreeing", c.agreeing());
            item.put("total", c.total());
            item.put("medianCost", ViewFormat.money(c.medianCost()));
            out.add(item);
        }
        return out;
    }
}
