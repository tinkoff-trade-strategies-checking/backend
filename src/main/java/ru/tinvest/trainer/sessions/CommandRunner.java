package ru.tinvest.trainer.sessions;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Выполнение команды сессии под блокировкой её строки с {@code Idempotency-Key}: общий путь заявок арены и команд
 * плеера. Ключ занимается до транзакции, ответ пишется вместе с изменениями команды; при ошибке ключ освобождается,
 * и повтор выполнится заново.
 */
@Component
class CommandRunner {

    private final Sessions sessions;
    private final Idempotency idempotency;
    private final TransactionTemplate transaction;

    CommandRunner(Sessions sessions, Idempotency idempotency, PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.idempotency = idempotency;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Чтение под блокировкой строки сессии: команды и события этой сессии ждут, поэтому прочитанное внутри согласовано
     * между собой (снимок и номер последнего события).
     */
    <T> T readLocked(UUID sessionId, Function<SessionRecord, T> body) {
        sessions.requireOwned(sessionId);
        return transaction.execute(status -> body.apply(sessions.requireOwnedForUpdate(sessionId)));
    }

    Object run(
            UUID sessionId,
            @Nullable UUID key,
            String operation,
            String requestHash,
            Function<SessionRecord, Map<String, Object>> body) {
        // чужая и несуществующая сессия — 404 до любой записи
        sessions.requireOwned(sessionId);
        Idempotency.Claim claim = null;
        if (key != null) {
            claim = idempotency.claim(sessionId, key, operation, requestHash);
            if (!claim.owned()) {
                return claim.previous().get();
            }
        }
        Idempotency.Claim owned = claim;
        try {
            return transaction.execute(status -> {
                SessionRecord session = sessions.requireOwnedForUpdate(sessionId);
                if (owned != null) {
                    // под блокировкой сессии: ответ по ключу мог быть записан, пока ждали очередь
                    Optional<JsonNode> done = idempotency.recheck(sessionId, key, owned.token());
                    if (done.isPresent()) {
                        return done.get();
                    }
                }
                Map<String, Object> response = body.apply(session);
                if (owned != null) {
                    idempotency.complete(sessionId, key, owned.token(), response);
                }
                return response;
            });
        } catch (RuntimeException e) {
            if (owned != null) {
                idempotency.release(sessionId, key, owned.token());
            }
            throw e;
        }
    }
}
