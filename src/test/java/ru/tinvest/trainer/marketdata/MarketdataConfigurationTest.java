package ru.tinvest.trainer.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.tinvest.trainer.worker.PeriodClassification;
import ru.tinvest.trainer.worker.SimulationWorkerClient;

/** Планировщик обязателен: без {@code MARKETDATA_CLICKHOUSE_URL} приложение не стартует. */
class MarketdataConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
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
                            .isEqualTo(5000);
                });
    }

    @Test
    void importProfileDoesNotNeedPlanner() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("import"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(LaunchPlanner.class);
                });
    }
}
