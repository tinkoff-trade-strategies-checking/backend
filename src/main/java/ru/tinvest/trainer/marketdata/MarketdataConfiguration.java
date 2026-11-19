package ru.tinvest.trainer.marketdata;

import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.tinvest.trainer.worker.SimulationWorkerClient;

/** Подбор запуска создаётся, когда задан {@code marketdata.clickhouse.url} ({@code MARKETDATA_CLICKHOUSE_URL}). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MarketdataProperties.class)
class MarketdataConfiguration {

    @Bean
    @ConditionalOnProperty("marketdata.clickhouse.url")
    ArchiveCatalog archiveCatalog(MarketdataProperties props) {
        MarketdataProperties.ClickHouseProperties c = props.clickhouse();
        HttpClient http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return new ClickHouseArchiveCatalog(http, c.url(), c.database(), c.user(), c.password(), c.timeout());
    }

    @Bean
    @ConditionalOnProperty("marketdata.clickhouse.url")
    LaunchPlanner launchPlanner(
            Snapshots snapshots, ArchiveCatalog archive, SimulationWorkerClient worker, MarketdataProperties props) {
        SecureRandom random = new SecureRandom();
        return new LaunchPlanner(
                snapshots, archive, worker, Set.copyOf(props.pool()), props.maxBars(), random::nextLong);
    }
}
