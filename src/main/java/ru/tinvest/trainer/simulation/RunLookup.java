package ru.tinvest.trainer.simulation;

/** Состояние прогона текущего пользователя для отчёта и истории (модуль {@code analysis}). */
public sealed interface RunLookup permits RunLookup.Missing, RunLookup.Unfinished, RunLookup.Finished {

    /** Прогона нет или он принадлежит другому пользователю. */
    record Missing() implements RunLookup {}

    /** Прогон ещё в очереди или выполняется ({@code queued} или {@code running}). */
    record Unfinished(RunRecord run) implements RunLookup {}

    /** Прогон завершён ({@code completed}, {@code bankrupt}, {@code invalid} или {@code failed}). */
    record Finished(RunRecord run) implements RunLookup {}
}
