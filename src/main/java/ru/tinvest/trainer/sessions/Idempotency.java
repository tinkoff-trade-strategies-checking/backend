package ru.tinvest.trainer.sessions;

import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Ключи {@code Idempotency-Key} ({@code sessions.idempotency_key}). Область ключа — сессия.
 *
 * <p>Ключ занимается атомарно (первичный ключ строки) отдельной сразу зафиксированной записью {@code in_progress} с
 * токеном захвата. Ответ и запись в журнал фиксируются одной транзакцией под блокировкой строки сессии, ответ пишет
 * только владелец токена. Повтор, пока первый запрос выполняется, — 409; после завершения — сохранённый ответ; другое
 * тело или операция — 422. Перехвата «зависшего» ключа по времени нет: запись {@code in_progress} без работающего
 * запроса остаётся только после остановки процесса и снимается при следующем запуске приложения.
 */
@Component
class Idempotency {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    Idempotency(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Результат захвата: либо ключ захвачен ({@code token}), либо готов прежний ответ. */
    record Claim(UUID token, Optional<JsonNode> previous) {

        boolean owned() {
            return previous.isEmpty();
        }
    }

    /**
     * Занимает ключ.
     *
     * @throws IdempotencyException ключ с другим телом или операцией (422) либо первый запрос ещё идёт (409)
     */
    Claim claim(UUID sessionId, UUID key, String operation, String requestHash) {
        UUID token = UUID.randomUUID();
        int inserted = jdbc.sql("INSERT INTO sessions.idempotency_key (session_id, key, operation, request_hash, "
                        + "status, claim_token) VALUES (:s, :k, :op, :hash, 'in_progress', :t) ON CONFLICT DO NOTHING")
                .param("s", sessionId)
                .param("k", key)
                .param("op", operation)
                .param("hash", requestHash)
                .param("t", token)
                .update();
        if (inserted == 1) {
            return new Claim(token, Optional.empty());
        }
        Stored stored = jdbc.sql("SELECT operation, request_hash, status, response "
                        + "FROM sessions.idempotency_key WHERE session_id = :s AND key = :k")
                .param("s", sessionId)
                .param("k", key)
                .query((rs, n) -> new Stored(
                        rs.getString("operation"),
                        rs.getString("request_hash"),
                        rs.getString("status"),
                        rs.getString("response")))
                .optional()
                // ключ освободили между вставкой и чтением (команда упала): занимаем заново
                .orElse(null);
        if (stored == null) {
            return claim(sessionId, key, operation, requestHash);
        }
        if (!stored.operation().equals(operation) || !stored.requestHash().equals(requestHash)) {
            throw IdempotencyException.reused();
        }
        if ("done".equals(stored.status())) {
            return new Claim(token, Optional.of(json.readTree(stored.response())));
        }
        throw IdempotencyException.inProgress();
    }

    /**
     * Перечитывает ключ под блокировкой сессии, внутри транзакции команды: если ответ уже записан, возвращает его, и
     * команду выполнять нельзя.
     *
     * @throws IdempotencyException ключ принадлежит другому запросу (409)
     */
    Optional<JsonNode> recheck(UUID sessionId, UUID key, UUID token) {
        Stored stored = jdbc.sql("SELECT operation, request_hash, status, response, claim_token "
                        + "FROM sessions.idempotency_key WHERE session_id = :s AND key = :k FOR UPDATE")
                .param("s", sessionId)
                .param("k", key)
                .query((rs, n) -> new Stored(
                        rs.getString("operation"),
                        rs.getString("request_hash"),
                        rs.getString("status"),
                        rs.getString("response"),
                        rs.getObject("claim_token", UUID.class)))
                .optional()
                .orElseThrow(IdempotencyException::inProgress);
        if ("done".equals(stored.status())) {
            return Optional.of(json.readTree(stored.response()));
        }
        if (!token.equals(stored.token())) {
            throw IdempotencyException.inProgress();
        }
        return Optional.empty();
    }

    /** Записывает ответ; вызывать в транзакции записи журнала. Ответ пишет только владелец токена. */
    void complete(UUID sessionId, UUID key, UUID token, Object response) {
        int updated = jdbc.sql("UPDATE sessions.idempotency_key SET status = 'done', response = CAST(:r AS jsonb) "
                        + "WHERE session_id = :s AND key = :k AND claim_token = :t AND status = 'in_progress'")
                .param("r", json.writeValueAsString(response))
                .param("s", sessionId)
                .param("k", key)
                .param("t", token)
                .update();
        if (updated != 1) {
            // ключ потерян: транзакция откатывается вместе с записью в журнал
            throw new IllegalStateException("Ключ идемпотентности " + key + " не принадлежит этому запросу");
        }
    }

    /** Освобождает ключ после ошибки (воркер недоступен и т. п.): повтор с тем же ключом выполнится заново. */
    void release(UUID sessionId, UUID key, UUID token) {
        jdbc.sql("DELETE FROM sessions.idempotency_key WHERE session_id = :s AND key = :k AND claim_token = :t "
                        + "AND status = 'in_progress'")
                .param("s", sessionId)
                .param("k", key)
                .param("t", token)
                .update();
    }

    /** После запуска процесса выполняющихся запросов нет: оставшиеся {@code in_progress} — следы остановки. */
    @EventListener(ApplicationReadyEvent.class)
    void dropAbandoned() {
        jdbc.sql("DELETE FROM sessions.idempotency_key WHERE status = 'in_progress'")
                .update();
    }

    private record Stored(String operation, String requestHash, String status, String response, UUID token) {

        Stored(String operation, String requestHash, String status, String response) {
            this(operation, requestHash, status, response, null);
        }
    }
}
