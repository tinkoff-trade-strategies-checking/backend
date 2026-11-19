package ru.tinvest.trainer.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.tinvest.trainer.PostgresIntegrationTest;

/** INV-5: загруженный снапшот не меняется, только создаются новые. */
class SnapshotImmutabilityTest extends PostgresIntegrationTest {

    @Autowired
    private Snapshots snapshots;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void updateIsRejected() {
        Snapshot s = snapshots.register("snap-" + UUID.randomUUID(), "фикстура");

        assertThatThrownBy(() -> jdbc.sql("UPDATE marketdata.snapshot SET description = 'x' WHERE id = :id")
                        .param("id", s.id())
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("INV-5");
        assertThat(snapshots.find(s.id()).orElseThrow().description()).isEqualTo("фикстура");
    }

    @Test
    void truncateIsRejected() {
        Snapshot s = snapshots.register("snap-" + UUID.randomUUID(), "");

        assertThatThrownBy(() -> jdbc.sql("TRUNCATE marketdata.snapshot").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("INV-5");
        assertThat(snapshots.find(s.id())).isPresent();
    }

    @Test
    void deleteIsRejected() {
        Snapshot s = snapshots.register("snap-" + UUID.randomUUID(), "");

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM marketdata.snapshot WHERE id = :id")
                        .param("id", s.id())
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("INV-5");
        assertThat(snapshots.find(s.id())).isPresent();
    }
}
