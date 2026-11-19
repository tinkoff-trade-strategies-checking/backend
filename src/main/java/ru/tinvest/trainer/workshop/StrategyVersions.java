package ru.tinvest.trainer.workshop;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.tinvest.trainer.config.Uuid7;

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
                        INSERT INTO workshop.strategy_version (id, strategy_id, version, source, params)
                        VALUES (:id, :s, :v, :src, CAST(:p AS jsonb))
                        """)
                .param("id", id)
                .param("s", strategyId)
                .param("v", next)
                .param("src", source)
                .param("p", paramsJson)
                .update();
        return find(id).orElseThrow();
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
}
