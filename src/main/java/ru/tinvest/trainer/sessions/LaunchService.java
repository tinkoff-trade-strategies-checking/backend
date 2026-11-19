package ru.tinvest.trainer.sessions;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import ru.tinvest.trainer.identity.CurrentUser;
import ru.tinvest.trainer.marketdata.LaunchPlan;
import ru.tinvest.trainer.marketdata.LaunchPlanner;
import ru.tinvest.trainer.marketdata.LaunchRejection;
import ru.tinvest.trainer.marketdata.LaunchRequest;
import ru.tinvest.trainer.marketdata.LaunchResult;

/** Запуск ручной сессии: подбор периода и бумаг ({@link LaunchPlanner}) и сохранение неизменного плана. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class LaunchService {

    private final LaunchPlanner planner;
    private final Sessions sessions;
    private final CurrentUser currentUser;

    LaunchService(LaunchPlanner planner, Sessions sessions, CurrentUser currentUser) {
        this.planner = planner;
        this.sessions = sessions;
        this.currentUser = currentUser;
    }

    /**
     * @throws LaunchRejectedException подбор отклонён
     * @throws ru.tinvest.trainer.api.DependencyUnavailableException воркер, архив недоступны или нет готового снапшота
     */
    UUID launch(LaunchRequest request) {
        UUID userId = currentUser.requireId();
        LaunchResult result = planner.plan(request);
        return switch (result) {
            case LaunchRejection rejection -> throw new LaunchRejectedException(rejection);
            case LaunchPlan plan -> sessions.create(userId, request.startMode(), request.durationDays(), plan);
        };
    }
}
