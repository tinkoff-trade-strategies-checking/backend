package ru.tinvest.trainer.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.tinvest.trainer.marketdata.Papers;
import ru.tinvest.trainer.marketdata.Regime;
import ru.tinvest.trainer.marketdata.StartMode;
import ru.tinvest.trainer.marketdata.Timeframe;
import ru.tinvest.trainer.sessions.DerivedIds;
import ru.tinvest.trainer.sessions.PaperRecord;
import ru.tinvest.trainer.sessions.SessionArchive;
import ru.tinvest.trainer.sessions.SessionRecord;
import ru.tinvest.trainer.worker.WorkerTypes.AlgorithmPortfolioData;
import ru.tinvest.trainer.worker.WorkerTypes.BarData;
import ru.tinvest.trainer.worker.WorkerTypes.BarInterval;
import ru.tinvest.trainer.worker.WorkerTypes.CheckLevel;
import ru.tinvest.trainer.worker.WorkerTypes.CostTotalsData;
import ru.tinvest.trainer.worker.WorkerTypes.Costs;
import ru.tinvest.trainer.worker.WorkerTypes.DivergenceOutput;
import ru.tinvest.trainer.worker.WorkerTypes.EquityPointData;
import ru.tinvest.trainer.worker.WorkerTypes.Market;
import ru.tinvest.trainer.worker.WorkerTypes.MetricsData;
import ru.tinvest.trainer.worker.WorkerTypes.MissedProfitableTradeData;
import ru.tinvest.trainer.worker.WorkerTypes.Paper;
import ru.tinvest.trainer.worker.WorkerTypes.PortfolioState;
import ru.tinvest.trainer.worker.WorkerTypes.ReplayResult;
import ru.tinvest.trainer.worker.WorkerTypes.ReportData;
import ru.tinvest.trainer.worker.WorkerTypes.RunState;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyRunOutput;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyRunStatus;
import ru.tinvest.trainer.worker.WorkerTypes.UnderwaterPointData;

/** Unit-тесты построения {@link ReportViews} для граничных случаев без БД и Docker. */
class ReportViewsUnitTest {

    @Test
    void startPriceFallsBackToZeroWhenPaperHasNoBarAtOrAfterStepZero() {
        List<PaperRecord> papers = List.of(
                new PaperRecord(0, Papers.id("SBER"), "SBER", "Финансы", 10L, new BigDecimal("0.01"), "Сбербанк"),
                new PaperRecord(1, Papers.id("GAZP"), "GAZP", "Энергетика", 10L, new BigDecimal("0.01"), "Газпром"));
        Costs costs = new Costs(
                new BigDecimal("0.003"),
                new BigDecimal("0.0001"),
                new BigDecimal("0.001"),
                new BigDecimal("0.1"),
                new BigDecimal("0.1"));
        SessionRecord session = new SessionRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                false,
                "finished_early",
                20,
                Timeframe.M5,
                Regime.CALM,
                StartMode.CUSTOM,
                UUID.randomUUID(),
                "snap-1",
                LocalDate.of(2024, 1, 10),
                LocalDate.of(2024, 2, 9),
                20,
                100,
                0.15,
                0.05,
                0.12,
                0.28,
                new BigDecimal("1000000.00"),
                OffsetDateTime.of(2024, 2, 10, 10, 0, 0, 0, ZoneOffset.UTC),
                0L,
                200,
                costs,
                papers);

        ReportData reportData = new ReportData(
                new MetricsData(
                        0.0, 0.0, null, 0.0, 0L, null, null, null, 0.0, 0L, 0L, null, 0L, 0L, 0.0, 0.0, null, 1, 0L,
                        0L),
                new CostTotalsData(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                List.of(new EquityPointData(0L, new BigDecimal("1000000.00"))),
                new BigDecimal("1000000.00"),
                List.of(new EquityPointData(0L, new BigDecimal("1000000.00"))),
                List.of(),
                List.of(new UnderwaterPointData(0L, BigDecimal.ZERO)),
                List.of(),
                CheckLevel.OK);
        PortfolioState portfolio = new PortfolioState(
                new BigDecimal("1000000.00"),
                new BigDecimal("1000000.00"),
                List.of(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO);
        // У GAZP при досрочном завершении на step = 0 есть только бар прогрева (step = -1)
        List<BarData> history = List.of(
                new BarData(
                        -1L,
                        "GAZP",
                        1_704_869_700_000L,
                        0,
                        new BigDecimal("160.00"),
                        new BigDecimal("161.00"),
                        new BigDecimal("159.00"),
                        new BigDecimal("160.50"),
                        500L,
                        true),
                new BarData(
                        0L,
                        "SBER",
                        1_704_870_000_000L,
                        1,
                        new BigDecimal("270.00"),
                        new BigDecimal("272.00"),
                        new BigDecimal("269.00"),
                        new BigDecimal("271.00"),
                        1000L,
                        false));
        ReplayResult replay = new ReplayResult(
                100L,
                RunState.RUNNING,
                0L,
                history,
                portfolio,
                List.of(),
                List.of(),
                List.of(),
                200L,
                null,
                reportData,
                1,
                List.of());
        Market market = new Market(
                "snap-1",
                List.of(
                        new Paper("SBER", "SBER", 10L, new BigDecimal("0.01")),
                        new Paper("GAZP", "GAZP", 10L, new BigDecimal("0.01"))),
                BarInterval.M5,
                session.periodStart(),
                session.periodEnd(),
                costs,
                session.initialCapital(),
                "IMOEX",
                200);

        Map<String, Object> view =
                ReportViews.report(new SessionArchive.CompletedReplay(session, replay, 1, market, List.of()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> paperViews = (List<Map<String, Object>>) view.get("papers");
        assertThat(paperViews).hasSize(2);
        assertThat(paperViews.get(0).get("ticker")).isEqualTo("SBER");
        assertThat(paperViews.get(0).get("startPrice")).isEqualTo("270.00");
        assertThat(paperViews.get(1).get("ticker")).isEqualTo("GAZP");
        assertThat(paperViews.get(1).get("startPrice")).isEqualTo("0.00");
    }

    @Test
    void reverseCheckTradesSkipsEntriesWithoutDivergenceSeqs() {
        List<PaperRecord> papers = List.of(
                new PaperRecord(0, Papers.id("SBER"), "SBER", "Финансы", 10L, new BigDecimal("0.01"), "Сбербанк"));
        Costs costs = new Costs(
                new BigDecimal("0.003"),
                new BigDecimal("0.0001"),
                new BigDecimal("0.001"),
                new BigDecimal("0.1"),
                new BigDecimal("0.1"));
        SessionRecord session = new SessionRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                false,
                "completed",
                20,
                Timeframe.D1,
                Regime.CALM,
                StartMode.CUSTOM,
                UUID.randomUUID(),
                "snap-1",
                LocalDate.of(2024, 1, 10),
                LocalDate.of(2024, 2, 9),
                20,
                20,
                0.15,
                0.05,
                0.12,
                0.28,
                new BigDecimal("1000000.00"),
                OffsetDateTime.of(2024, 2, 10, 10, 0, 0, 0, ZoneOffset.UTC),
                1L,
                200,
                costs,
                papers);
        ReportData reportData = new ReportData(
                new MetricsData(
                        0.0, 0.0, null, 0.0, 0L, null, null, null, 0.0, 0L, 0L, null, 0L, 0L, 0.0, 0.0, null, 1, 0L,
                        0L),
                new CostTotalsData(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                List.of(
                        new EquityPointData(0L, new BigDecimal("1000000.00")),
                        new EquityPointData(1L, new BigDecimal("1000000.00"))),
                new BigDecimal("1000000.00"),
                List.of(
                        new EquityPointData(0L, new BigDecimal("1000000.00")),
                        new EquityPointData(1L, new BigDecimal("1000000.00"))),
                List.of(),
                List.of(new UnderwaterPointData(0L, BigDecimal.ZERO), new UnderwaterPointData(1L, BigDecimal.ZERO)),
                List.of(),
                CheckLevel.OK);
        PortfolioState portfolio = new PortfolioState(
                new BigDecimal("1000000.00"),
                new BigDecimal("1000000.00"),
                List.of(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO);
        List<BarData> history = List.of(
                new BarData(
                        0L,
                        "SBER",
                        1_704_870_000_000L,
                        1,
                        new BigDecimal("270.00"),
                        new BigDecimal("272.00"),
                        new BigDecimal("269.00"),
                        new BigDecimal("271.00"),
                        1000L,
                        false),
                new BarData(
                        1L,
                        "SBER",
                        1_704_956_400_000L,
                        2,
                        new BigDecimal("271.00"),
                        new BigDecimal("276.00"),
                        new BigDecimal("270.00"),
                        new BigDecimal("275.00"),
                        1100L,
                        false));
        ReplayResult replay = new ReplayResult(
                20L,
                RunState.FINISHED_END,
                1L,
                history,
                portfolio,
                List.of(),
                List.of(),
                List.of(),
                200L,
                null,
                reportData,
                2,
                List.of());
        Market market = new Market(
                "snap-1",
                List.of(new Paper("SBER", "SBER", 10L, new BigDecimal("0.01"))),
                BarInterval.D1,
                session.periodStart(),
                session.periodEnd(),
                costs,
                session.initialCapital(),
                "IMOEX",
                200);
        StrategyRunOutput algoRun = new StrategyRunOutput(
                StrategyRunStatus.COMPLETED,
                CheckLevel.OK,
                List.of(),
                reportData,
                List.of(),
                List.of(),
                List.of(),
                42L,
                1L,
                2L,
                history);
        DivergenceOutput divOutput = new DivergenceOutput(
                List.of(),
                List.of(
                        new MissedProfitableTradeData(
                                "SBER",
                                0L,
                                1L,
                                1L,
                                new BigDecimal("400.00"),
                                List.of("algo-1"),
                                new BigDecimal("270.00"),
                                new BigDecimal("275.00"),
                                1L,
                                List.of()),
                        new MissedProfitableTradeData(
                                "SBER",
                                0L,
                                1L,
                                1L,
                                new BigDecimal("400.00"),
                                List.of("algo-1"),
                                new BigDecimal("270.00"),
                                new BigDecimal("275.00"),
                                2L,
                                List.of(7L))),
                List.of(),
                List.of(new AlgorithmPortfolioData("algo-1", algoRun)));

        Map<String, Object> view = ReportViews.report(
                new SessionArchive.CompletedReplay(session, replay, 2, market, List.of()),
                new ReportViews.BackgroundAnalysis("Эталон", divOutput));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reverse = (List<Map<String, Object>>) view.get("reverseCheckTrades");
        assertThat(reverse).hasSize(1);
        assertThat(reverse.getFirst().get("id")).isEqualTo(DerivedIds.reverseCheckTrade(session.id(), 2L));
        assertThat(reverse.getFirst().get("entryDivergenceId")).isEqualTo(DerivedIds.divergence(session.id(), 7L));
    }
}
