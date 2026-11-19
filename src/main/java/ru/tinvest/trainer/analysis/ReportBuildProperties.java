package ru.tinvest.trainer.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Фоновая сборка отчётов: число потоков (каждый держит соединение с БД и вызов воркера) и очередь ожидания. */
@ConfigurationProperties("analysis.report-build")
record ReportBuildProperties(
        @DefaultValue("2") int threads, @DefaultValue("100") int queue) {}
