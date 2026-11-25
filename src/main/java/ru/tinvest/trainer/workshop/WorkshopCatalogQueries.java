package ru.tinvest.trainer.workshop;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.tinvest.trainer.api.InvalidRequestException;
import ru.tinvest.trainer.api.NotFoundException;
import ru.tinvest.trainer.identity.CurrentUser;
import ru.tinvest.trainer.worker.WorkerTypes.CheckLevel;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyDiagnostic;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyParamDecl;
import ru.tinvest.trainer.workshop.WorkshopViews.AlgorithmDetailView;
import ru.tinvest.trainer.workshop.WorkshopViews.AlgorithmSummaryView;
import ru.tinvest.trainer.workshop.WorkshopViews.AlgorithmVersionView;
import ru.tinvest.trainer.workshop.WorkshopViews.AuthorOptionView;
import ru.tinvest.trainer.workshop.WorkshopViews.DetailNotFoundView;
import ru.tinvest.trainer.workshop.WorkshopViews.DetailReadyView;
import ru.tinvest.trainer.workshop.WorkshopViews.DetailResultView;
import ru.tinvest.trainer.workshop.WorkshopViews.ForkSourceView;
import ru.tinvest.trainer.workshop.WorkshopViews.LinterWarningView;
import ru.tinvest.trainer.workshop.WorkshopViews.WorkshopPageView;
import tools.jackson.databind.ObjectMapper;

/** Запросы чтения каталога и карточки алгоритма (фильтрация, сортировка, пагинация, изоляция hidden). */
@Service
class WorkshopCatalogQueries {

    private static final int MAX_PAGE_SIZE = 100;

    private static final String SUMMARY_CTE = """
            WITH latest_ver AS (
                SELECT DISTINCT ON (v.strategy_id)
                       v.id,
                       v.strategy_id,
                       v.version,
                       v.lookahead_level,
                       v.diagnostics,
                       v.diagnostics::text AS diagnostics_json,
                       v.published_at
                FROM workshop.strategy_version v
                ORDER BY v.strategy_id, v.version DESC
            ),
            ver_counts AS (
                SELECT v.strategy_id, COUNT(*)::int AS versions_count
                FROM workshop.strategy_version v
                GROUP BY v.strategy_id
            )
            """;

    private static final String SUMMARY_SELECT = """
            SELECT s.id,
                   s.name,
                   s.description,
                   s.owner_id,
                   COALESCE(s.owner_id, :systemAuthor) AS author_id,
                   s.author_name,
                   s.is_system,
                   s.visibility,
                   s.license,
                   COALESCE(lv.version, 0) AS latest_version,
                   lv.id AS latest_version_id,
                   COALESCE(vc.versions_count, 0) AS versions_count,
                   lv.published_at,
                   lv.lookahead_level,
                   COALESCE(lv.diagnostics_json, '[]') AS diagnostics_json,
                   fv.id AS fork_version_id,
                   fv.strategy_id AS fork_strategy_id,
                   fs.name AS fork_strategy_name,
                   fs.author_name AS fork_author_name,
                   fv.version AS fork_version
            """;

    private static final String SUMMARY_FROM = """
            FROM workshop.strategy s
            LEFT JOIN latest_ver lv ON lv.strategy_id = s.id
            LEFT JOIN ver_counts vc ON vc.strategy_id = s.id
            LEFT JOIN workshop.strategy_version fv ON fv.id = s.forked_from_version_id
            LEFT JOIN workshop.strategy fs ON fs.id = fv.strategy_id
            """;

    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final ObjectMapper json;

    WorkshopCatalogQueries(JdbcClient jdbc, CurrentUser currentUser, ObjectMapper json) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.json = json;
    }

    WorkshopPageView list(
            String sort,
            @Nullable UUID authorId,
            boolean mineOnly,
            boolean cleanOnly,
            @Nullable String search,
            int page,
            int pageSize) {
        String orderBy = switch (sort) {
            case "published_desc" -> "COALESCE(lv.published_at, s.updated_at) DESC, s.id DESC";
            case "name_asc" -> "lower(s.name) ASC, s.id ASC";
            case "author_asc" -> "s.is_system DESC, lower(s.author_name) ASC, lower(s.name) ASC, s.id ASC";
            default -> throw new InvalidRequestException("sort must be published_desc, name_asc, or author_asc");
        };
        if (page < 1) {
            throw new InvalidRequestException("page must be at least 1");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }

        UUID viewerId = currentUser.requireId();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("viewerId", viewerId);
        params.put("systemAuthor", StrategyCatalog.SYSTEM_AUTHOR_ID);

        StringBuilder where = new StringBuilder(
                " WHERE s.visibility <> 'deleted' AND (s.visibility IN ('public', 'closed') OR s.owner_id = :viewerId)");
        if (mineOnly) {
            where.append(" AND s.owner_id = :viewerId");
        } else {
            where.append(" AND lv.version IS NOT NULL");
        }
        if (cleanOnly) {
            where.append(" AND lv.version IS NOT NULL AND lv.lookahead_level = 'OK'"
                    + " AND jsonb_array_length(lv.diagnostics) = 0");
        }
        if (authorId != null) {
            where.append(" AND COALESCE(s.owner_id, :systemAuthor) = :authorId");
            params.put("authorId", authorId);
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (strpos(lower(s.name), :search) > 0 OR strpos(lower(s.description), :search) > 0)");
            params.put("search", search.strip().toLowerCase(Locale.ROOT));
        }

        long total = queryLong(SUMMARY_CTE + "SELECT COUNT(*) " + SUMMARY_FROM + where, params);
        long offset = (long) (page - 1) * pageSize;
        String selectItems = SUMMARY_CTE + SUMMARY_SELECT + SUMMARY_FROM + where + " ORDER BY " + orderBy
                + " LIMIT :limit OFFSET :offset";

        Map<String, Object> itemParams = new LinkedHashMap<>(params);
        itemParams.put("limit", pageSize);
        itemParams.put("offset", offset);
        List<AlgorithmSummaryView> items = querySummaries(selectItems, itemParams, viewerId);
        List<AuthorOptionView> authors = loadAuthors(viewerId);
        int totalPages = (int) ((total + pageSize - 1) / pageSize);
        return new WorkshopPageView(items, authors, total, page, pageSize, totalPages);
    }

    AlgorithmSummaryView getSummary(UUID strategyId) {
        UUID viewerId = currentUser.requireId();
        String sql = SUMMARY_CTE + SUMMARY_SELECT + SUMMARY_FROM
                + " WHERE s.id = :id AND s.owner_id = :viewerId AND s.visibility <> 'deleted'";
        List<AlgorithmSummaryView> found = querySummaries(
                sql,
                Map.of("id", strategyId, "viewerId", viewerId, "systemAuthor", StrategyCatalog.SYSTEM_AUTHOR_ID),
                viewerId);
        if (found.isEmpty()) {
            throw new NotFoundException("Algorithm not found");
        }
        return found.getFirst();
    }

    DetailResultView getById(UUID strategyId) {
        UUID viewerId = currentUser.requireId();
        String sql = SUMMARY_CTE + SUMMARY_SELECT + SUMMARY_FROM + """
                 WHERE s.id = :id
                   AND s.visibility <> 'deleted'
                   AND (s.owner_id = :viewerId OR (s.visibility IN ('public', 'closed') AND lv.version IS NOT NULL))
                """;
        List<AlgorithmSummaryView> found = querySummaries(
                sql,
                Map.of("id", strategyId, "viewerId", viewerId, "systemAuthor", StrategyCatalog.SYSTEM_AUTHOR_ID),
                viewerId);
        if (found.isEmpty()) {
            return new DetailNotFoundView("NOT_FOUND");
        }
        AlgorithmSummaryView summary = found.getFirst();
        boolean hideSource = "closed".equals(summary.visibility()) && !summary.isMine();
        List<AlgorithmVersionView> versions = jdbc.sql("""
                        SELECT id,
                               version,
                               published_at,
                               summary,
                               lookahead_level,
                               source,
                               diagnostics::text AS diagnostics_json,
                               param_decls::text AS param_decls_json
                        FROM workshop.strategy_version
                        WHERE strategy_id = :id
                        ORDER BY version DESC
                        """)
                .param("id", strategyId)
                .query((rs, n) -> mapVersion(rs, hideSource))
                .list();

        AlgorithmDetailView detail = new AlgorithmDetailView(
                summary.id(),
                summary.name(),
                summary.description(),
                summary.authorId(),
                summary.author(),
                summary.isMine(),
                summary.isSystem(),
                summary.visibility(),
                summary.latestVersion(),
                summary.latestVersionId(),
                summary.versionsCount(),
                summary.publishedAt(),
                summary.license(),
                summary.lookaheadLevel(),
                summary.hasLinterWarnings(),
                summary.linterWarnings(),
                summary.forkedFrom(),
                versions);
        return new DetailReadyView("READY", detail);
    }

    private long queryLong(String sql, Map<String, Object> params) {
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }
        return spec.query(Long.class).single();
    }

    private List<AlgorithmSummaryView> querySummaries(String sql, Map<String, Object> params, UUID viewerId) {
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }
        return spec.query((rs, n) -> mapSummary(rs, viewerId)).list();
    }

    private List<AuthorOptionView> loadAuthors(UUID viewerId) {
        return jdbc.sql("""
                        SELECT DISTINCT COALESCE(s.owner_id, :systemAuthor) AS author_id,
                               s.author_name,
                               s.is_system,
                               lower(s.author_name) AS author_sort
                        FROM workshop.strategy s
                        WHERE s.visibility <> 'deleted'
                          AND (s.visibility IN ('public', 'closed') OR s.owner_id = :viewerId)
                          AND EXISTS (SELECT 1 FROM workshop.strategy_version v WHERE v.strategy_id = s.id)
                        ORDER BY s.is_system DESC, author_sort ASC, author_id ASC
                        """)
                .param("systemAuthor", StrategyCatalog.SYSTEM_AUTHOR_ID)
                .param("viewerId", viewerId)
                .query((rs, n) ->
                        new AuthorOptionView(rs.getObject("author_id", UUID.class), rs.getString("author_name")))
                .list();
    }

    private AlgorithmSummaryView mapSummary(ResultSet rs, UUID viewerId) throws SQLException {
        UUID ownerId = rs.getObject("owner_id", UUID.class);
        boolean isMine = Objects.equals(ownerId, viewerId);
        OffsetDateTime published = rs.getObject("published_at", OffsetDateTime.class);
        List<StrategyDiagnostic> diagnostics = parseDiagnostics(rs.getString("diagnostics_json"));
        List<LinterWarningView> warnings = toLinterWarnings(diagnostics);
        return new AlgorithmSummaryView(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("description"),
                rs.getObject("author_id", UUID.class),
                rs.getString("author_name"),
                isMine,
                rs.getBoolean("is_system"),
                rs.getString("visibility"),
                rs.getInt("latest_version"),
                rs.getObject("latest_version_id", UUID.class),
                rs.getInt("versions_count"),
                published == null ? null : WorkshopViews.formatTimestamp(published),
                rs.getString("license"),
                rs.getString("lookahead_level"),
                !warnings.isEmpty(),
                warnings,
                mapForkSource(rs));
    }

    private AlgorithmVersionView mapVersion(ResultSet rs, boolean hideSource) throws SQLException {
        return new AlgorithmVersionView(
                rs.getObject("id", UUID.class),
                rs.getInt("version"),
                WorkshopViews.formatTimestamp(rs.getObject("published_at", OffsetDateTime.class)),
                rs.getString("summary"),
                true,
                rs.getString("lookahead_level"),
                hideSource ? null : rs.getString("source"),
                parseDiagnostics(rs.getString("diagnostics_json")),
                parseParams(rs.getString("param_decls_json")));
    }

    private static @Nullable ForkSourceView mapForkSource(ResultSet rs) throws SQLException {
        UUID forkVersionId = rs.getObject("fork_version_id", UUID.class);
        if (forkVersionId == null) {
            return null;
        }
        return new ForkSourceView(
                forkVersionId,
                rs.getObject("fork_strategy_id", UUID.class),
                rs.getString("fork_strategy_name"),
                rs.getString("fork_author_name"),
                rs.getInt("fork_version"));
    }

    private static List<LinterWarningView> toLinterWarnings(List<StrategyDiagnostic> diagnostics) {
        List<LinterWarningView> warnings = new ArrayList<>();
        for (StrategyDiagnostic d : diagnostics) {
            if (d.level() != CheckLevel.ERROR) {
                warnings.add(new LinterWarningView(d.code(), d.startLine(), d.message()));
            }
        }
        return List.copyOf(warnings);
    }

    private List<StrategyDiagnostic> parseDiagnostics(@Nullable String raw) {
        if (raw == null || raw.isBlank() || "[]".equals(raw.strip())) {
            return List.of();
        }
        return List.of(json.readValue(raw, StrategyDiagnostic[].class));
    }

    private List<StrategyParamDecl> parseParams(@Nullable String raw) {
        if (raw == null || raw.isBlank() || "[]".equals(raw.strip())) {
            return List.of();
        }
        return List.of(json.readValue(raw, StrategyParamDecl[].class));
    }
}
