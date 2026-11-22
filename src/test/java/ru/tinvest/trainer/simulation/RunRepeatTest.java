package ru.tinvest.trainer.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.tinvest.trainer.PostgresIntegrationTest;
import ru.tinvest.trainer.api.NotFoundException;
import ru.tinvest.trainer.marketdata.Snapshot;
import ru.tinvest.trainer.marketdata.Snapshots;
import ru.tinvest.trainer.workshop.StrategyVersion;
import ru.tinvest.trainer.workshop.StrategyVersions;

/** INV-5: прогон привязан к неизменяемым ID версий; повтор (UC-18) использует те же версии. */
class RunRepeatTest extends PostgresIntegrationTest {

    @Autowired
    private Runs runs;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private StrategyVersions versions;

    @Autowired
    private Snapshots snapshots;

    @Test
    void repeatUsesSameVersions() {
        UUID strategy = UUID.randomUUID();
        StrategyVersion v1 = versions.publish(strategy, "buy()", "{}");
        Snapshot snap = snapshots.register("snap-" + UUID.randomUUID(), "");
        Run original = runs.create(v1.id(), snap.id());

        // после публикации новой версии и нового снапшота повтор всё равно читает прежние
        versions.publish(strategy, "sell()", "{}");
        snapshots.register("snap-" + UUID.randomUUID(), "новый");
        Run repeated = runs.repeat(original.id());

        assertThat(repeated.id()).isNotEqualTo(original.id());
        assertThat(repeated.strategyVersionId()).isEqualTo(v1.id());
        assertThat(repeated.snapshotId()).isEqualTo(snap.id());
        assertThat(repeated.repeatOfRunId()).isEqualTo(original.id());
    }

    @Test
    void runReferencesCannotBeChanged() {
        StrategyVersion v = versions.publish(UUID.randomUUID(), "buy()", "{}");
        Snapshot snap = snapshots.register("snap-" + UUID.randomUUID(), "");
        Run run = runs.create(v.id(), snap.id());

        assertThatThrownBy(() -> jdbc.sql("UPDATE simulation.run SET snapshot_id = :n WHERE id = :id")
                        .param("n", UUID.randomUUID())
                        .param("id", run.id())
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("INV-5");
        assertThatThrownBy(() -> jdbc.sql("UPDATE simulation.run SET strategy_version_id = :n WHERE id = :id")
                        .param("n", UUID.randomUUID())
                        .param("id", run.id())
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("INV-5");
        assertThatThrownBy(() -> jdbc.sql("UPDATE simulation.run SET repeat_of_run_id = :n WHERE id = :id")
                        .param("n", run.id())
                        .param("id", run.id())
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("INV-5");
        assertThat(runs.find(run.id()).orElseThrow().snapshotId()).isEqualTo(snap.id());
    }

    @Test
    void runWithUnknownVersionIsRejected() {
        Snapshot snap = snapshots.register("snap-" + UUID.randomUUID(), "");

        assertThatThrownBy(() -> runs.create(UUID.randomUUID(), snap.id())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteAndFinishedUpdateAreBlockedByTriggers() {
        StrategyVersion v = versions.publish(UUID.randomUUID(), "buy()", "{}");
        Snapshot snap = snapshots.register("snap-" + UUID.randomUUID(), "");
        Run run = runs.create(v.id(), snap.id());

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM simulation.run WHERE id = :id")
                        .param("id", run.id())
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("INV-5");

        jdbc.sql("UPDATE simulation.run SET status = 'completed', finished_at = now() WHERE id = :id")
                .param("id", run.id())
                .update();

        assertThatThrownBy(() -> jdbc.sql("UPDATE simulation.run SET status = 'running' WHERE id = :id")
                        .param("id", run.id())
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("INV-5");
    }
}
