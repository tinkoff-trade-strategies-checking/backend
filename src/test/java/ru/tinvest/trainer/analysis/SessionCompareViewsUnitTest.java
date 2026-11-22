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
import ru.tinvest.trainer.worker.WorkerTypes.ConsensusItemData;
import ru.tinvest.trainer.worker.WorkerTypes.CostTotalsData;
import ru.tinvest.trainer.worker.WorkerTypes.Costs;
import ru.tinvest.trainer.worker.WorkerTypes.DivergenceData;
import ru.tinvest.trainer.worker.WorkerTypes.DivergenceKind;
import ru.tinvest.trainer.worker.WorkerTypes.DivergenceOutput;
import ru.tinvest.trainer.worker.WorkerTypes.EquityPointData;
import ru.tinvest.trainer.worker.WorkerTypes.Market;
import ru.tinvest.trainer.worker.WorkerTypes.MetricsData;
import ru.tinvest.trainer.worker.WorkerTypes.MissedProfitableTradeData;
import ru.tinvest.trainer.worker.WorkerTypes.OrderKind;
import ru.tinvest.trainer.worker.WorkerTypes.Paper;
import ru.tinvest.trainer.worker.WorkerTypes.PortfolioState;
import ru.tinvest.trainer.worker.WorkerTypes.ReplayResult;
import ru.tinvest.trainer.worker.WorkerTypes.ReportData;
import ru.tinvest.trainer.worker.WorkerTypes.RunState;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyRunOutput;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyRunStatus;
import ru.tinvest.trainer.worker.WorkerTypes.TradeSide;
import ru.tinvest.trainer.worker.WorkerTypes.UnderwaterPointData;
import ru.tinvest.trainer.workshop.StrategyCatalog;
import ru.tinvest.trainer.workshop.StrategyVersionView;
import tools.jackson.databind.ObjectMapper;

/** Unit-тесты {@link SessionCompareViews} без БД и Docker: разметка по алгоритмам, порядок, UUID v5 и консенсус. */
class SessionCompareViewsUnitTest {

    @Test
    void resolveAlgoParamsClampsSizePctToDeclaredRange() {
        ObjectMapper json = new ObjectMapper();
        String withoutSizePct =
                "[{\"name\":\"fast\",\"defaultValue\":20.0,\"rangeMin\":5.0,\"rangeMax\":50.0,\"step\":\"5\",\"integer\":true}]";
        String unboundedSizePct =
                "[{\"name\":\"size_pct\",\"defaultValue\":95.0,\"rangeMin\":null,\"rangeMax\":null,\"step\":null,\"integer\":false}]";
        String rangedSizePct =
                "[{\"name\":\"size_pct\",\"defaultValue\":50.0,\"rangeMin\":40.0,\"rangeMax\":80.0,\"step\":null,\"integer\":false}]";

        assertThat(SessionCompareService.resolveAlgoParams(json, withoutSizePct, 31.0))
                .isEmpty();
        assertThat(SessionCompareService.resolveAlgoParams(json, unboundedSizePct, 31.0))
                .containsEntry("size_pct", 31.0);
        assertThat(SessionCompareService.resolveAlgoParams(json, rangedSizePct, 31.0))
                .containsEntry("size_pct", 40.0);
        assertThat(SessionCompareService.resolveAlgoParams(json, rangedSizePct, 95.0))
                .containsEntry("size_pct", 80.0);
        assertThat(SessionCompareService.resolveAlgoParams(json, rangedSizePct, 55.0))
                .containsEntry("size_pct", 55.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsPerAlgorithmViewsAndConsensusPreservingRequestOrder() {
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
                        0.05, 0.06, null, 0.01, 1L, null, null, null, 0.5, 2L, 1L, 1.0, 0L, 0L, 1.0, 0.5, null, 2, 0L,
                        1L),
                new CostTotalsData(
                        new BigDecimal("10.00"),
                        new BigDecimal("1.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("11.00")),
                List.of(
                        new EquityPointData(0L, new BigDecimal("1000000.00")),
                        new EquityPointData(1L, new BigDecimal("1005000.00"))),
                new BigDecimal("1005000.00"),
                List.of(
                        new EquityPointData(0L, new BigDecimal("1000000.00")),
                        new EquityPointData(1L, new BigDecimal("1001000.00"))),
                List.of(),
                List.of(new UnderwaterPointData(0L, BigDecimal.ZERO), new UnderwaterPointData(1L, BigDecimal.ZERO)),
                List.of(),
                CheckLevel.OK);
        PortfolioState portfolio = new PortfolioState(
                new BigDecimal("1005000.00"),
                new BigDecimal("1005000.00"),
                List.of(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("100.50"),
                new BigDecimal("100.50"),
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

        UUID stratA = UUID.randomUUID();
        UUID verA = UUID.randomUUID();
        UUID stratB = StrategyCatalog.SYSTEM_STRATEGY_ID;
        UUID verB = StrategyCatalog.SYSTEM_VERSION_ID;

        List<StrategyVersionView> versions = List.of(
                new StrategyVersionView(
                        verA,
                        stratA,
                        "Моя стратегия",
                        "Автор",
                        session.userId(),
                        "public",
                        false,
                        2,
                        "rule \"a\" when true then buy 1 lots",
                        "",
                        CheckLevel.OK,
                        "[]",
                        "[]",
                        OffsetDateTime.now(ZoneOffset.UTC)),
                new StrategyVersionView(
                        verB,
                        stratB,
                        "Эталон платформы",
                        "Система",
                        StrategyCatalog.SYSTEM_AUTHOR_ID,
                        "public",
                        true,
                        1,
                        "strategy s {}",
                        "",
                        CheckLevel.OK,
                        "[]",
                        "[]",
                        OffsetDateTime.now(ZoneOffset.UTC)));

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
        DivergenceOutput output = new DivergenceOutput(
                List.of(
                        new DivergenceData(
                                verA.toString(),
                                "SBER",
                                DivergenceKind.EXCESS,
                                0L,
                                -1L,
                                1L,
                                0L,
                                1L,
                                new BigDecimal("100.00"),
                                new BigDecimal("0.01"),
                                "алгоритм здесь не действовал бы",
                                0L,
                                OrderKind.MARKET,
                                null),
                        new DivergenceData(
                                verB.toString(),
                                "SBER",
                                DivergenceKind.EXCESS,
                                0L,
                                -1L,
                                1L,
                                0L,
                                1L,
                                new BigDecimal("120.00"),
                                new BigDecimal("0.02"),
                                "алгоритм здесь не действовал бы",
                                1L,
                                OrderKind.MARKET,
                                null)),
                List.of(new MissedProfitableTradeData(
                        "SBER",
                        0L,
                        1L,
                        1L,
                        new BigDecimal("400.00"),
                        List.of(verA.toString(), verB.toString()),
                        new BigDecimal("270.00"),
                        new BigDecimal("275.00"),
                        0L,
                        List.of(0L, 1L))),
                List.of(new ConsensusItemData("SBER", 0L, TradeSide.BUY, true, 0, 2, new BigDecimal("110.00"))),
                List.of(
                        new AlgorithmPortfolioData(verA.toString(), algoRun),
                        new AlgorithmPortfolioData(verB.toString(), algoRun)));

        Map<String, Object> response = SessionCompareViews.compare(
                new SessionArchive.CompletedReplay(session, replay, 2, market, List.of()), versions, output);

        assertThat(response.get("sessionId")).isEqualTo(session.id());
        List<Map<String, Object>> algos = (List<Map<String, Object>>) response.get("algorithms");
        assertThat(algos).hasSize(2);
        assertThat(algos.get(0).get("algorithmId")).isEqualTo(stratA);
        assertThat(algos.get(1).get("algorithmId")).isEqualTo(stratB);

        List<Map<String, Object>> revA =
                (List<Map<String, Object>>) algos.get(0).get("reverseCheckTrades");
        List<Map<String, Object>> revB =
                (List<Map<String, Object>>) algos.get(1).get("reverseCheckTrades");
        assertThat(revA.getFirst().get("entryDivergenceId")).isEqualTo(DerivedIds.divergence(session.id(), 0L));
        assertThat(revB.getFirst().get("entryDivergenceId")).isEqualTo(DerivedIds.divergence(session.id(), 1L));

        List<Map<String, Object>> consensus = (List<Map<String, Object>>) response.get("consensus");
        assertThat(consensus).hasSize(1);
        assertThat(consensus.getFirst().get("day")).isEqualTo(1);
        assertThat(consensus.getFirst().get("paperTicker")).isEqualTo("SBER");
        assertThat(consensus.getFirst().get("side")).isEqualTo("buy");
        assertThat(consensus.getFirst().get("humanAction")).isEqualTo(true);
        assertThat(consensus.getFirst().get("agreeing")).isEqualTo(0);
        assertThat(consensus.getFirst().get("total")).isEqualTo(2);
        assertThat(consensus.getFirst().get("medianCost")).isEqualTo("110.00");
    }
}
