package ru.tinvest.trainer.sessions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import ru.tinvest.trainer.marketdata.Regime;
import ru.tinvest.trainer.marketdata.StartMode;
import ru.tinvest.trainer.marketdata.Timeframe;

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
        List<PaperRecord> papers) {

    public SessionRecord {
        papers = List.copyOf(papers);
    }

    @Override
    public String toString() {
        return "SessionRecord[" + id + ", blind=" + blind + ", status=" + status + "]";
    }
}
