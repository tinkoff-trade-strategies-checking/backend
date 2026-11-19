package ru.tinvest.trainer.sessions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import ru.tinvest.trainer.marketdata.Regime;
import ru.tinvest.trainer.marketdata.StartMode;
import ru.tinvest.trainer.marketdata.Timeframe;
import ru.tinvest.trainer.worker.WorkerTypes.Costs;

/**
 * Сессия: параметры запуска и неизменный план (снапшот, период, категория, бумаги, капитал). Даты периода, σ, MDD,
 * пороги и тикеры — только для backend до конца сессии (INV-1, INV-7), наружу их отдаёт только
 * {@link BlindMasking}; {@code toString} их не печатает.
 */
public record SessionRecord(
        UUID id,
        UUID userId,
        boolean blind,
        String status,
        int durationDays,
        Timeframe timeframe,
        Regime regime,
        StartMode startMode,
        UUID snapshotId,
        String snapshotKey,
        LocalDate periodStart,
        LocalDate periodEnd,
        int tradingDays,
        int barsCount,
        double sigma,
        double mdd,
        double p33,
        double p80,
        BigDecimal initialCapital,
        OffsetDateTime createdAt,
        long currentStep,
        int warmupBars,
        Costs costs,
        List<PaperRecord> papers) {

    public SessionRecord {
        papers = List.copyOf(papers);
    }

    /**
     * Данные сессии раскрыты: неслепая сессия или слепая после завершения (INV-7, {@code decisions.md}: раскрытие только
     * после завершения). Отчёт, история и воспроизведение отдают реальные даты и тикеры только при {@code true}.
     */
    public boolean revealed() {
        return !blind || !"running".equals(status);
    }

    @Override
    public String toString() {
        return "SessionRecord[" + id + ", blind=" + blind + ", status=" + status + "]";
    }
}
