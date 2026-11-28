package ru.tinvest.trainer.marketdata;

import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.tinvest.trainer.worker.SimulationWorkerClient;

/**
 * Подбор запуска обязателен: без {@code marketdata.clickhouse.url} ({@code MARKETDATA_CLICKHOUSE_URL}) приложение не
 * стартует. Без веб-сервера (профили {@code import}, {@code create-user}) подбор не создаётся, как и всё, что
 * его использует ({@code LaunchService}, контроллеры): они в той же условной цепочке.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(MarketdataProperties.class)
class MarketdataConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MarketdataConfiguration.class);

    @Bean
    ArchiveCatalog archiveCatalog(MarketdataProperties props) {
        MarketdataProperties.ClickHouseProperties c = props.requiredClickhouse();
        HttpClient http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return new CachingArchiveCatalog(
                new ClickHouseArchiveCatalog(http, c.url(), c.database(), c.user(), c.password(), c.timeout()),
                props.cacheSnapshots(),
                props.maxConcurrentScans());
    }

    @Bean
    LaunchPlanner launchPlanner(
            Snapshots snapshots, ArchiveCatalog archive, SimulationWorkerClient worker, MarketdataProperties props) {
        SecureRandom random = new SecureRandom();
        return new DefaultLaunchPlanner(
                snapshots,
                archive,
                worker,
                Set.copyOf(props.pool()),
                props.maxBars(),
                props.runMaxBars(),
                random::nextLong);
    }

    /**
     * Прогрев кэша последнего готового снапшота в фоне после старта: первый скан свечей тяжёлый, пользователь не
     * должен его ждать. Сбой (архива ещё нет, ClickHouse недоступен) не мешает работе, скан повторится по запросу.
     */
    @Bean
    ApplicationListener<ApplicationReadyEvent> catalogWarmUp(LaunchPlanner planner, MarketdataProperties props) {
        return event -> {
            if (!props.warmUp()) {
                return;
            }
            Thread.ofVirtual().name("marketdata-warm-up").start(() -> {
                try {
                    planner.warmUp();
                    log.info("Кэш каталога архива прогрет");
                } catch (RuntimeException e) {
                    log.warn("Прогрев кэша каталога архива не удался: {}", e.toString());
                }
            });
        };
    }
}
