package ru.tinvest.trainer.sessions;

/**
 * Состояние сессии для отчёта и истории (модуль {@code analysis}): чужая и несуществующая неразличимы, незавершённая
 * не несёт ничего, кроме номера дня (INV-1, INV-7), у завершённой есть воспроизведение журнала с отчётом ядра.
 */
public sealed interface SessionLookup {

    /** Сессии нет или она чужая. */
    record Missing() implements SessionLookup {}

    /** Сессия идёт; {@code playedDays} — номер торгового дня на курсоре. */
    record Unfinished(SessionRecord session, int playedDays) implements SessionLookup {}

    /** Сессия завершена; отчёт и итог — в {@code analysis} (сохранённый отчёт), здесь только сама сессия. */
    record Finished(SessionRecord session) implements SessionLookup {}
}
