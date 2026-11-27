package ru.tinvest.trainer.sessions;

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
import ru.tinvest.trainer.worker.WorkerTypes.Costs;
import ru.tinvest.trainer.worker.WorkerTypes.EstimateResult;
import ru.tinvest.trainer.worker.WorkerTypes.OrderKind;

/** Модульные тесты расчёта {@code maxLots} / {@code presetLots} и инварианта слепого режима (backend#87, INV-6/INV-7). */
class OrderEstimateSizingTest {

    @Test
    void buyLotSizingComputesExactFloorPresetsWithBigDecimal() {
        var sizing = Arena.buyLotSizing(new BigDecimal("100000.00"), new BigDecimal("30120.00"), 10L);
        assertThat(sizing.maxLots()).isEqualTo(33L);
        assertThat(sizing.presetLots()).isNotNull();
        assertThat(sizing.presetLots().pct25()).isEqualTo(8L);
        assertThat(sizing.presetLots().pct50()).isEqualTo(16L);
        assertThat(sizing.presetLots().pct100()).isEqualTo(33L);

        var zeroCash = Arena.buyLotSizing(BigDecimal.ZERO, new BigDecimal("1000.00"), 1L);
        assertThat(zeroCash.maxLots()).isZero();
        assertThat(zeroCash.presetLots()).isEqualTo(new ArenaState.PresetLots(0L, 0L, 0L));
    }

    @Test
    void blindEstimateIncludesMaxLotsAndPresetsWithoutLeakingRublesOrLotSize() {
        SessionRecord blindSession = new SessionRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                true,
                "running",
                60,
                Timeframe.D1,
                Regime.MEDIUM,
                StartMode.RANDOM,
                UUID.randomUUID(),
                "snap-key",
                LocalDate.of(2024, 1, 10),
                LocalDate.of(2024, 4, 10),
                60,
                60,
                0.15,
                0.08,
                0.10,
                0.25,
                new BigDecimal("1000000.00"),
                OffsetDateTime.now(ZoneOffset.UTC),
                10L,
                200,
                new Costs(
                        new BigDecimal("0.3"),
                        new BigDecimal("0.01"),
                        new BigDecimal("0.1"),
                        new BigDecimal("0.1"),
                        new BigDecimal("10")),
                List.of(new PaperRecord(
                        0, UUID.randomUUID(), "SBER", "Финансы", 10L, new BigDecimal("0.01"), "Сбербанк")),
                null,
                Map.of(),
                null,
                false);

        EstimateResult amounts = new EstimateResult(
                true,
                null,
                "",
                new BigDecimal("300.00"),
                new BigDecimal("30000.00"),
                new BigDecimal("90.00"),
                new BigDecimal("3.00"),
                new BigDecimal("30093.00"),
                new BigDecimal("3.00"),
                new BigDecimal("0.01"),
                new BigDecimal("0.00"),
                new BigDecimal("3.01"),
                null);

        ArenaState.EstimateView view = new ArenaState.EstimateView(
                true,
                null,
                null,
                amounts,
                List.of(new ArenaState.EstimateLeg(OrderKind.MARKET, true, null, null, amounts)),
                33L,
                new ArenaState.PresetLots(8L, 16L, 33L));

        Map<String, Object> blindResponse = new BlindMasking().estimate(blindSession, view);
        assertThat(blindResponse)
                .containsEntry("mode", "BLIND")
                .containsEntry("maxLots", 33L)
                .doesNotContainKeys("referencePrice", "amount", "commission", "exchangeFee", "requiredCash", "lotSize");
        @SuppressWarnings("unchecked")
        Map<String, Object> presets = (Map<String, Object>) blindResponse.get("presetLots");
        assertThat(presets)
                .containsEntry("pct25", 8L)
                .containsEntry("pct50", 16L)
                .containsEntry("pct100", 33L);
    }
}
