package ru.tinvest.trainer.analysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import ru.tinvest.trainer.api.InvalidRequestException;
import ru.tinvest.trainer.api.NotFoundException;
import ru.tinvest.trainer.identity.CurrentUser;
import ru.tinvest.trainer.sessions.SessionArchive;
import ru.tinvest.trainer.sessions.SessionRecord;
import ru.tinvest.trainer.worker.SimulationWorkerClient;
import ru.tinvest.trainer.worker.WorkerTypes.AlgorithmInput;
import ru.tinvest.trainer.worker.WorkerTypes.DivergenceOutput;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyParamDecl;
import ru.tinvest.trainer.workshop.StrategyCatalog;
import ru.tinvest.trainer.workshop.StrategyVersionView;
import tools.jackson.databind.ObjectMapper;

/**
 * Постфактум-сравнение завершённой сессии с 1–5 алгоритмами (UC-9 п. 1а/1б). Исходная сессия и её сохранённый отчёт не
 * изменяются (INV-5); расчёты выполняет воркер ядра (INV-3, INV-6).
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SessionCompareService {

    static final int MAX_ALGORITHMS = 5;

    private final SessionArchive archive;
    private final StrategyCatalog strategyCatalog;
    private final SimulationWorkerClient worker;
    private final CurrentUser currentUser;
    private final ObjectMapper json;

    SessionCompareService(
            SessionArchive archive,
            StrategyCatalog strategyCatalog,
            SimulationWorkerClient worker,
            CurrentUser currentUser,
            ObjectMapper json) {
        this.archive = archive;
        this.strategyCatalog = strategyCatalog;
        this.worker = worker;
        this.currentUser = currentUser;
        this.json = json;
    }

    Map<String, Object> compare(UUID sessionId, @Nullable List<UUID> algorithmIds) {
        List<UUID> validatedIds = validateAlgorithmIds(algorithmIds);
        SessionRecord session =
                archive.findOwned(sessionId).orElseThrow(() -> new NotFoundException("Session not found"));
        if (!SessionArchive.isFinished(session)) {
            throw new SessionNotFinishedException("The session is not finished");
        }

        UUID userId = currentUser.requireId();
        List<StrategyVersionView> versions = new ArrayList<>(validatedIds.size());
        List<AlgorithmInput> inputs = new ArrayList<>(validatedIds.size());
        int paperCount = Math.max(1, session.papers().size());
        double sizePct = Math.max(1, 95 / paperCount);

        for (UUID algorithmId : validatedIds) {
            StrategyVersionView version = strategyCatalog
                    .findLatestVersion(algorithmId, userId)
                    .orElseThrow(() -> new NotFoundException("Algorithm not found"));
            versions.add(version);
            Map<String, Double> params = resolveAlgoParams(json, version.paramDeclsJson(), sizePct);
            inputs.add(new AlgorithmInput(version.versionId().toString(), version.source(), params));
        }

        SessionArchive.CompletedReplay completed = archive.replayCompleted(session);
        DivergenceOutput output = worker.analyzeDivergences(
                completed.market(), completed.journal(), completed.replay().lastStep(), inputs);
        return SessionCompareViews.compare(completed, versions, output);
    }

    private static List<UUID> validateAlgorithmIds(@Nullable List<UUID> algorithmIds) {
        if (algorithmIds == null || algorithmIds.isEmpty()) {
            throw new InvalidRequestException("algorithmIds must contain between 1 and " + MAX_ALGORITHMS + " items");
        }
        if (algorithmIds.size() > MAX_ALGORITHMS) {
            throw new InvalidRequestException("algorithmIds must contain at most " + MAX_ALGORITHMS + " items");
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

    static Map<String, Double> resolveAlgoParams(ObjectMapper json, @Nullable String raw, double sizePct) {
        if (raw == null || raw.isBlank() || "[]".equals(raw.strip())) {
            return Map.of();
        }
        StrategyParamDecl[] decls = json.readValue(raw, StrategyParamDecl[].class);
        for (StrategyParamDecl decl : decls) {
            if ("size_pct".equals(decl.name())) {
                Double min = decl.rangeMin();
                Double max = decl.rangeMax();
                if (min != null && max != null && min > max) {
                    return Map.of();
                }
                double value = sizePct;
                if (min != null && value < min) {
                    value = min;
                }
                if (max != null && value > max) {
                    value = max;
                }
                return Map.of("size_pct", value);
            }
        }
        return Map.of();
    }
}
