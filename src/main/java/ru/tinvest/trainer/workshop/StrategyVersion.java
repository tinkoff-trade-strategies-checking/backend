package ru.tinvest.trainer.workshop;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Опубликованная неизменяемая версия стратегии. */
public record StrategyVersion(
        UUID id, UUID strategyId, int version, String source, String params, OffsetDateTime publishedAt) {}
