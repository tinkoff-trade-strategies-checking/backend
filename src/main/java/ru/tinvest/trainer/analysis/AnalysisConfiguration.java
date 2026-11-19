package ru.tinvest.trainer.analysis;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Асинхронность модуля: сборка отчёта по событию завершения идёт в ограниченном пуле, а не в потоке завершения
 * сессии. Очередь не бесконечна; переполнена — сборка пропускается, а {@code GET report} без отчёта запускает её заново.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableConfigurationProperties(ReportBuildProperties.class)
class AnalysisConfiguration {

    @Bean(name = "reportBuildExecutor")
    Executor reportBuildExecutor(ReportBuildProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.threads());
        executor.setMaxPoolSize(properties.threads());
        executor.setQueueCapacity(properties.queue());
        executor.setThreadNamePrefix("report-build-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        return executor;
    }
}
