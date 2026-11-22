package ru.tinvest.trainer.workshop;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyDiagnostic;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyParamDecl;

/** DTO-представления REST API мастерской: отделены от сущностей и таблиц БД. */
final class WorkshopViews {

    private WorkshopViews() {}

    static String formatTimestamp(OffsetDateTime ts) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ts.withOffsetSameInstant(ZoneOffset.UTC));
    }

    record LinterWarningView(String code, int line, String message) {}

    record AuthorOptionView(UUID id, String name) {}

    record ForkSourceView(UUID versionId, UUID strategyId, String strategyName, String authorName, int version) {}

    record AlgorithmSummaryView(
            UUID id,
            String name,
            String description,
            UUID authorId,
            String author,
            boolean isMine,
            boolean isSystem,
            String visibility,
            int latestVersion,
            int versionsCount,
            @Nullable String publishedAt,
            String license,
            @Nullable String lookaheadLevel,
            boolean hasLinterWarnings,
            List<LinterWarningView> linterWarnings,
            @Nullable ForkSourceView forkedFrom) {}

    record AlgorithmVersionView(
            UUID id,
            int version,
            String publishedAt,
            String summary,
            boolean immutable,
            String lookaheadLevel,
            String source,
            List<StrategyDiagnostic> diagnostics,
            List<StrategyParamDecl> params) {}

    record AlgorithmDetailView(
            UUID id,
            String name,
            String description,
            UUID authorId,
            String author,
            boolean isMine,
            boolean isSystem,
            String visibility,
            int latestVersion,
            int versionsCount,
            @Nullable String publishedAt,
            String license,
            @Nullable String lookaheadLevel,
            boolean hasLinterWarnings,
            List<LinterWarningView> linterWarnings,
            @Nullable ForkSourceView forkedFrom,
            List<AlgorithmVersionView> versions) {}

    record WorkshopPageView(
            List<AlgorithmSummaryView> items,
            List<AuthorOptionView> authors,
            long total,
            int page,
            int pageSize,
            int totalPages) {}

    record DraftView(
            UUID strategyId,
            String name,
            String description,
            String visibility,
            @Nullable ForkSourceView forkedFrom,
            String source,
            @Nullable String lastCheckLevel,
            List<StrategyDiagnostic> lastDiagnostics,
            List<StrategyParamDecl> lastParams,
            String updatedAt,
            int latestVersion) {}

    record VisibilityView(UUID id, String visibility, String updatedAt) {}
}
