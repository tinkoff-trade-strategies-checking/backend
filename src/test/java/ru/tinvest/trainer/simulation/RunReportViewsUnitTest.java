package ru.tinvest.trainer.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.tinvest.trainer.marketdata.Regime;
import ru.tinvest.trainer.marketdata.StartMode;
import ru.tinvest.trainer.marketdata.Timeframe;
import ru.tinvest.trainer.worker.WorkerTypes.BarData;
import ru.tinvest.trainer.worker.WorkerTypes.CheckLevel;
import ru.tinvest.trainer.worker.WorkerTypes.CostTotalsData;
import ru.tinvest.trainer.worker.WorkerTypes.Costs;
import ru.tinvest.trainer.worker.WorkerTypes.EquityPointData;
import ru.tinvest.trainer.worker.WorkerTypes.FillState;
import ru.tinvest.trainer.worker.WorkerTypes.MetricsData;
import ru.tinvest.trainer.worker.WorkerTypes.OrderKind;
import ru.tinvest.trainer.worker.WorkerTypes.OrderParams;
import ru.tinvest.trainer.worker.WorkerTypes.OrderPhase;
import ru.tinvest.trainer.worker.WorkerTypes.OrderState;
import ru.tinvest.trainer.worker.WorkerTypes.ReportData;
import ru.tinvest.trainer.worker.WorkerTypes.RoundTripData;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyDiagnostic;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyFinding;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyRunOutput;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyRunStatus;
import ru.tinvest.trainer.worker.WorkerTypes.Tif;
import ru.tinvest.trainer.worker.WorkerTypes.TradeSide;
import ru.tinvest.trainer.worker.WorkerTypes.UnderwaterPointData;

/** Unit-тесты построения {@code AlgoRunReport} без БД и Docker. */
class RunReportViewsUnitTest {

    @Test
    void buildsCompletedAlgoRunReportWithOrdersFindingsAndRepeatMatch() {
        RunRecord run = sampleRun("completed", false);
        ReportData report = new ReportData(
                new MetricsData(
                        0.15, 0.16, 0.25, 0.05, 2L, 0.12, 1.4, 2.1, 0.3, 1L, 1L, 1.0, 0L, 0L, 0.4, 0.5, 0.08, 3, 0L,
                        1L),
                new CostTotalsData(
                        new BigDecimal("30.00"),
                        new BigDecimal("1.00"),
                        new BigDecimal("10.00"),
                        new BigDecimal("5.00"),
                        new BigDecimal("46.00")),
                List.of(
                        new EquityPointData(0L, new BigDecimal("1000000.00")),
                        new EquityPointData(1L, new BigDecimal("1150000.00"))),
                new BigDecimal("1150000.00"),
                List.of(
                        new EquityPointData(0L, new BigDecimal("1000000.00")),
                        new EquityPointData(1L, new BigDecimal("1080000.00"))),
                List.of(new RoundTripData(
                        "SBER",
                        0L,
                        1L,
                        10L,
                        new BigDecimal("250.00"),
                        new BigDecimal("265.00"),
                        new BigDecimal("1500.00"),
                        new BigDecimal("46.00"),
                        new BigDecimal("1454.00"),
                        1L)),
                List.of(
                        new UnderwaterPointData(0L, BigDecimal.ZERO),
                        new UnderwaterPointData(1L, new BigDecimal("2.50"))),
                List.of(new StrategyFinding("W-RT01", CheckLevel.WARNING, "Высокая доля отклонённых заявок", 1L)),
                CheckLevel.WARNING);

        OrderState order = new OrderState(
                10L,
                new OrderParams("SBER", TradeSide.BUY, OrderKind.MARKET, 10L, null, null, null, Tif.DAY, false),
                0L,
                OrderPhase.FILLED,
                null,
                "",
                10L,
                -1L,
                null,
                0L,
                "вход",
                "SMA(20) > SMA(50)");
        FillState fill = new FillState(
                10L,
                0L,
                "SBER",
                TradeSide.BUY,
                10L,
                new BigDecimal("250.00"),
                new BigDecimal("2500.00"),
                new BigDecimal("7.50"),
                new BigDecimal("0.25"),
                new BigDecimal("2.50"),
                new BigDecimal("1.25"),
                false,
                1L,
                new BigDecimal("11.50"),
                new BigDecimal("0.01"),
                new BigDecimal("0.25"));
        List<BarData> history = List.of(
                new BarData(
                        0L,
                        "SBER",
                        1_700_000_000_000L,
                        1,
                        new BigDecimal("250.00"),
                        new BigDecimal("255"),
                        new BigDecimal("248"),
                        new BigDecimal("252"),
                        1000,
                        false),
                new BarData(
                        1L,
                        "SBER",
                        1_700_086_400_000L,
                        2,
                        new BigDecimal("252.00"),
                        new BigDecimal("268"),
                        new BigDecimal("251"),
                        new BigDecimal("265"),
                        1200,
                        false));

        StrategyRunOutput output = new StrategyRunOutput(
                StrategyRunStatus.COMPLETED,
                CheckLevel.WARNING,
                List.of(),
                report,
                List.of(fill),
                List.of(order),
                List.of(),
                42L,
                1L,
                2L,
                history);

        Map<String, Object> view = RunReportViews.algoRunReport(run, "Эталон платформы", "completed", 3, true, output);

        assertThat(view.get("status")).isEqualTo("completed");
        assertThat(view.get("level")).isEqualTo("WARNING");
        assertThat(view.get("repeatMatch")).isEqualTo(true);
        assertThat(view.get("algoName")).isEqualTo("Эталон платформы");
        @SuppressWarnings("unchecked")
        List<RunReportViews.FindingView> findings = (List<RunReportViews.FindingView>) view.get("findings");
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).code()).isEqualTo("W-RT01");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orders = (List<Map<String, Object>>) view.get("orders");
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).get("ruleName")).isEqualTo("вход");
        assertThat(orders.get(0).get("because")).isEqualTo("SMA(20) > SMA(50)");
    }

    @Test
    void buildsInvalidRunReportWhenWorkerOmitsMetrics() {
        RunRecord run = sampleRun("invalid", true);
        StrategyRunOutput output = new StrategyRunOutput(
                StrategyRunStatus.INVALID,
                CheckLevel.ERROR,
                List.of(new StrategyDiagnostic(
                        "E-RT01", CheckLevel.ERROR, 4, 1, 4, 15, "Ошибка в рантайме", List.of())),
                null,
                List.of(),
                List.of(),
                List.of(),
                0L,
                -1L,
                10L,
                List.of());

        Map<String, Object> view = RunReportViews.algoRunReport(run, "Моя стратегия", "invalid", 0, null, output);

        assertThat(view.get("status")).isEqualTo("invalid");
        assertThat(view.get("level")).isEqualTo("ERROR");
        assertThat(view.get("metrics")).isNull();
        assertThat(view.get("equityCurve")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        List<Object> diags = (List<Object>) view.get("diagnostics");
        assertThat(diags).hasSize(1);
    }

    private static RunRecord sampleRun(String status, boolean blind) {
        return new RunRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "snap-1",
                UUID.randomUUID(),
                blind,
                status,
                20,
                Timeframe.D1,
                Regime.CALM,
                StartMode.CUSTOM,
                LocalDate.of(2024, 1, 10),
                LocalDate.of(2024, 2, 10),
                20,
                20,
                0.15,
                0.05,
                0.12,
                0.28,
                new BigDecimal("1000000.00"),
                200,
                new Costs(
                        new BigDecimal("0.003"),
                        new BigDecimal("0.0001"),
                        new BigDecimal("0.001"),
                        new BigDecimal("0.1"),
                        new BigDecimal("0.1")),
                Map.of(),
                0L,
                2L,
                1L,
                3,
                42L,
                CheckLevel.OK,
                "15.000000",
                new BigDecimal("1150000.00"),
                null,
                OffsetDateTime.of(2024, 3, 1, 10, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2024, 3, 1, 10, 0, 5, 0, ZoneOffset.UTC),
                List.of(new RunPaperRecord(
                        0, UUID.randomUUID(), "SBER", "Финансы", 10L, new BigDecimal("0.01"), "Сбербанк")));
    }
}
