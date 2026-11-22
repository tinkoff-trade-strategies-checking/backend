package ru.tinvest.trainer.workshop;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.tinvest.trainer.config.Uuid7;
import ru.tinvest.trainer.worker.WorkerTypes.CheckLevel;

/** Публичный API модуля: публикация новой версии и чтение версии. Изменить версию нельзя (INV-5). */
@Service
public class StrategyVersions {

    private final JdbcClient jdbc;

    StrategyVersions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Создаёт новую версию стратегии с номером на единицу больше последней. */
    @Transactional
    public StrategyVersion publish(UUID strategyId, String source, String paramsJson) {
        ensureParentStrategyExists(strategyId);
        return insertVersion(strategyId, source, "", CheckLevel.OK, paramsJson, "[]", "[]");
    }

    @Transactional
    StrategyVersion publishChecked(
            UUID strategyId,
            String source,
            String summary,
            CheckLevel lookaheadLevel,
            String paramsJson,
            String diagnosticsJson,
            String paramDeclsJson) {
        return insertVersion(strategyId, source, summary, lookaheadLevel, paramsJson, diagnosticsJson, paramDeclsJson);
    }

    public Optional<StrategyVersion> find(UUID id) {
        return jdbc.sql("""
                        SELECT id, strategy_id, version, source, params::text AS params, published_at
                        FROM workshop.strategy_version WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, n) -> new StrategyVersion(
                        rs.getObject("id", UUID.class),
                        rs.getObject("strategy_id", UUID.class),
                        rs.getInt("version"),
                        rs.getString("source"),
                        rs.getString("params"),
                        rs.getObject("published_at", OffsetDateTime.class)))
                .optional();
    }

    private void ensureParentStrategyExists(UUID strategyId) {
        jdbc.sql("""
                        INSERT INTO workshop.strategy (
                            id, owner_id, author_name, is_system, name, description, visibility, license
                        )
                        VALUES (:id, NULL, 'Legacy', FALSE, :name, '', 'hidden', 'MIT')
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param("id", strategyId)
                .param("name", "legacy-" + strategyId)
                .update();
    }

    private StrategyVersion insertVersion(
            UUID strategyId,
            String source,
            String summary,
            CheckLevel lookaheadLevel,
            String paramsJson,
            String diagnosticsJson,
            String paramDeclsJson) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:key))")
                .param("key", strategyId.toString())
                .query()
                .singleRow();
        int next = jdbc.sql(
                        "SELECT COALESCE(MAX(version), 0) + 1 FROM workshop.strategy_version WHERE strategy_id = :s")
                .param("s", strategyId)
                .query(Integer.class)
                .single();
        UUID id = Uuid7.next();
        jdbc.sql("""
                        INSERT INTO workshop.strategy_version (
                            id, strategy_id, version, source, params, summary, lookahead_level, diagnostics, param_decls
                        )
                        VALUES (
                            :id, :s, :v, :src, CAST(:p AS jsonb), :summary, :level,
                            CAST(:diag AS jsonb), CAST(:decls AS jsonb)
                        )
                        """)
                .param("id", id)
                .param("s", strategyId)
                .param("v", next)
                .param("src", source)
                .param("p", paramsJson)
                .param("summary", summary)
                .param("level", lookaheadLevel.name())
                .param("diag", diagnosticsJson)
                .param("decls", paramDeclsJson)
                .update();
        return find(id).orElseThrow();
    }
}
