package ru.tinvest.trainer.simulation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import ru.tinvest.trainer.api.ErrorCode;
import ru.tinvest.trainer.api.InvalidRequestException;
import ru.tinvest.trainer.api.NotFoundException;
import ru.tinvest.trainer.identity.CurrentUser;
import ru.tinvest.trainer.marketdata.LaunchPlan;
import ru.tinvest.trainer.marketdata.LaunchPlanner;
import ru.tinvest.trainer.marketdata.LaunchRejection;
import ru.tinvest.trainer.marketdata.LaunchRequest;
import ru.tinvest.trainer.marketdata.LaunchResult;
import ru.tinvest.trainer.marketdata.PlannedPaper;
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

    static final int MIN_COMPARE_ALGORITHMS = 2;
    static final int MAX_COMPARE_ALGORITHMS = 5;

    record CompareLaunchItem(UUID algorithmId, String algorithmName, UUID versionId, UUID runId, String status) {}

    record CompareLaunchResult(
            LaunchPlan plan,
            int durationDays,
            boolean blind,
            List<PlannedPaper> papers,
            List<CompareLaunchItem> runs) {}

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
            UUID strategyVersionId,
            Map<String, Double> paramOverrides,
            boolean blindRequested,
            LaunchRequest request,
            @Nullable BigDecimal dailyLossLimitPct) {
        UUID userId = currentUser.requireId();
        StrategyVersionView version = strategyCatalog
                .findVersion(strategyVersionId)
                .filter(v -> strategyCatalog.canAccess(strategyVersionId, userId))
                .orElseThrow(() -> new NotFoundException("Strategy version not found"));

        checkStrategyValid(version.source(), paramOverrides);

        LaunchPlan plan = planOrThrow(request);
        boolean effectiveBlind = plan.blind() || blindRequested;
        UUID runId = runs.createPlanned(
                userId,
                strategyVersionId,
                request.startMode(),
                request.durationDays(),
                effectiveBlind,
                plan,
                paramOverrides,
                dailyLossLimitPct);
        queue.enqueueAfterCommit(runId);
        return runId;
    }

    CompareLaunchResult launchCompare(
            @Nullable List<UUID> algorithmIds,
            boolean blindRequested,
            LaunchRequest request,
            @Nullable BigDecimal dailyLossLimitPct) {
        List<UUID> validatedIds = validateCompareAlgorithmIds(algorithmIds);
        UUID userId = currentUser.requireId();

        List<StrategyVersionView> versions = new ArrayList<>(validatedIds.size());
        for (UUID algorithmId : validatedIds) {
            StrategyVersionView version = strategyCatalog
                    .findLatestVersion(algorithmId, userId)
                    .orElseThrow(() -> new NotFoundException("Algorithm not found"));
            versions.add(version);
        }

        for (StrategyVersionView version : versions) {
            checkStrategyValid(version.source(), Map.of());
        }

        LaunchPlan plan = planOrThrow(request);
        boolean effectiveBlind = plan.blind() || blindRequested;
        List<UUID> versionIds =
                versions.stream().map(StrategyVersionView::versionId).toList();
        Runs.PlannedBatch batch = runs.createPlannedBatch(
                userId,
                versionIds,
                request.startMode(),
                request.durationDays(),
                effectiveBlind,
                plan,
                dailyLossLimitPct);

        for (UUID runId : batch.runIds()) {
            queue.enqueueAfterCommit(runId);
        }

        List<CompareLaunchItem> items = new ArrayList<>(versions.size());
        for (int i = 0; i < versions.size(); i++) {
            StrategyVersionView v = versions.get(i);
            items.add(new CompareLaunchItem(
                    v.strategyId(),
                    v.strategyName(),
                    v.versionId(),
                    batch.runIds().get(i),
                    "queued"));
        }
        return new CompareLaunchResult(
                plan, request.durationDays(), effectiveBlind, batch.papers(), List.copyOf(items));
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

    private void checkStrategyValid(String source, Map<String, Double> paramOverrides) {
        StrategyCheck check = worker.checkStrategy(source, paramOverrides);
        boolean hasError = check.lookaheadLevel() == CheckLevel.ERROR
                || check.diagnostics().stream().anyMatch(d -> d.level() == CheckLevel.ERROR);
        if (hasError) {
            throw new RunStrategyInvalidException(check);
        }
    }

    private LaunchPlan planOrThrow(LaunchRequest request) {
        LaunchResult result = planner.plan(request);
        return switch (result) {
            case LaunchRejection rejection -> throw new RunLaunchRejectedException(rejection);
            case LaunchPlan p -> p;
        };
    }

    private static List<UUID> validateCompareAlgorithmIds(@Nullable List<UUID> algorithmIds) {
        if (algorithmIds == null
                || algorithmIds.size() < MIN_COMPARE_ALGORITHMS
                || algorithmIds.size() > MAX_COMPARE_ALGORITHMS) {
            throw new InvalidRequestException("algorithmIds must contain between "
                    + MIN_COMPARE_ALGORITHMS
                    + " and "
                    + MAX_COMPARE_ALGORITHMS
                    + " items");
        }
        Set<UUID> seen = new HashSet<>(algorithmIds.size());
        for (UUID id : algorithmIds) {
            if (id == null) {
                throw new InvalidRequestException("algorithmIds must not contain null");
            }
            if (!seen.add(id)) {
                throw new InvalidRequestException("algorithmIds must not contain duplicates");
            }
        }
        return algorithmIds;
    }
}
