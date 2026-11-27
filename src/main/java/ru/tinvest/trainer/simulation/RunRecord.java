package ru.tinvest.trainer.simulation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import ru.tinvest.trainer.marketdata.Regime;
import ru.tinvest.trainer.marketdata.StartMode;
import ru.tinvest.trainer.marketdata.Timeframe;
import ru.tinvest.trainer.worker.WorkerTypes.CheckLevel;
import ru.tinvest.trainer.worker.WorkerTypes.Costs;

/**
 * Прогон алгоритма: параметры запуска и зафиксированный план (INV-5). Даты периода, σ, MDD, пороги и тикеры не
 * попадают в {@code toString} и не раскрываются клиенту до завершения слепого прогона (INV-1, INV-7).
 */
public record RunRecord(
        UUID id,
        UUID userId,
        UUID strategyVersionId,
        UUID snapshotId,
        String snapshotKey,
        @Nullable UUID repeatOfRunId,
        boolean blind,
        String status,
        int durationDays,
        Timeframe timeframe,
        Regime regime,
        StartMode startMode,
        LocalDate periodStart,
        LocalDate periodEnd,
        int tradingDays,
        int barsCount,
        double sigma,
        double mdd,
        double p33,
        double p80,
        BigDecimal initialCapital,
        int warmupBars,
        Costs costs,
        Map<String, Double> paramOverrides,
        long doneSteps,
        @Nullable Long totalSteps,
        @Nullable Long lastStep,
        @Nullable Integer playedDays,
        @Nullable Long digest,
        @Nullable CheckLevel finalLevel,
        @Nullable String netReturnPct,
        @Nullable BigDecimal finalEquity,
        @Nullable String errorCode,
        OffsetDateTime createdAt,
        @Nullable OffsetDateTime finishedAt,
        List<RunPaperRecord> papers,
        @Nullable BigDecimal dailyLossLimitPct,
        boolean killSwitchTriggered) {

    public RunRecord {
        paramOverrides = Map.copyOf(paramOverrides);
        papers = List.copyOf(papers);
    }

    public RunRecord(
            UUID id,
            UUID userId,
            UUID strategyVersionId,
            UUID snapshotId,
            String snapshotKey,
            @Nullable UUID repeatOfRunId,
            boolean blind,
            String status,
            int durationDays,
            Timeframe timeframe,
            Regime regime,
            StartMode startMode,
            LocalDate periodStart,
            LocalDate periodEnd,
            int tradingDays,
            int barsCount,
            double sigma,
            double mdd,
            double p33,
            double p80,
            BigDecimal initialCapital,
            int warmupBars,
            Costs costs,
            Map<String, Double> paramOverrides,
            long doneSteps,
            @Nullable Long totalSteps,
            @Nullable Long lastStep,
            @Nullable Integer playedDays,
            @Nullable Long digest,
            @Nullable CheckLevel finalLevel,
            @Nullable String netReturnPct,
            @Nullable BigDecimal finalEquity,
            @Nullable String errorCode,
            OffsetDateTime createdAt,
            @Nullable OffsetDateTime finishedAt,
            List<RunPaperRecord> papers) {
        this(
                id,
                userId,
                strategyVersionId,
                snapshotId,
                snapshotKey,
                repeatOfRunId,
                blind,
                status,
                durationDays,
                timeframe,
                regime,
                startMode,
                periodStart,
                periodEnd,
                tradingDays,
                barsCount,
                sigma,
                mdd,
                p33,
                p80,
                initialCapital,
                warmupBars,
                costs,
                paramOverrides,
                doneSteps,
                totalSteps,
                lastStep,
                playedDays,
                digest,
                finalLevel,
                netReturnPct,
                finalEquity,
                errorCode,
                createdAt,
                finishedAt,
                papers,
                null,
                false);
    }

    /** Прогон ещё в очереди или выполняется. */
    public boolean isActive() {
        return "queued".equals(status) || "running".equals(status);
    }

    /** Прогон завершён (независимо от исхода: {@code completed}, {@code bankrupt}, {@code invalid}, {@code failed}). */
    public boolean isFinished() {
        return !isActive();
    }

    /** Данные периода и бумаг раскрыты клиенту (INV-7): неслепой прогон или слепой после завершения. */
    public boolean revealed() {
        return !blind || !isActive();
    }

    @Override
    public String toString() {
        return "RunRecord[" + id + ", blind=" + blind + ", status=" + status + "]";
    }
}
