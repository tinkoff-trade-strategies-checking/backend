package ru.tinvest.trainer.worker;

import java.time.LocalDate;

/**
 * Окно из {@code ClassifyPeriods}. Служебные данные backend: клиенту до конца сессии разрешена только категория
 * (INV-1), даты в слепом режиме запрещены (INV-7). Поэтому {@code toString} не печатает даты и метрики.
 */
public record PeriodWindow(LocalDate startDate, LocalDate endDate, double sigma, double mdd, PeriodCategory category) {

    @Override
    public String toString() {
        return "PeriodWindow[" + category + "]";
    }
}
