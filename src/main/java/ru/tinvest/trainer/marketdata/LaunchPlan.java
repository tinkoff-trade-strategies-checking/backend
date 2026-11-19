package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Подобранные параметры запуска. Всё, кроме {@code regime}, {@code timeframe}, {@code barsCount}, {@code capital} и
 * секторов бумаг, — только для backend до конца сессии (INV-1, INV-7): даты периода, σ, MDD, пороги, тикеры.
 * {@code toString} их не печатает, чтобы они не попали в логи.
 *
 * @param blind слепая сессия ({@link StartMode#RANDOM})
 * @param tradingDays длина периода в торговых днях
 * @param sigma годовая реализованная волатильность окна
 * @param mdd максимальная просадка окна, доля 0..1
 * @param p33 порог «спокойный» по всему снапшоту
 * @param p80 порог «стрессовый» по всему снапшоту
 * @param barsCount оценка числа свечей на бумагу ({@code tradingDays × свечей в день})
 */
public record LaunchPlan(
        Snapshot snapshot,
        boolean blind,
        LocalDate startDate,
        LocalDate endDate,
        int tradingDays,
        Regime regime,
        double sigma,
        double mdd,
        double p33,
        double p80,
        Timeframe timeframe,
        int barsCount,
        List<PlannedPaper> papers,
        BigDecimal capital)
        implements LaunchResult {

    public LaunchPlan {
        papers = List.copyOf(papers);
    }

    @Override
    public String toString() {
        return "LaunchPlan[snapshot=" + snapshot.id() + ", regime=" + regime + ", papers=" + papers.size() + "]";
    }
}
