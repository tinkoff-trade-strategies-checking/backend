package ru.tinvest.trainer.analysis;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Сохранённые отчёты завершённых сессий ({@code analysis.session_report}): пишутся один раз, не меняются. */
@Component
class StoredReports {

    /** {@code report} — тело {@code SessionReport}; {@code playedDays} и {@code netReturnPct} — для истории. */
    record Stored(UUID sessionId, JsonNode report, int playedDays, String netReturnPct) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    StoredReports(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Optional<Stored> find(UUID sessionId) {
        return Optional.ofNullable(findAll(List.of(sessionId)).get(sessionId));
    }

    Map<UUID, Stored> findAll(Collection<UUID> sessionIds) {
        Map<UUID, Stored> out = new HashMap<>();
        if (sessionIds.isEmpty()) {
            return out;
        }
        jdbc.sql("SELECT session_id, report::text AS report, played_days, net_return_pct "
                        + "FROM analysis.session_report WHERE session_id IN (:ids)")
                .param("ids", sessionIds)
                .query((rs, n) -> out.put(
                        rs.getObject("session_id", UUID.class),
                        new Stored(
                                rs.getObject("session_id", UUID.class),
                                json.readTree(rs.getString("report")),
                                rs.getInt("played_days"),
                                rs.getString("net_return_pct"))))
                .list();
        return out;
    }

    /** Сохраняет отчёт; уже сохранённый не меняется (параллельная сборка, повтор события). */
    void insert(UUID sessionId, Object report, int playedDays, String netReturnPct) {
        jdbc.sql("INSERT INTO analysis.session_report (session_id, report, played_days, net_return_pct) "
                        + "VALUES (:id, CAST(:report AS jsonb), :days, :net) ON CONFLICT (session_id) DO NOTHING")
                .param("id", sessionId)
                .param("report", json.writeValueAsString(report))
                .param("days", playedDays)
                .param("net", netReturnPct)
                .update();
    }
}
