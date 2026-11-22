package ru.tinvest.trainer.workshop;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.tinvest.trainer.worker.WorkerTypes.CheckLevel;

/** Публичный каталог версий стратегий для модулей {@code simulation} и {@code sessions}. */
@Service
public class StrategyCatalog {

    public static final UUID SYSTEM_AUTHOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    public static final UUID SYSTEM_STRATEGY_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    public static final UUID SYSTEM_VERSION_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");

    private final JdbcClient jdbc;

    StrategyCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Ищет опубликованную версию по идентификатору вместе с метаданными стратегии. */
    public Optional<StrategyVersionView> findVersion(UUID versionId) {
        return jdbc.sql("""
                        SELECT v.id AS version_id,
                               v.strategy_id,
                               s.name AS strategy_name,
                               s.author_name,
                               s.owner_id,
                               s.visibility,
                               s.is_system,
                               v.version,
                               v.source,
                               v.summary,
                               v.lookahead_level,
                               v.diagnostics::text AS diagnostics_json,
                               v.param_decls::text AS param_decls_json,
                               v.published_at
                        FROM workshop.strategy_version v
                        JOIN workshop.strategy s ON s.id = v.strategy_id
                        WHERE v.id = :versionId
                        """)
                .param("versionId", versionId)
                .query((rs, n) -> new StrategyVersionView(
                        rs.getObject("version_id", UUID.class),
                        rs.getObject("strategy_id", UUID.class),
                        rs.getString("strategy_name"),
                        rs.getString("author_name"),
                        rs.getObject("owner_id", UUID.class),
                        rs.getString("visibility"),
                        rs.getBoolean("is_system"),
                        rs.getInt("version"),
                        rs.getString("source"),
                        rs.getString("summary"),
                        CheckLevel.valueOf(rs.getString("lookahead_level")),
                        rs.getString("diagnostics_json"),
                        rs.getString("param_decls_json"),
                        rs.getObject("published_at", OffsetDateTime.class)))
                .optional();
    }

    /** Возвращает системную версию «Эталон платформы» v1 (из миграции V16). */
    public StrategyVersionView systemDefaultVersion() {
        return findVersion(SYSTEM_VERSION_ID)
                .orElseThrow(() -> new IllegalStateException("System default strategy version is missing"));
    }

    /** Проверяет доступ пользователя к опубликованной версии: публичная, своя или системная. */
    public boolean canAccess(UUID versionId, UUID userId) {
        return findVersion(versionId)
                .map(v -> v.isSystem() || "public".equals(v.visibility()) || Objects.equals(v.ownerId(), userId))
                .orElse(false);
    }
}
