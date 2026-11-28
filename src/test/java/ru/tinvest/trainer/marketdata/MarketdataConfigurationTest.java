package ru.tinvest.trainer.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import ru.tinvest.trainer.worker.PeriodClassification;
import ru.tinvest.trainer.worker.SimulationWorkerClient;

/** Планировщик обязателен: без {@code MARKETDATA_CLICKHOUSE_URL} приложение не стартует. */
class MarketdataConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(MarketdataConfiguration.class)
            .withBean(Snapshots.class, () -> new Snapshots(null))
            .withBean(SimulationWorkerClient.class, () -> new SimulationWorkerClient() {
                @Override
                public String ping() {
                    return "stub";
                }

                @Override
                public PeriodClassification classifyPeriods(
                        String snapshotKey, int windowLength, LocalDate from, LocalDate to, LocalDate startDate) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public ru.tinvest.trainer.worker.WorkerTypes.ReplayResult replaySession(
                        ru.tinvest.trainer.worker.WorkerTypes.Market market,
                        java.util.List<ru.tinvest.trainer.worker.WorkerTypes.JournalRecord> journal,
                        long upToStep,
                        boolean includeHistory,
                        boolean blind) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public ru.tinvest.trainer.worker.WorkerTypes.ReplayResult replayWithReport(
                        ru.tinvest.trainer.worker.WorkerTypes.Market market,
                        java.util.List<ru.tinvest.trainer.worker.WorkerTypes.JournalRecord> journal,
                        long upToStep) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int tradingDaysAt(ru.tinvest.trainer.worker.WorkerTypes.Market market, long upToStep) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public ru.tinvest.trainer.worker.WorkerTypes.EstimateResult estimateOrder(
                        ru.tinvest.trainer.worker.WorkerTypes.Market market,
                        java.util.List<ru.tinvest.trainer.worker.WorkerTypes.JournalRecord> journal,
                        long upToStep,
                        ru.tinvest.trainer.worker.WorkerTypes.OrderParams order) {
                    throw new UnsupportedOperationException();
                }
            });

    @Test
    void startupFailsWithoutClickHouseUrl() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage("Не задан marketdata.clickhouse.url (переменная MARKETDATA_CLICKHOUSE_URL)");
        });
    }

    @Test
    void blankUrlIsTreatedAsMissing() {
        runner.withPropertyValues("marketdata.clickhouse.url=", "marketdata.clickhouse.database=ttsc")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("marketdata.clickhouse.url=   ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void plannerIsCreatedWhenUrlIsSet() {
        runner.withPropertyValues("marketdata.clickhouse.url=http://localhost:1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LaunchPlanner.class);
                    assertThat(context.getBean(MarketdataProperties.class).maxBars())
                            .isEqualTo(25000);
                    assertThat(context.getBean(MarketdataProperties.class).runMaxBars())
                            .isEqualTo(250000);
                });
    }

    @Test
    void withoutWebServerThereIsNoPlannerAndNoUrlIsNeeded() {
        // профили import и create-user: ни планировщика, ни требования к ClickHouse
        new ApplicationContextRunner()
                .withUserConfiguration(MarketdataConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(LaunchPlanner.class);
                    assertThat(context).doesNotHaveBean(ArchiveCatalog.class);
                });
    }
}
