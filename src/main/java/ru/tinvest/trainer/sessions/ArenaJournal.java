package ru.tinvest.trainer.sessions;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.tinvest.trainer.worker.WorkerTypes.Command;
import ru.tinvest.trainer.worker.WorkerTypes.JournalRecord;
import ru.tinvest.trainer.worker.WorkerTypes.OrderKind;
import ru.tinvest.trainer.worker.WorkerTypes.OrderParams;
import ru.tinvest.trainer.worker.WorkerTypes.RejectCode;
import ru.tinvest.trainer.worker.WorkerTypes.Tif;
import ru.tinvest.trainer.worker.WorkerTypes.TradeSide;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Журнал команд сессии ({@code sessions.command_journal}, INV-8): только дописывается. Команда хранится в рублях
 * (слепая заявка уже переведена {@code EstimateOrder}); бумага — индексом в сессии, не тикером.
 */
@Component
class ArenaJournal {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    ArenaJournal(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Записи журнала по порядку для воркера, не дальше курсора переданной сессии. */
    List<JournalRecord> records(SessionRecord session) {
        // записи дальше курсора сессии (курсор мог уйти вперёд после чтения сессии) воркер отвергает: берём до курсора
        return jdbc.sql("SELECT seq, step, command FROM sessions.command_journal "
                        + "WHERE session_id = :id AND step <= :step ORDER BY seq")
                .param("id", session.id())
                .param("step", session.currentStep())
                .query((rs, n) -> new JournalRecord(
                        rs.getLong("seq"), rs.getLong("step"), decode(rs.getString("command"), session)))
                .list();
    }

    /** {@code seen_step} по номеру команды. */
    Map<Long, Long> seenSteps(UUID sessionId) {
        Map<Long, Long> result = new HashMap<>();
        jdbc.sql("SELECT seq, seen_step FROM sessions.command_journal WHERE session_id = :id")
                .param("id", sessionId)
                .query((rs, n) -> result.put(rs.getLong("seq"), rs.getLong("seen_step")))
                .list();
        return result;
    }

    /** Номер следующей команды; вызывать под блокировкой сессии. */
    long nextSeq(UUID sessionId) {
        return jdbc.sql("SELECT coalesce(max(seq) + 1, 0) FROM sessions.command_journal WHERE session_id = :id")
                .param("id", sessionId)
                .query(Long.class)
                .single();
    }

    void append(
            SessionRecord session,
            JournalRecord record,
            long seenStep,
            boolean accepted,
            @Nullable RejectCode reason,
            @Nullable String rejectField) {
        String type = switch (record.command()) {
            case Command.Place p -> "place";
            case Command.Cancel c -> "cancel";
            case Command.Replace r -> "replace";
        };
        jdbc.sql("INSERT INTO sessions.command_journal (session_id, seq, step, seen_step, command_type, command, "
                        + "accepted, reject_reason, reject_field) VALUES (:session, :seq, :step, :seen, :type, "
                        + "CAST(:command AS jsonb), :accepted, :reason, :field)")
                .param("session", session.id())
                .param("seq", record.seq())
                .param("step", record.step())
                .param("seen", seenStep)
                .param("type", type)
                .param("command", json.writeValueAsString(encode(record.command(), session)))
                .param("accepted", accepted)
                .param("reason", reason == null ? null : reason.name())
                .param("field", rejectField == null || rejectField.isEmpty() ? null : rejectField)
                .update();
    }

    // ---------- JSON команды ----------

    private static Map<String, Object> encode(Command command, SessionRecord session) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (command) {
            case Command.Place p -> {
                out.put("order", encode(p.order(), session));
                out.put("ocoWith", p.ocoWith() == null ? null : encode(p.ocoWith(), session));
            }
            case Command.Cancel c -> out.put("orderId", c.orderId());
            case Command.Replace r -> {
                out.put("orderId", r.orderId());
                out.put("replacement", encode(r.replacement(), session));
            }
        }
        return out;
    }

    private static Map<String, Object> encode(OrderParams spec, SessionRecord session) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paperIndex", indexOf(spec.paperKey(), session));
        out.put("side", spec.side().name());
        out.put("kind", spec.kind().name());
        out.put("lots", spec.lots());
        out.put("limitPrice", plain(spec.limitPrice()));
        out.put("stopPrice", plain(spec.stopPrice()));
        out.put("trailingPercent", plain(spec.trailingPercent()));
        out.put("tif", spec.tif().name());
        return out;
    }

    private Command decode(String text, SessionRecord session) {
        JsonNode node = json.readTree(text);
        if (node.has("replacement")) {
            return new Command.Replace(node.get("orderId").asLong(), place(node.get("replacement"), session));
        }
        if (node.has("order")) {
            return place(node, session);
        }
        return new Command.Cancel(node.get("orderId").asLong());
    }

    private static Command.Place place(JsonNode node, SessionRecord session) {
        JsonNode oco = node.get("ocoWith");
        return new Command.Place(
                spec(node.get("order"), session), oco == null || oco.isNull() ? null : spec(oco, session));
    }

    private static OrderParams spec(JsonNode node, SessionRecord session) {
        return new OrderParams(
                session.papers().get(node.get("paperIndex").asInt()).secid(),
                TradeSide.valueOf(node.get("side").asString()),
                OrderKind.valueOf(node.get("kind").asString()),
                node.get("lots").asLong(),
                decimal(node.get("limitPrice")),
                decimal(node.get("stopPrice")),
                decimal(node.get("trailingPercent")),
                Tif.valueOf(node.get("tif").asString()),
                false);
    }

    private static @Nullable BigDecimal decimal(@Nullable JsonNode node) {
        return node == null || node.isNull() ? null : new BigDecimal(node.asString());
    }

    private static @Nullable String plain(@Nullable BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static int indexOf(String paperKey, SessionRecord session) {
        for (PaperRecord paper : session.papers()) {
            if (paper.secid().equals(paperKey)) {
                return paper.paperIndex();
            }
        }
        throw new IllegalStateException("Бумага команды не входит в сессию " + session.id());
    }
}
