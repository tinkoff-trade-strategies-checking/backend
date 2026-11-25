package ru.tinvest.trainer.worker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Настройки подключения к Simulation Worker: адрес {@code host:port}, дедлайны вызовов и лимит сообщения. */
@ConfigurationProperties("worker")
record WorkerProperties(
        @DefaultValue("localhost:50051") String address,
        @DefaultValue("30s") Duration deadline,
        @DefaultValue("10m") Duration runDeadline,
        @DefaultValue("268435456") int maxInboundMessageSize) {}
