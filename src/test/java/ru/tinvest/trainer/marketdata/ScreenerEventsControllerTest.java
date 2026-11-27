package ru.tinvest.trainer.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScreenerEventsControllerTest {

    @Test
    void screenerLeftJoinsActiveCatalogWithFundamentals() {
        List<PlannedPaper> catalogPapers = List.of(
                new PlannedPaper(Papers.id("SBER"), "SBER", "Финансы", 10, new BigDecimal("0.01"), "Сбербанк"),
                new PlannedPaper(Papers.id("NEWCO"), "NEWCO", "Технологии", 1, new BigDecimal("0.10"), ""));

        SecurityFundamental sberFundamentals = new SecurityFundamental(
                "SBER",
                LocalDate.of(2025, 12, 31),
                LocalDate.of(2026, 2, 26),
                "FY",
                "RUB",
                new BigDecimal("7150000000000.00"),
                new BigDecimal("4.2500"),
                new BigDecimal("0.9200"),
                new BigDecimal("2.1000"),
                null,
                new BigDecimal("23.5000"),
                new BigDecimal("2.8500"),
                new BigDecimal("58.2000"),
                new BigDecimal("46.1000"),
                new BigDecimal("38.5000"),
                new BigDecimal("11.8000"),
                new BigDecimal("6.4000"),
                null,
                null,
                new BigDecimal("74.5000"),
                new BigDecimal("344.0000"),
                new BigDecimal("11.8000"),
                new BigDecimal("50.0000"),
                new BigDecimal("48.0000"),
                new BigDecimal("1.0400"));

        SecurityFundamentals stubFundamentals = new SecurityFundamentals(null) {
            @Override
            public Map<String, SecurityFundamental> findAllLatest() {
                return Map.of("SBER", sberFundamentals);
            }
        };

        LaunchPlanner stubPlanner = new LaunchPlanner() {
            @Override
            public LaunchResult plan(LaunchRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LaunchResult plan(LaunchRequest request, UUID snapshotId, long seed) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<PlannedPaper> papers(UUID snapshotId) {
                return catalogPapers;
            }

            @Override
            public List<Timeframe> availableTimeframes(UUID snapshotId) {
                return List.of(Timeframe.D1);
            }

            @Override
            public Map<Timeframe, Integer> maxDurations(UUID snapshotId) {
                return Map.of(Timeframe.D1, 5000);
            }
        };

        ScreenerEventsController controller = new ScreenerEventsController(stubPlanner, stubFundamentals, null);
        List<ScreenerPaper> result = controller.listScreenerPapers();

        assertThat(result).hasSize(2);

        ScreenerPaper sber = result.get(0);
        assertThat(sber.ticker()).isEqualTo("SBER");
        assertThat(sber.name()).isEqualTo("Сбербанк");
        assertThat(sber.sector()).isEqualTo("Финансы");
        assertThat(sber.hasFundamentals()).isTrue();
        assertThat(sber.marketCapRub()).isEqualTo("7150000000000.00");
        assertThat(sber.peRatio()).isEqualTo("4.25");
        assertThat(sber.roe()).isEqualTo("23.50");
        assertThat(sber.evEbitda()).isNull();

        ScreenerPaper newco = result.get(1);
        assertThat(newco.ticker()).isEqualTo("NEWCO");
        assertThat(newco.name()).isEqualTo("NEWCO");
        assertThat(newco.sector()).isEqualTo("Технологии");
        assertThat(newco.hasFundamentals()).isFalse();
        assertThat(newco.marketCapRub()).isNull();
        assertThat(newco.peRatio()).isNull();
    }
}
