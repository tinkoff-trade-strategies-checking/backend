package ru.tinvest.trainer.workshop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.tinvest.trainer.PostgresIntegrationTest;

/** INV-5: опубликованная версия стратегии не меняется, только создаются новые. */
class StrategyVersionImmutabilityTest extends PostgresIntegrationTest {

    @Autowired
    private StrategyVersions versions;

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
    void deleteIsRejected() {
        StrategyVersion v = versions.publish(UUID.randomUUID(), "buy()", "{}");

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM workshop.strategy_version WHERE id = :id")
                        .param("id", v.id())
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("INV-5");
        assertThat(versions.find(v.id())).isPresent();
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
}
