package ru.tinvest.trainer.sessions;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Ключи {@code Idempotency-Key} ({@code sessions.idempotency_key}). Область ключа — сессия. Ключ занимается отдельной
 * (сразу зафиксированной) записью {@code in_progress}; результат дописывается в той же транзакции, что и запись в
 * журнал, поэтому {@code in_progress} старше {@link #STALE} значит «журнал не записан» и ключ можно занять заново.
 */
@Component
class Idempotency {

    static final Duration STALE = Duration.ofSeconds(60);

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    Idempotency(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Занимает ключ.
     *
     * @return прежний ответ, если тот же запрос уже выполнен; пусто — ключ занят этим вызовом
     * @throws IdempotencyException ключ с другим телом или операцией (422) либо первый запрос ещё идёт (409)
     */
    Optional<JsonNode> claim(UUID sessionId, UUID key, String operation, String requestHash) {
        int inserted = jdbc.sql("INSERT INTO sessions.idempotency_key (session_id, key, operation, request_hash, "
                        + "status) VALUES (:s, :k, :op, :hash, 'in_progress') ON CONFLICT DO NOTHING")
                .param("s", sessionId)
                .param("k", key)
                .param("op", operation)
                .param("hash", requestHash)
                .update();
        if (inserted == 1) {
            return Optional.empty();
        }
        Stored stored = jdbc.sql("SELECT operation, request_hash, status, response, created_at "
                        + "FROM sessions.idempotency_key WHERE session_id = :s AND key = :k")
                .param("s", sessionId)
                .param("k", key)
                .query((rs, n) -> new Stored(
                        rs.getString("operation"),
                        rs.getString("request_hash"),
                        rs.getString("status"),
                        rs.getString("response"),
                        rs.getObject("created_at", OffsetDateTime.class)))
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
            return Optional.of(json.readTree(stored.response()));
        }
        if (stored.createdAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC).minus(STALE))) {
            int taken = jdbc.sql("UPDATE sessions.idempotency_key SET created_at = now() WHERE session_id = :s "
                            + "AND key = :k AND status = 'in_progress' AND created_at = :created")
                    .param("s", sessionId)
                    .param("k", key)
                    .param("created", stored.createdAt())
                    .update();
            if (taken == 1) {
                return Optional.empty();
            }
        }
        throw IdempotencyException.inProgress();
    }

    /** Записывает ответ; вызывать в транзакции записи журнала. */
    void complete(UUID sessionId, UUID key, Object response) {
        jdbc.sql("UPDATE sessions.idempotency_key SET status = 'done', response = CAST(:r AS jsonb) "
                        + "WHERE session_id = :s AND key = :k")
                .param("r", json.writeValueAsString(response))
                .param("s", sessionId)
                .param("k", key)
                .update();
    }

    /** Освобождает ключ после ошибки (воркер недоступен и т. п.): повтор с тем же ключом выполнится заново. */
    void release(UUID sessionId, UUID key) {
        jdbc.sql("DELETE FROM sessions.idempotency_key WHERE session_id = :s AND key = :k AND status = 'in_progress'")
                .param("s", sessionId)
                .param("k", key)
                .update();
    }

    private record Stored(
            String operation, String requestHash, String status, String response, OffsetDateTime createdAt) {}
}
