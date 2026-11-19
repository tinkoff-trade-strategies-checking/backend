package ru.tinvest.trainer.sessions;

import java.math.BigDecimal;
import java.util.ArrayList;
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

    /** Команды журнала с исходом приёма для выгрузки отчёта (только завершённая сессия); нога OCO — отдельной записью. */
    List<CommandLogEntry> log(SessionRecord session) {
        List<CommandLogEntry> out = new ArrayList<>();
        jdbc.sql("SELECT seq, step, command_type, command, accepted, reject_reason "
                        + "FROM sessions.command_journal WHERE session_id = :id ORDER BY seq")
                .param("id", session.id())
                .query((rs, n) -> {
                    logEntries(
                            out,
                            rs.getLong("seq"),
                            rs.getLong("step"),
                            rs.getString("command_type"),
                            json.readTree(rs.getString("command")),
                            rs.getBoolean("accepted"),
                            rs.getString("reject_reason"),
                            session);
                    return n;
                })
                .list();
        return out;
    }

    private static void logEntries(
            List<CommandLogEntry> out,
            long seq,
            long step,
            String type,
            JsonNode command,
            boolean accepted,
            @Nullable String reason,
            SessionRecord session) {
        // у замены параметры новой заявки — в replacement.order, у подачи — в order; у отмены их нет
        JsonNode place = command.has("replacement") ? command.get("replacement") : command;
        JsonNode order = place.get("order");
        Long orderId = command.has("orderId") ? command.get("orderId").asLong() : null;
        if (order == null || order.isNull()) {
            out.add(new CommandLogEntry(
                    seq, step, type, orderId, null, null, null, null, null, null, null, null, false, accepted, reason));
            return;
        }
        out.add(logEntry(seq, step, type, orderId, order, false, accepted, reason, session));
        JsonNode oco = place.get("ocoWith");
        if (oco != null && !oco.isNull()) {
            out.add(logEntry(seq, step, type, null, oco, true, accepted, reason, session));
        }
    }

    private static CommandLogEntry logEntry(
            long seq,
            long step,
            String type,
            @Nullable Long orderId,
            JsonNode order,
            boolean ocoLeg,
            boolean accepted,
            @Nullable String reason,
            SessionRecord session) {
        return new CommandLogEntry(
                seq,
                step,
                type,
                orderId,
                session.papers().get(order.get("paperIndex").asInt()).secid(),
                order.get("side").asString(),
                order.get("kind").asString(),
                order.get("lots").asLong(),
                decimal(order.get("limitPrice")),
                decimal(order.get("stopPrice")),
                decimal(order.get("trailingPercent")),
                order.get("tif").asString(),
                ocoLeg,
                accepted,
                reason);
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
