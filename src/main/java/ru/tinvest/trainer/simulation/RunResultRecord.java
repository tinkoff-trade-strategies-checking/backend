package ru.tinvest.trainer.simulation;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Сохранённый неизменяемый результат прогона из {@code simulation.run_result}. */
public record RunResultRecord(
        UUID runId,
        String reportJson,
        String journalJson,
        String diagnosticsJson,
        @Nullable Boolean repeatMatch,
        OffsetDateTime createdAt) {}
