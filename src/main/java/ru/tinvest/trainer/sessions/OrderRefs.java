package ru.tinvest.trainer.sessions;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.tinvest.trainer.config.Uuid7;

/**
 * Соответствие номера заявки в ядре ({@code seq * 2 + нога}) и UUID v7 из контракта ({@code sessions.order_ref}).
 * Создаётся при первом показе заявки и не меняется; состояния заявки не хранит — оно в журнале (INV-8).
 */
@Component
class OrderRefs {

    private final JdbcClient jdbc;

    OrderRefs(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** UUID для каждого номера из {@code coreIds}; недостающие создаются. */
    Map<Long, UUID> ensure(UUID sessionId, Collection<Long> coreIds) {
        Map<Long, UUID> result = new HashMap<>();
        if (coreIds.isEmpty()) {
            return result;
        }
        load(sessionId, coreIds, result);
        for (Long coreId : coreIds) {
            if (!result.containsKey(coreId)) {
                jdbc.sql("INSERT INTO sessions.order_ref (session_id, core_order_id, id) VALUES (:s, :c, :id) "
                                + "ON CONFLICT DO NOTHING")
                        .param("s", sessionId)
                        .param("c", coreId)
                        .param("id", Uuid7.next())
                        .update();
            }
        }
        if (result.size() < coreIds.size()) {
            load(sessionId, coreIds, result);
        }
        return result;
    }

    private void load(UUID sessionId, Collection<Long> coreIds, Map<Long, UUID> into) {
        jdbc.sql("SELECT core_order_id, id FROM sessions.order_ref WHERE session_id = :s "
                        + "AND core_order_id IN (:ids)")
                .param("s", sessionId)
                .param("ids", coreIds)
                .query((rs, n) -> into.put(rs.getLong("core_order_id"), rs.getObject("id", UUID.class)))
                .list();
    }

    /** Номер заявки в ядре по UUID; чужой и неизвестный UUID неразличимы. */
    Optional<Long> coreId(UUID sessionId, UUID orderId) {
        return jdbc.sql("SELECT core_order_id FROM sessions.order_ref WHERE session_id = :s AND id = :id")
                .param("s", sessionId)
                .param("id", orderId)
                .query(Long.class)
                .optional();
    }
}
