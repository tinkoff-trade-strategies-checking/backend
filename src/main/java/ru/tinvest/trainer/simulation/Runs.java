package ru.tinvest.trainer.simulation;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.tinvest.trainer.api.NotFoundException;
import ru.tinvest.trainer.config.Uuid7;
import ru.tinvest.trainer.marketdata.Snapshots;
import ru.tinvest.trainer.workshop.StrategyVersions;

/** Публичный API модуля: создание прогона по ID версий и повтор прогона с теми же версиями (UC-18). */
@Service
public class Runs {

    private final JdbcClient jdbc;
    private final StrategyVersions strategyVersions;
    private final Snapshots snapshots;

    Runs(JdbcClient jdbc, StrategyVersions strategyVersions, Snapshots snapshots) {
        this.jdbc = jdbc;
        this.strategyVersions = strategyVersions;
        this.snapshots = snapshots;
    }

    public Run create(UUID strategyVersionId, UUID snapshotId) {
        return insert(strategyVersionId, snapshotId, null);
    }

    /** Новый прогон с теми же версиями стратегии и снапшота, что у исходного. */
    public Run repeat(UUID runId) {
        Run original = find(runId).orElseThrow(() -> new NotFoundException("Прогон не найден"));
        return insert(original.strategyVersionId(), original.snapshotId(), original.id());
    }

    public Optional<Run> find(UUID id) {
        return jdbc.sql("""
                        SELECT id, strategy_version_id, snapshot_id, repeat_of_run_id, created_at
                        FROM simulation.run WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, n) -> new Run(
                        rs.getObject("id", UUID.class),
                        rs.getObject("strategy_version_id", UUID.class),
                        rs.getObject("snapshot_id", UUID.class),
                        rs.getObject("repeat_of_run_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .optional();
    }

    private Run insert(UUID strategyVersionId, UUID snapshotId, UUID repeatOf) {
        if (strategyVersions.find(strategyVersionId).isEmpty()) {
            throw new NotFoundException("Версия стратегии не найдена");
        }
        if (snapshots.find(snapshotId).isEmpty()) {
            throw new NotFoundException("Снапшот не найден");
        }
        UUID id = Uuid7.next();
        jdbc.sql("""
                        INSERT INTO simulation.run (id, strategy_version_id, snapshot_id, repeat_of_run_id)
                        VALUES (:id, :sv, :sn, :r)
                        """)
                .param("id", id)
                .param("sv", strategyVersionId)
                .param("sn", snapshotId)
                .param("r", repeatOf)
                .update();
        return find(id).orElseThrow();
    }
}
