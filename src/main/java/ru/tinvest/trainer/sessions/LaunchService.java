package ru.tinvest.trainer.sessions;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import ru.tinvest.trainer.api.NotFoundException;
import ru.tinvest.trainer.identity.CurrentUser;
import ru.tinvest.trainer.marketdata.LaunchPlan;
import ru.tinvest.trainer.marketdata.LaunchPlanner;
import ru.tinvest.trainer.marketdata.LaunchRejection;
import ru.tinvest.trainer.marketdata.LaunchRequest;
import ru.tinvest.trainer.marketdata.LaunchResult;
import ru.tinvest.trainer.workshop.StrategyCatalog;
import ru.tinvest.trainer.workshop.StrategyVersionView;

/** Запуск ручной сессии: подбор периода и бумаг ({@link LaunchPlanner}) и сохранение неизменного плана. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class LaunchService {

    private final LaunchPlanner planner;
    private final Sessions sessions;
    private final StrategyCatalog strategyCatalog;
    private final CurrentUser currentUser;

    LaunchService(LaunchPlanner planner, Sessions sessions, StrategyCatalog strategyCatalog, CurrentUser currentUser) {
        this.planner = planner;
        this.sessions = sessions;
        this.strategyCatalog = strategyCatalog;
        this.currentUser = currentUser;
    }

    /**
     * @throws LaunchRejectedException подбор отклонён
     * @throws NotFoundException выбранный фоновый алгоритм не найден, не опубликован или недоступен пользователю
     * @throws ru.tinvest.trainer.api.DependencyUnavailableException воркер, архив недоступны или нет готового снапшота
     */
    UUID launch(LaunchRequest request, @Nullable UUID backgroundAlgorithmId) {
        UUID userId = currentUser.requireId();
        StrategyVersionView algoVersion = backgroundAlgorithmId == null
                ? strategyCatalog.systemDefaultVersion()
                : strategyCatalog
                        .findLatestVersion(backgroundAlgorithmId, userId)
                        .orElseThrow(() -> new NotFoundException("Background algorithm not found"));
        LaunchResult result = planner.plan(request);
        return switch (result) {
            case LaunchRejection rejection -> throw new LaunchRejectedException(rejection);
            case LaunchPlan plan ->
                sessions.create(userId, request.startMode(), request.durationDays(), plan, algoVersion);
        };
    }
}
