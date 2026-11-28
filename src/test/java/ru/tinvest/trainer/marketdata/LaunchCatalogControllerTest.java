package ru.tinvest.trainer.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LaunchCatalogControllerTest {

    @Test
    void maxPapersReflectsCatalogSizeWithMinimumOfOne() {
        MarketdataProperties props = new MarketdataProperties(
                List.of(),
                25000,
                250000,
                2,
                2,
                false,
                new MarketdataProperties.Execution("0.3", "0.01", "0.1", "0.1", "10"),
                null);

        List<PlannedPaper> empty = List.of();
        LaunchCatalogController emptyController = new LaunchCatalogController(stubPlanner(empty), props);
        assertThat(emptyController.options().minPapers()).isEqualTo(1);
        assertThat(emptyController.options().maxPapers()).isEqualTo(1);
        assertThat(emptyController.options().executionRules().maxBars()).isEqualTo(25000);
        assertThat(emptyController.options().executionRules().runMaxBars()).isEqualTo(250000);
        assertThat(emptyController.options().maxDurationDaysByTimeframe()).containsEntry("1d", 25000);
        assertThat(emptyController.options().runMaxDurationDaysByTimeframe()).containsEntry("1d", 250000);

        List<PlannedPaper> many = new ArrayList<>();
        for (int i = 1; i <= 120; i++) {
            String secid = "TICK" + i;
            many.add(new PlannedPaper(Papers.id(secid), secid, "Финансы", 10, new BigDecimal("0.01"), secid));
        }
        LaunchCatalogController manyController = new LaunchCatalogController(stubPlanner(many), props);
        assertThat(manyController.options().minPapers()).isEqualTo(1);
        assertThat(manyController.options().maxPapers()).isEqualTo(120);
        assertThat(manyController.papers()).isNotEmpty();
        assertThat(manyController.papers().getFirst().assetClass()).isEqualTo("EQUITY");
    }

    private static LaunchPlanner stubPlanner(List<PlannedPaper> papers) {
        return new LaunchPlanner() {
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
                return papers;
            }

            @Override
            public List<Timeframe> availableTimeframes(UUID snapshotId) {
                return List.of(Timeframe.D1);
            }

            @Override
            public Map<Timeframe, Integer> maxDurations(UUID snapshotId) {
                return Map.of(Timeframe.D1, 25000);
            }

            @Override
            public Map<Timeframe, Integer> runMaxDurations(UUID snapshotId) {
                return Map.of(Timeframe.D1, 250000);
            }

            @Override
            public DateRange archiveDateRange(UUID snapshotId) {
                return new DateRange(LocalDate.of(2024, 1, 3), LocalDate.of(2024, 12, 30));
            }
        };
    }
}
