package ru.tinvest.trainer.analysis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Фоновая сборка отчётов: число потоков (каждый держит соединение с БД и вызов воркера), очередь ожидания и пауза
 * между повторами сборки одной сессии, если она падает.
 */
@ConfigurationProperties("analysis.report-build")
record ReportBuildProperties(
        @DefaultValue("2") int threads,
        @DefaultValue("100") int queue,
        @DefaultValue("1m") Duration retryInterval) {}
