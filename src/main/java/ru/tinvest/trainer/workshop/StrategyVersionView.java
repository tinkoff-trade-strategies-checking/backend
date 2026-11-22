package ru.tinvest.trainer.workshop;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import ru.tinvest.trainer.worker.WorkerTypes.CheckLevel;

/** Полное представление опубликованной версии стратегии для соседних модулей ({@code simulation}, {@code sessions}). */
public record StrategyVersionView(
        UUID versionId,
        UUID strategyId,
        String strategyName,
        String authorName,
        @Nullable UUID ownerId,
        String visibility,
        boolean isSystem,
        int version,
        String source,
        String summary,
        CheckLevel lookaheadLevel,
        String diagnosticsJson,
        String paramDeclsJson,
        OffsetDateTime publishedAt) {}
