package ru.tinvest.trainer.workshop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.tinvest.trainer.PostgresIntegrationTest;
import ru.tinvest.trainer.worker.WorkerTypes.CheckLevel;

/** INV-5: опубликованная версия стратегии не меняется и стратегии не удаляются, только скрываются. */
class StrategyVersionImmutabilityTest extends PostgresIntegrationTest {

    @Autowired
    private StrategyVersions versions;

    @Autowired
    private StrategyCatalog catalog;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void updateOfPublishedVersionIsRejected() {
        StrategyVersion v = versions.publish(UUID.randomUUID(), "buy()", "{}");

        assertThatThrownBy(() -> jdbc.sql("UPDATE workshop.strategy_version SET source = 'sell()' WHERE id = :id")
                        .param("id", v.id())
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("INV-5");
        assertThat(versions.find(v.id()).orElseThrow().source()).isEqualTo("buy()");
    }

    @Test
    void deleteAndTruncateOfVersionAreRejected() {
        StrategyVersion v = versions.publish(UUID.randomUUID(), "buy()", "{}");

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM workshop.strategy_version WHERE id = :id")
                        .param("id", v.id())
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("INV-5");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE workshop.strategy_version").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("INV-5");
        assertThat(versions.find(v.id())).isPresent();
    }

    @Test
    void deleteAndTruncateOfStrategyAreRejected() {
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM workshop.strategy WHERE id = :id")
                        .param("id", StrategyCatalog.SYSTEM_STRATEGY_ID)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("INV-5");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE workshop.strategy CASCADE").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("INV-5");
    }

    @Test
    void newVersionIsCreatedWithNextNumberAndOldStaysIntact() {
        UUID strategy = UUID.randomUUID();
        StrategyVersion first = versions.publish(strategy, "buy()", "{\"a\":1}");
        StrategyVersion second = versions.publish(strategy, "sell()", "{\"a\":2}");

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(first.version()).isEqualTo(1);
        assertThat(second.version()).isEqualTo(2);
        assertThat(versions.find(first.id()).orElseThrow().source()).isEqualTo("buy()");
    }

    @Test
    void systemDefaultStrategyIsSeededInV16() {
        StrategyVersionView system = catalog.systemDefaultVersion();

        assertThat(system.versionId()).isEqualTo(StrategyCatalog.SYSTEM_VERSION_ID);
        assertThat(system.strategyId()).isEqualTo(StrategyCatalog.SYSTEM_STRATEGY_ID);
        assertThat(system.strategyName()).isEqualTo("Эталон платформы");
        assertThat(system.authorName()).isEqualTo("Эталон платформы");
        assertThat(system.isSystem()).isTrue();
        assertThat(system.version()).isEqualTo(1);
        assertThat(system.lookaheadLevel()).isEqualTo(CheckLevel.OK);
        assertThat(system.source()).contains("param fast = 20 range 5..50 step 5");
        assertThat(system.source()).contains("param slow = 50 range 20..200 step 10");
        assertThat(system.paramDeclsJson()).contains("fast", "slow", "rsi_n", "rsi_high", "stop_pct", "size_pct");
        assertThat(catalog.canAccess(system.versionId(), UUID.randomUUID())).isTrue();
    }
}
