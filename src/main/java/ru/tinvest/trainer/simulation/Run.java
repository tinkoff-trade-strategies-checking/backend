package ru.tinvest.trainer.simulation;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Прогон: ссылки на неизменяемые версии стратегии и снапшота; {@code repeatOfRunId} задан у повтора (UC-18). */
public record Run(UUID id, UUID strategyVersionId, UUID snapshotId, UUID repeatOfRunId, OffsetDateTime createdAt) {}
