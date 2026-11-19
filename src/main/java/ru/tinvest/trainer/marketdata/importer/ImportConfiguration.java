package ru.tinvest.trainer.marketdata.importer;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import ru.tinvest.trainer.marketdata.Snapshots;

/** Импортёр архива — только в профиле {@code import}: {@code --spring.profiles.active=import}. */
@Configuration
@Profile("import")
@EnableConfigurationProperties(ImportProperties.class)
class ImportConfiguration {

    @Bean
    ArchiveImporter archiveImporter(ImportProperties props, Snapshots snapshots, DataSource dataSource) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        ImportProperties.Iss i = props.iss();
        HttpFetcher fetcher =
                new HttpFetcher(http, i.pause(), i.maxRetries(), i.initialBackoff(), i.maxBackoff(), Sleeper.REAL);
        ImportProperties.ClickHouseProperties c = props.clickhouse();
        ClickHouse ch = new ClickHouse(http, c.url(), c.database(), c.user(), c.password());
        return new ArchiveImporter(
                new IssClient(fetcher, i.baseUrl(), i.emptyRetries(), IssClient.DEFAULT_CANDLES_PAGE),
                new CbrRuonia(fetcher, props.cbr().url(), i.emptyRetries()),
                new ClickHouseArchive(ch),
                snapshots,
                Clock.systemUTC(),
                SnapshotLocks.postgres(dataSource));
    }

    @Bean
    ApplicationRunner importRunner(ImportProperties props, ArchiveImporter importer) {
        return args -> {
            if (props.from() == null || props.to() == null) {
                throw new ImportException("Задайте --import.from и --import.to (yyyy-MM-dd)");
            }
            importer.run(new ArchiveImporter.Params(
                    props.from(), props.to(), props.tickers(), props.label(), props.allowGaps()));
        };
    }
}
