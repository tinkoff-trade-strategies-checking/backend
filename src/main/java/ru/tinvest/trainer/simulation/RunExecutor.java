package ru.tinvest.trainer.simulation;

import io.grpc.StatusRuntimeException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.tinvest.trainer.api.DependencyUnavailableException;
import ru.tinvest.trainer.api.ErrorCode;
import ru.tinvest.trainer.worker.SimulationWorkerClient;
import ru.tinvest.trainer.worker.WorkerTypes.BarData;
import ru.tinvest.trainer.worker.WorkerTypes.BarInterval;
import ru.tinvest.trainer.worker.WorkerTypes.Market;
import ru.tinvest.trainer.worker.WorkerTypes.Paper;
import ru.tinvest.trainer.worker.WorkerTypes.RunProgressData;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyRunOutput;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyRunStatus;
import ru.tinvest.trainer.workshop.StrategyCatalog;
import ru.tinvest.trainer.workshop.StrategyVersionView;

/**
 * Исполнитель одного прогона алгоритма на Simulation Worker: вызывает потоковый {@code RunStrategy}, транслирует
 * прогресс в {@code simulation.run_event}, проверяет воспроизводимость повтора (UC-18, INV-4) и сохраняет отчёт.
 */
@Service
class RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(RunExecutor.class);
    private static final String BENCHMARK = "IMOEX";

    private final Runs runs;
    private final StrategyCatalog strategyCatalog;
    private final SimulationWorkerClient worker;
    private final RunProperties properties;

    RunExecutor(Runs runs, StrategyCatalog strategyCatalog, SimulationWorkerClient worker, RunProperties properties) {
        this.runs = runs;
        this.strategyCatalog = strategyCatalog;
        this.worker = worker;
        this.properties = properties;
    }

    void execute(UUID runId) {
        if (!runs.markRunning(runId)) {
            return;
        }
        executeClaimed(runId);
    }

    void executeClaimed(UUID runId) {
        try {
            RunRecord run =
                    runs.findRecord(runId).orElseThrow(() -> new IllegalStateException("Прогон не найден: " + runId));
            StrategyVersionView version = strategyCatalog
                    .findVersion(run.strategyVersionId())
                    .orElseThrow(
                            () -> new IllegalStateException("Версия стратегии не найдена: " + run.strategyVersionId()));
            Market market = market(run);
            long intervalMs = properties.progressInterval().toMillis();
            AtomicLong lastProgressMs = new AtomicLong(0L);
            AtomicLong lastProgressSteps = new AtomicLong(-1L);

            StrategyRunOutput output =
                    worker.runStrategy(market, version.source(), run.paramOverrides(), true, progress -> {
                        reportProgress(runId, progress, intervalMs, lastProgressMs, lastProgressSteps);
                    });

            String status = mapStatus(output.status());
            int playedDays = resolvePlayedDays(output);
            BigDecimal finalEquity =
                    output.report() == null ? null : output.report().finalEquity();
            String netReturnPct = output.report() == null
                    ? null
                    : RunFormat.pct(output.report().metrics().totalReturn());
            Boolean repeatMatch = computeRepeatMatch(run, status, output.digest(), finalEquity);
            long doneSteps = resolveDoneSteps(output);
            long totalSteps = Math.max(0L, output.totalSteps());

            Map<String, Object> report =
                    RunReportViews.algoRunReport(run, version.strategyName(), status, playedDays, repeatMatch, output);

            runs.finishSuccess(
                    runId,
                    status,
                    doneSteps,
                    totalSteps,
                    output.lastStep(),
                    playedDays,
                    output.digest(),
                    output.level(),
                    netReturnPct,
                    finalEquity,
                    repeatMatch,
                    report,
                    output.journal(),
                    output.diagnostics());
        } catch (DependencyUnavailableException e) {
            log.warn(
                    "Прогон {} не выполнен: воркер недоступен ({})",
                    runId,
                    e.getClass().getSimpleName());
            runs.finishFailed(runId, ErrorCode.SERVICE_UNAVAILABLE);
        } catch (RuntimeException e) {
            // INV-7: данные слепого прогона (тикеры, даты, суммы) в логи не попадают
            log.error("Прогон {} завершился ошибкой ({})", runId, failureReason(e));
            runs.finishFailed(runId, ErrorCode.INTERNAL_ERROR);
        }
    }

    void failRejected(UUID runId) {
        try {
            runs.finishFailed(runId, ErrorCode.SERVICE_UNAVAILABLE);
        } catch (RuntimeException e) {
            log.error("Не удалось перевести отклонённый прогон {} в failed", runId, e);
        }
    }

    private void reportProgress(
            UUID runId,
            RunProgressData progress,
            long intervalMs,
            AtomicLong lastProgressMs,
            AtomicLong lastProgressSteps) {
        long done = Math.max(0L, progress.doneSteps());
        long total = Math.max(0L, progress.totalSteps());
        long prev = lastProgressSteps.get();
        if (done <= prev) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean isLast = total > 0 && done >= total;
        if (prev < 0 || isLast || now - lastProgressMs.get() >= intervalMs) {
            if (lastProgressSteps.compareAndSet(prev, done)) {
                lastProgressMs.set(now);
                runs.updateProgress(runId, done, total);
            }
        }
    }

    static String failureReason(RuntimeException e) {
        if (e instanceof StatusRuntimeException sre) {
            return sre.getClass().getSimpleName() + ": " + sre.getStatus().getCode();
        }
        return e.getClass().getSimpleName();
    }

    static Market market(RunRecord run) {
        List<Paper> papers = new ArrayList<>(run.papers().size());
        for (RunPaperRecord p : run.papers()) {
            papers.add(new Paper(p.secid(), p.secid(), p.lotSize(), p.priceStep()));
        }
        return new Market(
                run.snapshotKey(),
                papers,
                BarInterval.valueOf(run.timeframe().name()),
                run.periodStart(),
                run.periodEnd(),
                run.costs(),
                run.initialCapital(),
                BENCHMARK,
                run.warmupBars());
    }

    private @Nullable Boolean computeRepeatMatch(
            RunRecord run, String currentStatus, long digest, @Nullable BigDecimal finalEquity) {
        if (run.repeatOfRunId() == null) {
            return null;
        }
        RunRecord original = runs.findRecord(run.repeatOfRunId()).orElse(null);
        if (original == null || original.isActive() || "failed".equals(original.status())) {
            return null;
        }
        if (!original.status().equals(currentStatus)) {
            return false;
        }
        if (!Objects.equals(original.digest(), digest)) {
            return false;
        }
        return sameDecimal(original.finalEquity(), finalEquity);
    }

    private static boolean sameDecimal(@Nullable BigDecimal a, @Nullable BigDecimal b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.compareTo(b) == 0;
    }

    private static String mapStatus(StrategyRunStatus status) {
        return switch (status) {
            case COMPLETED -> "completed";
            case BANKRUPT_STOP -> "bankrupt";
            case INVALID -> "invalid";
        };
    }

    private static int resolvePlayedDays(StrategyRunOutput output) {
        if (output.report() != null) {
            return Math.max(0, output.report().metrics().tradingDays());
        }
        int maxDay = 0;
        for (BarData bar : output.history()) {
            if (bar.tradingDay() > maxDay) {
                maxDay = bar.tradingDay();
            }
        }
        return maxDay;
    }

    private static long resolveDoneSteps(StrategyRunOutput output) {
        if (output.status() == StrategyRunStatus.COMPLETED && output.totalSteps() > 0) {
            return output.totalSteps();
        }
        if (output.lastStep() >= 0) {
            long completed = output.lastStep() + 1;
            return output.totalSteps() > 0 ? Math.min(output.totalSteps(), completed) : completed;
        }
        return 0L;
    }
}
