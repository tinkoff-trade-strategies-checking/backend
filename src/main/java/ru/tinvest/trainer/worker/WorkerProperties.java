package ru.tinvest.trainer.worker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Настройки подключения к Simulation Worker: адрес {@code host:port} и дедлайн одного вызова. */
@ConfigurationProperties("worker")
record WorkerProperties(
        @DefaultValue("localhost:50051") String address,
        @DefaultValue("5s") Duration deadline) {}
