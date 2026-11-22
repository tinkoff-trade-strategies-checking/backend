package ru.tinvest.trainer.simulation;

import ru.tinvest.trainer.marketdata.LaunchRejection;

/** Подбор периода или бумаг для прогона алгоритма отклонён {@code LaunchPlanner}. */
final class RunLaunchRejectedException extends RuntimeException {

    private final LaunchRejection rejection;

    RunLaunchRejectedException(LaunchRejection rejection) {
        super(rejection.reason().name());
        this.rejection = rejection;
    }

    LaunchRejection rejection() {
        return rejection;
    }
}
