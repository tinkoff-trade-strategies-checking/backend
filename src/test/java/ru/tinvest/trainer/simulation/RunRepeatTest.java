package ru.tinvest.trainer.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
    void runWithUnknownVersionIsRejected() {
        Snapshot snap = snapshots.register("snap-" + UUID.randomUUID(), "");

        assertThatThrownBy(() -> runs.create(UUID.randomUUID(), snap.id())).isInstanceOf(NotFoundException.class);
    }
}
