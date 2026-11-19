package ru.tinvest.trainer.sessions;

import java.time.Duration;

/**
 * Часы и планировщик плеера. В приложении — реальное время ({@link SchedulerTimer}), в тестах — виртуальные часы,
 * которые двигаются вручную, поэтому тесты не ждут реальных секунд.
 */
interface PlayerTimer {

    /** Текущее время часов, мс. */
    long nowMs();

    /** Повторяет задачу с периодом {@code period}; задача одной подписки не выполняется параллельно сама с собой. */
    Handle every(Duration period, Runnable task);

    /** Выполняет задачу один раз через {@code delay}. */
    Handle after(Duration delay, Runnable task);

    /** Выполняет задачу отдельно от вызывающего потока (доставка событий); в тестах — сразу. */
    void execute(Runnable task);

    interface Handle {

        /** Отменяет задачу; уже запущенное выполнение не прерывается. Повторный вызов безопасен. */
        void cancel();
    }
}
