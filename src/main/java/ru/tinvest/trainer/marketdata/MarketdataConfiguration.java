package ru.tinvest.trainer.marketdata;

import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import ru.tinvest.trainer.worker.SimulationWorkerClient;

/**
 * Подбор запуска обязателен: без {@code marketdata.clickhouse.url} ({@code MARKETDATA_CLICKHOUSE_URL}) приложение не
 * стартует. Профиль {@code import} (без веба, импорт архива) подбор не использует.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!import")
@EnableConfigurationProperties(MarketdataProperties.class)
class MarketdataConfiguration {

    @Bean
    ArchiveCatalog archiveCatalog(MarketdataProperties props) {
        MarketdataProperties.ClickHouseProperties c = props.requiredClickhouse();
        HttpClient http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return new CachingArchiveCatalog(
                new ClickHouseArchiveCatalog(http, c.url(), c.database(), c.user(), c.password(), c.timeout()));
    }

    @Bean
    LaunchPlanner launchPlanner(
            Snapshots snapshots, ArchiveCatalog archive, SimulationWorkerClient worker, MarketdataProperties props) {
        SecureRandom random = new SecureRandom();
        return new LaunchPlanner(
                snapshots, archive, worker, Set.copyOf(props.pool()), props.maxBars(), random::nextLong);
    }
}
