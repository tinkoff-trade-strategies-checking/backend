package ru.tinvest.trainer.marketdata.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.tinvest.trainer.marketdata.Snapshots;

/** Профиль {@code import} поднимается, свойства биндятся, без профиля импортёра нет. */
class ImportProfileSmokeTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ImportConfiguration.class)
            .withBean(Snapshots.class, () -> mock(Snapshots.class))
            .withBean(DataSource.class, () -> mock(DataSource.class));

    @Test
    void importProfileBindsPropertiesAndCreatesImporter() {
        runner.withPropertyValues(
                        "spring.profiles.active=import",
                        "IMPORT_CLICKHOUSE_URL=http://ch:8123",
                        "IMPORT_CLICKHOUSE_DATABASE=ttsc",
                        "IMPORT_CLICKHOUSE_USER=u",
                        "IMPORT_CLICKHOUSE_PASSWORD=p",
                        "import.from=2024-01-01",
                        "import.to=2024-12-31",
                        "import.tickers=SBER,GAZP")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(ArchiveImporter.class);
                    ImportProperties props = ctx.getBean(ImportProperties.class);
                    assertThat(props.from()).isEqualTo(LocalDate.of(2024, 1, 1));
                    assertThat(props.tickers()).isEqualTo(List.of("SBER", "GAZP"));
                    assertThat(props.clickhouse().url()).isEqualTo("http://ch:8123");
                    assertThat(props.iss().baseUrl()).isEqualTo("https://iss.moex.com/iss");
                    assertThat(ctx.getEnvironment().getProperty("spring.main.web-application-type"))
                            .isEqualTo("none");
                });
    }

    @Test
    void importProfileWithoutClickHouseSettingsFailsInsteadOfUsingDefaults() {
        runner.withPropertyValues("spring.profiles.active=import")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void withoutProfileImporterIsAbsent() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(ArchiveImporter.class));
    }
}
