package ru.tinvest.trainer.simulation;

import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import ru.tinvest.trainer.api.ErrorCode;
import ru.tinvest.trainer.api.NotFoundException;
import ru.tinvest.trainer.identity.CurrentUser;
import ru.tinvest.trainer.marketdata.LaunchPlan;
import ru.tinvest.trainer.marketdata.LaunchPlanner;
import ru.tinvest.trainer.marketdata.LaunchRejection;
import ru.tinvest.trainer.marketdata.LaunchRequest;
import ru.tinvest.trainer.marketdata.LaunchResult;
import ru.tinvest.trainer.worker.SimulationWorkerClient;
import ru.tinvest.trainer.worker.WorkerTypes.CheckLevel;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyCheck;
import ru.tinvest.trainer.workshop.StrategyCatalog;
import ru.tinvest.trainer.workshop.StrategyVersionView;

/**
 * Запуск и повтор прогона алгоритма (UC-14, UC-18): проверяет доступ к версии стратегии и её валидность на воркере,
 * планирует период через {@link LaunchPlanner} и ставит задание в фоновую очередь {@link RunQueue}.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class RunLaunchService {

    private final LaunchPlanner planner;
    private final StrategyCatalog strategyCatalog;
    private final SimulationWorkerClient worker;
    private final Runs runs;
    private final RunQueue queue;
    private final CurrentUser currentUser;

    RunLaunchService(
            LaunchPlanner planner,
            StrategyCatalog strategyCatalog,
            SimulationWorkerClient worker,
            Runs runs,
            RunQueue queue,
            CurrentUser currentUser) {
        this.planner = planner;
        this.strategyCatalog = strategyCatalog;
        this.worker = worker;
        this.runs = runs;
        this.queue = queue;
        this.currentUser = currentUser;
    }

    UUID launch(
            UUID strategyVersionId, Map<String, Double> paramOverrides, boolean blindRequested, LaunchRequest request) {
        UUID userId = currentUser.requireId();
        StrategyVersionView version = strategyCatalog
                .findVersion(strategyVersionId)
                .filter(v -> strategyCatalog.canAccess(strategyVersionId, userId))
                .orElseThrow(() -> new NotFoundException("Strategy version not found"));

        StrategyCheck check = worker.checkStrategy(version.source(), paramOverrides);
        boolean hasError = check.lookaheadLevel() == CheckLevel.ERROR
                || check.diagnostics().stream().anyMatch(d -> d.level() == CheckLevel.ERROR);
        if (hasError) {
            throw new RunStrategyInvalidException(check);
        }

        LaunchResult result = planner.plan(request);
        LaunchPlan plan = switch (result) {
            case LaunchRejection rejection -> throw new RunLaunchRejectedException(rejection);
            case LaunchPlan p -> p;
        };
        boolean effectiveBlind = plan.blind() || blindRequested;
        UUID runId = runs.createPlanned(
                userId,
                strategyVersionId,
                request.startMode(),
                request.durationDays(),
                effectiveBlind,
                plan,
                paramOverrides);
        queue.enqueueAfterCommit(runId);
        return runId;
    }

    UUID repeat(UUID runId) {
        RunRecord original = runs.requireOwned(runId);
        if (original.isActive()) {
            throw new RunConflictException(ErrorCode.CONFLICT, "Run is not finished yet");
        }
        UUID userId = currentUser.requireId();
        UUID repeatedId = runs.repeatPlanned(userId, original);
        queue.enqueueAfterCommit(repeatedId);
        return repeatedId;
    }
}
