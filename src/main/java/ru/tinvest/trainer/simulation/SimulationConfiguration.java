package ru.tinvest.trainer.simulation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Конфигурация модуля {@code simulation}. */
@Configuration
@EnableConfigurationProperties(RunProperties.class)
class SimulationConfiguration {}
