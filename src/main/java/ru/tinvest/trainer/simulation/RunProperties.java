package ru.tinvest.trainer.simulation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Настройки фоновой очереди прогонов алгоритмов и потока SSE ({@code lab.simulation}). */
@ConfigurationProperties("lab.simulation")
record RunProperties(
        @DefaultValue("2") int threads,
        @DefaultValue("100") int queueCapacity,
        @DefaultValue("3") int maxRunningPerUser,
        @DefaultValue("20") int maxQueuedPerUser,
        @DefaultValue("250ms") Duration progressInterval,
        @DefaultValue("2s") Duration heartbeat,
        @DefaultValue("24h") Duration eventRetention,
        @DefaultValue("256") int maxConcurrentSends) {

    RunProperties {
        if (threads < 1
                || queueCapacity < 1
                || maxRunningPerUser < 1
                || maxQueuedPerUser < 1
                || progressInterval.isNegative()
                || heartbeat.isZero()
                || heartbeat.isNegative()
                || eventRetention.isNegative()
                || maxConcurrentSends < 1) {
            throw new IllegalArgumentException("lab.simulation: некорректные параметры");
        }
    }
}
