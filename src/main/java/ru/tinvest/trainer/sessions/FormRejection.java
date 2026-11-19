package ru.tinvest.trainer.sessions;

import org.jspecify.annotations.Nullable;
import ru.tinvest.trainer.worker.WorkerTypes.RejectCode;

/**
 * Отказ до воркера (форма заявки, {@code seenStep} больше курсора, завершённая сессия): в журнал не пишется
 * ({@code decisions.md}, «Заявки»). Ответ клиенту — 200 с {@code accepted: false}.
 */
final class FormRejection extends RuntimeException {

    private final RejectCode reason;
    private final @Nullable String field;

    FormRejection(RejectCode reason, @Nullable String field) {
        super(reason.name(), null, false, false);
        this.reason = reason;
        this.field = field;
    }

    RejectCode reason() {
        return reason;
    }

    @Nullable
    String field() {
        return field;
    }
}
