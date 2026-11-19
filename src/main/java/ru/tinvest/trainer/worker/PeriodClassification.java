package ru.tinvest.trainer.worker;

import java.util.List;

/** Ответ {@code ClassifyPeriods}: окна и пороги P33/P80 по всему снапшоту (только backend, INV-1). */
public record PeriodClassification(List<PeriodWindow> windows, double p33, double p80) {

    public PeriodClassification {
        windows = List.copyOf(windows);
    }

    @Override
    public String toString() {
        return "PeriodClassification[windows=" + windows.size() + "]";
    }
}
