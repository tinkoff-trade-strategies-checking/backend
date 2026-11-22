package ru.tinvest.trainer.workshop;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.tinvest.trainer.api.InvalidRequestException;
import ru.tinvest.trainer.api.NotFoundException;
import ru.tinvest.trainer.config.Uuid7;
import ru.tinvest.trainer.identity.CurrentUser;
import ru.tinvest.trainer.worker.SimulationWorkerClient;
import ru.tinvest.trainer.worker.WorkerTypes.CheckLevel;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyCheck;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyDiagnostic;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyParamDecl;
import ru.tinvest.trainer.workshop.WorkshopViews.AlgorithmVersionView;
import ru.tinvest.trainer.workshop.WorkshopViews.DraftView;
import ru.tinvest.trainer.workshop.WorkshopViews.ForkSourceView;
import tools.jackson.databind.ObjectMapper;

/** Мутации мастерской: черновики, проверка DSL, публикация версий, форки и скрытие из каталога. */
@Service
class WorkshopService {

    private static final int MAX_NAME_LENGTH = 80;
    private static final int MAX_DESCRIPTION_LENGTH = 2000;
    private static final int MAX_SUMMARY_LENGTH = 500;
    private static final int MAX_SOURCE_LENGTH = 65_536;

    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final SimulationWorkerClient worker;
    private final StrategyVersions versions;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    WorkshopService(
            JdbcClient jdbc,
            CurrentUser currentUser,
            SimulationWorkerClient worker,
            StrategyVersions versions,
            ObjectMapper json,
            TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.worker = worker;
        this.versions = versions;
        this.json = json;
        this.tx = tx;
    }

    DraftView createDraft(
            @Nullable String rawName, @Nullable String rawDescription, @Nullable String rawSource, String authorName) {
        UUID ownerId = currentUser.requireId();
        String name = validateName(rawName);
        String description = validateDescription(rawDescription == null ? "" : rawDescription);
        String source = validateSource(rawSource == null ? "" : rawSource);
        String safeAuthor = normalizeAuthor(authorName);
        UUID strategyId = Uuid7.next();

        try {
            tx.executeWithoutResult(status -> {
                jdbc.sql("""
                                INSERT INTO workshop.strategy (
                                    id, owner_id, author_name, is_system, name, description, visibility, license
                                )
                                VALUES (:id, :owner, :author, FALSE, :name, :desc, 'public', 'MIT')
                                """)
                        .param("id", strategyId)
                        .param("owner", ownerId)
                        .param("author", safeAuthor)
                        .param("name", name)
                        .param("desc", description)
                        .update();
                jdbc.sql("INSERT INTO workshop.strategy_draft (strategy_id, source) VALUES (:id, :src)")
                        .param("id", strategyId)
                        .param("src", source)
                        .update();
            });
        } catch (DuplicateKeyException e) {
            throw WorkshopConflictException.duplicateName();
        }
        return getDraft(strategyId);
    }

    DraftView getDraft(UUID strategyId) {
        UUID ownerId = currentUser.requireId();
        return loadOwnedDraft(strategyId, ownerId);
    }

    DraftView updateDraft(
            UUID strategyId, @Nullable String rawName, @Nullable String rawDescription, @Nullable String rawSource) {
        if (rawName == null && rawDescription == null && rawSource == null) {
            throw new InvalidRequestException("At least one of name, description, or source must be provided");
        }
        UUID ownerId = currentUser.requireId();
        String newName = rawName == null ? null : validateName(rawName);
        String newDescription = rawDescription == null ? null : validateDescription(rawDescription);
        String newSource = rawSource == null ? null : validateSource(rawSource);

        try {
            tx.executeWithoutResult(status -> {
                OwnedStrategy current = requireOwnedForUpdate(strategyId, ownerId);
                String finalName = newName != null ? newName : current.name();
                String finalDescription = newDescription != null ? newDescription : current.description();
                jdbc.sql("""
                                UPDATE workshop.strategy
                                SET name = :name, description = :desc, updated_at = now()
                                WHERE id = :id
                                """)
                        .param("name", finalName)
                        .param("desc", finalDescription)
                        .param("id", strategyId)
                        .update();
                if (newSource != null) {
                    jdbc.sql("""
                                    INSERT INTO workshop.strategy_draft (
                                        strategy_id, source, last_check_level, last_diagnostics, last_params, updated_at
                                    )
                                    VALUES (:id, :src, NULL, '[]'::jsonb, '[]'::jsonb, now())
                                    ON CONFLICT (strategy_id) DO UPDATE
                                    SET source = EXCLUDED.source,
                                        last_check_level = NULL,
                                        last_diagnostics = '[]'::jsonb,
                                        last_params = '[]'::jsonb,
                                        updated_at = now()
                                    """)
                            .param("id", strategyId)
                            .param("src", newSource)
                            .update();
                }
            });
        } catch (DuplicateKeyException e) {
            throw WorkshopConflictException.duplicateName();
        }
        return loadOwnedDraft(strategyId, ownerId);
    }

    StrategyCheck check(@Nullable String source, @Nullable Map<String, Double> paramOverrides) {
        if (source == null) {
            throw new InvalidRequestException("source is required");
        }
        validateSource(source);
        Map<String, Double> overrides = validateOverrides(paramOverrides);
        return worker.checkStrategy(source, overrides);
    }

    AlgorithmVersionView publish(UUID strategyId, @Nullable String rawSummary, @Nullable String rawSource) {
        UUID ownerId = currentUser.requireId();
        String summary = rawSummary == null ? "" : validateSummary(rawSummary);
        String draftSource = loadOwnedDraftSource(strategyId, ownerId);
        String sourceToCheck = rawSource == null ? draftSource : validateSource(rawSource);

        StrategyCheck check = worker.checkStrategy(sourceToCheck, Map.of());
        boolean hasError = check.lookaheadLevel() == CheckLevel.ERROR
                || check.diagnostics().stream().anyMatch(d -> d.level() == CheckLevel.ERROR);
        if (hasError) {
            throw new StrategyInvalidException(check);
        }

        Map<String, Double> defaultParams = new LinkedHashMap<>();
        for (StrategyParamDecl p : check.params()) {
            defaultParams.put(p.name(), p.defaultValue());
        }
        String paramsJson = json.writeValueAsString(defaultParams);
        String diagnosticsJson = json.writeValueAsString(check.diagnostics());
        String paramDeclsJson = json.writeValueAsString(check.params());

        StrategyVersion published = tx.execute(status -> {
            requireOwnedForUpdate(strategyId, ownerId);
            jdbc.sql("""
                            INSERT INTO workshop.strategy_draft (
                                strategy_id, source, last_check_level, last_diagnostics, last_params, updated_at
                            )
                            VALUES (
                                :id, :src, :level, CAST(:diag AS jsonb), CAST(:decls AS jsonb), now()
                            )
                            ON CONFLICT (strategy_id) DO UPDATE
                            SET source = EXCLUDED.source,
                                last_check_level = EXCLUDED.last_check_level,
                                last_diagnostics = EXCLUDED.last_diagnostics,
                                last_params = EXCLUDED.last_params,
                                updated_at = now()
                            """)
                    .param("id", strategyId)
                    .param("src", sourceToCheck)
                    .param("level", check.lookaheadLevel().name())
                    .param("diag", diagnosticsJson)
                    .param("decls", paramDeclsJson)
                    .update();
            jdbc.sql("UPDATE workshop.strategy SET updated_at = now() WHERE id = :id")
                    .param("id", strategyId)
                    .update();
            return versions.publishChecked(
                    strategyId,
                    sourceToCheck,
                    summary,
                    check.lookaheadLevel(),
                    paramsJson,
                    diagnosticsJson,
                    paramDeclsJson);
        });
        if (published == null) {
            throw new IllegalStateException("Published version is null");
        }
        return new AlgorithmVersionView(
                published.id(),
                published.version(),
                WorkshopViews.formatTimestamp(published.publishedAt()),
                summary,
                true,
                check.lookaheadLevel().name(),
                sourceToCheck,
                check.diagnostics(),
                check.params());
    }

    DraftView fork(
            UUID sourceStrategyId,
            @Nullable Integer versionNumber,
            @Nullable String requestedName,
            @Nullable String requestedDescription,
            String authorName) {
        UUID ownerId = currentUser.requireId();
        if (versionNumber != null && versionNumber < 1) {
            throw new InvalidRequestException("version must be positive");
        }
        ForkTarget source = findForkTarget(sourceStrategyId, versionNumber, ownerId);
        String explicitName = requestedName == null ? null : validateName(requestedName);
        String description =
                requestedDescription == null ? source.description() : validateDescription(requestedDescription);
        String safeAuthor = normalizeAuthor(authorName);
        UUID newStrategyId = Uuid7.next();

        try {
            tx.executeWithoutResult(status -> {
                String finalName = explicitName != null ? explicitName : pickUniqueForkName(ownerId, source.name());
                jdbc.sql("""
                                INSERT INTO workshop.strategy (
                                    id, owner_id, author_name, is_system, name, description,
                                    visibility, license, forked_from_version_id
                                )
                                VALUES (
                                    :id, :owner, :author, FALSE, :name, :desc, 'public', 'MIT', :forkVersion
                                )
                                """)
                        .param("id", newStrategyId)
                        .param("owner", ownerId)
                        .param("author", safeAuthor)
                        .param("name", finalName)
                        .param("desc", description)
                        .param("forkVersion", source.versionId())
                        .update();
                jdbc.sql("""
                                INSERT INTO workshop.strategy_draft (
                                    strategy_id, source, last_check_level, last_diagnostics, last_params
                                )
                                VALUES (
                                    :id, :src, :level, CAST(:diag AS jsonb), CAST(:decls AS jsonb)
                                )
                                """)
                        .param("id", newStrategyId)
                        .param("src", source.source())
                        .param("level", source.lookaheadLevel())
                        .param("diag", source.diagnosticsJson())
                        .param("decls", source.paramDeclsJson())
                        .update();
            });
        } catch (DuplicateKeyException e) {
            throw WorkshopConflictException.duplicateName();
        }
        return loadOwnedDraft(newStrategyId, ownerId);
    }

    void changeVisibility(UUID strategyId, @Nullable String visibility) {
        if (!"public".equals(visibility) && !"hidden".equals(visibility)) {
            throw new InvalidRequestException("visibility must be public or hidden");
        }
        UUID ownerId = currentUser.requireId();
        StrategyMeta meta = jdbc.sql("""
                        SELECT id, owner_id, is_system
                        FROM workshop.strategy
                        WHERE id = :id
                        """)
                .param("id", strategyId)
                .query((rs, n) -> new StrategyMeta(
                        rs.getObject("id", UUID.class),
                        rs.getObject("owner_id", UUID.class),
                        rs.getBoolean("is_system")))
                .optional()
                .orElseThrow(() -> new NotFoundException("Algorithm not found"));

        if (meta.isSystem()) {
            throw WorkshopConflictException.systemAlgorithmImmutable();
        }
        if (!Objects.equals(meta.ownerId(), ownerId)) {
            throw new NotFoundException("Algorithm not found");
        }

        jdbc.sql("""
                        UPDATE workshop.strategy
                        SET visibility = :vis, updated_at = now()
                        WHERE id = :id
                        """).param("vis", visibility).param("id", strategyId).update();
    }

    private DraftView loadOwnedDraft(UUID strategyId, UUID ownerId) {
        return jdbc.sql("""
                        SELECT s.id,
                               s.name,
                               s.description,
                               s.visibility,
                               COALESCE(d.source, '') AS source,
                               d.last_check_level,
                               COALESCE(d.last_diagnostics::text, '[]') AS last_diagnostics_json,
                               COALESCE(d.last_params::text, '[]') AS last_params_json,
                               COALESCE(d.updated_at, s.updated_at) AS updated_at,
                               COALESCE((SELECT MAX(v.version)
                                         FROM workshop.strategy_version v
                                         WHERE v.strategy_id = s.id), 0) AS latest_version,
                               fv.id AS fork_version_id,
                               fv.strategy_id AS fork_strategy_id,
                               fs.name AS fork_strategy_name,
                               fs.author_name AS fork_author_name,
                               fv.version AS fork_version
                        FROM workshop.strategy s
                        LEFT JOIN workshop.strategy_draft d ON d.strategy_id = s.id
                        LEFT JOIN workshop.strategy_version fv ON fv.id = s.forked_from_version_id
                        LEFT JOIN workshop.strategy fs ON fs.id = fv.strategy_id
                        WHERE s.id = :id AND s.owner_id = :owner AND NOT s.is_system
                        """)
                .param("id", strategyId)
                .param("owner", ownerId)
                .query((rs, n) -> mapDraft(rs))
                .optional()
                .orElseThrow(() -> new NotFoundException("Algorithm draft not found"));
    }

    private String loadOwnedDraftSource(UUID strategyId, UUID ownerId) {
        return jdbc.sql("""
                        SELECT COALESCE(d.source, '') AS source
                        FROM workshop.strategy s
                        LEFT JOIN workshop.strategy_draft d ON d.strategy_id = s.id
                        WHERE s.id = :id AND s.owner_id = :owner AND NOT s.is_system
                        """)
                .param("id", strategyId)
                .param("owner", ownerId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new NotFoundException("Algorithm not found"));
    }

    private OwnedStrategy requireOwnedForUpdate(UUID strategyId, UUID ownerId) {
        return jdbc.sql("""
                        SELECT name, description
                        FROM workshop.strategy
                        WHERE id = :id AND owner_id = :owner AND NOT is_system
                        FOR UPDATE
                        """)
                .param("id", strategyId)
                .param("owner", ownerId)
                .query((rs, n) -> new OwnedStrategy(rs.getString("name"), rs.getString("description")))
                .optional()
                .orElseThrow(() -> new NotFoundException("Algorithm not found"));
    }

    private ForkTarget findForkTarget(UUID strategyId, @Nullable Integer versionNumber, UUID viewerId) {
        String versionFilter = versionNumber == null ? "" : " AND v.version = :ver";
        String sql = """
                SELECT s.name,
                       s.description,
                       v.id AS version_id,
                       v.source,
                       v.lookahead_level,
                       v.diagnostics::text AS diagnostics_json,
                       v.param_decls::text AS param_decls_json
                FROM workshop.strategy s
                JOIN workshop.strategy_version v ON v.strategy_id = s.id
                WHERE s.id = :id
                  AND (s.is_system OR s.visibility = 'public' OR s.owner_id = :viewerId)
                """ + versionFilter + " ORDER BY v.version DESC LIMIT 1";
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("id", strategyId).param("viewerId", viewerId);
        if (versionNumber != null) {
            spec = spec.param("ver", versionNumber);
        }
        return spec.query((rs, n) -> new ForkTarget(
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getObject("version_id", UUID.class),
                        rs.getString("source"),
                        rs.getString("lookahead_level"),
                        rs.getString("diagnostics_json"),
                        rs.getString("param_decls_json")))
                .optional()
                .orElseThrow(() -> new NotFoundException("Published algorithm version not found"));
    }

    private String pickUniqueForkName(UUID ownerId, String baseName) {
        for (int attempt = 1; attempt <= 1000; attempt++) {
            String suffix = attempt == 1 ? " (копия)" : " (копия " + attempt + ")";
            int maxBase = MAX_NAME_LENGTH - suffix.length();
            String prefix =
                    baseName.length() > maxBase ? baseName.substring(0, maxBase).stripTrailing() : baseName;
            String candidate = prefix + suffix;
            boolean exists = jdbc.sql("""
                            SELECT EXISTS(
                                SELECT 1 FROM workshop.strategy
                                WHERE owner_id = :owner AND lower(name) = lower(:name)
                            )
                            """)
                    .param("owner", ownerId)
                    .param("name", candidate)
                    .query(Boolean.class)
                    .single();
            if (!exists) {
                return candidate;
            }
        }
        throw WorkshopConflictException.duplicateName();
    }

    private DraftView mapDraft(ResultSet rs) throws SQLException {
        UUID forkVersionId = rs.getObject("fork_version_id", UUID.class);
        ForkSourceView forkedFrom = forkVersionId == null
                ? null
                : new ForkSourceView(
                        forkVersionId,
                        rs.getObject("fork_strategy_id", UUID.class),
                        rs.getString("fork_strategy_name"),
                        rs.getString("fork_author_name"),
                        rs.getInt("fork_version"));
        String lastLevel = rs.getString("last_check_level");
        StrategyCheck check = lastLevel == null
                ? null
                : new StrategyCheck(
                        CheckLevel.valueOf(lastLevel),
                        List.of(json.readValue(rs.getString("last_diagnostics_json"), StrategyDiagnostic[].class)),
                        List.of(json.readValue(rs.getString("last_params_json"), StrategyParamDecl[].class)));
        return new DraftView(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("visibility"),
                forkedFrom,
                rs.getString("source"),
                check,
                WorkshopViews.formatTimestamp(rs.getObject("updated_at", OffsetDateTime.class)),
                rs.getInt("latest_version"));
    }

    private static String validateName(@Nullable String raw) {
        if (raw == null) {
            throw new InvalidRequestException("name is required");
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw new InvalidRequestException("name must be between 1 and " + MAX_NAME_LENGTH + " characters");
        }
        return trimmed;
    }

    private static String validateDescription(String raw) {
        if (raw.length() > MAX_DESCRIPTION_LENGTH) {
            throw new InvalidRequestException("description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        return raw;
    }

    private static String validateSummary(String raw) {
        String trimmed = raw.strip();
        if (trimmed.length() > MAX_SUMMARY_LENGTH) {
            throw new InvalidRequestException("summary must be at most " + MAX_SUMMARY_LENGTH + " characters");
        }
        return trimmed;
    }

    private static String validateSource(String raw) {
        if (raw.length() > MAX_SOURCE_LENGTH) {
            throw new InvalidRequestException("source is too long");
        }
        return raw;
    }

    private static String normalizeAuthor(String raw) {
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return "Автор";
        }
        return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
    }

    private static Map<String, Double> validateOverrides(@Nullable Map<String, Double> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : raw.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null || !Double.isFinite(e.getValue())) {
                throw new InvalidRequestException("paramOverrides must have non-blank names and finite numbers");
            }
            out.put(e.getKey(), e.getValue());
        }
        return Map.copyOf(out);
    }

    private record OwnedStrategy(String name, String description) {}

    private record StrategyMeta(UUID id, @Nullable UUID ownerId, boolean isSystem) {}

    private record ForkTarget(
            String name,
            String description,
            UUID versionId,
            String source,
            String lookaheadLevel,
            String diagnosticsJson,
            String paramDeclsJson) {}
}
