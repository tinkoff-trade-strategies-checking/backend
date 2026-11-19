package ru.tinvest.trainer.sessions;

import ru.tinvest.trainer.marketdata.LaunchRejection;

/** Подбор запуска отклонён: HTTP 422 {@code LaunchProblem}. Несёт только причину, поле и числа лимита. */
class LaunchRejectedException extends RuntimeException {

    private final transient LaunchRejection rejection;

    LaunchRejectedException(LaunchRejection rejection) {
        super(rejection.reason().name(), null, false, false);
        this.rejection = rejection;
    }

    LaunchRejection rejection() {
        return rejection;
    }
}
