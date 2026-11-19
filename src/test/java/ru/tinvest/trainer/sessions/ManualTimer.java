package ru.tinvest.trainer.sessions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Виртуальные часы плеера для тестов: время идёт только при {@link #advance}, задачи выполняются на потоке теста в
 * порядке времени запуска, ожидания реальных секунд нет. Доставка событий ({@link #execute}) — сразу.
 */
final class ManualTimer implements PlayerTimer {

    private static final class Task implements Handle {

        long at;
        final long period;
        final Runnable body;
        final boolean permanent;
        boolean cancelled;

        Task(long at, long period, Runnable body, boolean permanent) {
            this.at = at;
            this.period = period;
            this.body = body;
            this.permanent = permanent;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    private final List<Task> tasks = new ArrayList<>();
    private long now = 1_000_000;
    private boolean baseline;

    @Override
    public synchronized long nowMs() {
        return now;
    }

    @Override
    public synchronized Handle every(Duration period, Runnable task) {
        Task t = new Task(now + period.toMillis(), period.toMillis(), task, !baseline);
        tasks.add(t);
        return t;
    }

    @Override
    public synchronized Handle after(Duration delay, Runnable task) {
        Task t = new Task(now + delay.toMillis(), 0, task, false);
        tasks.add(t);
        return t;
    }

    @Override
    public void execute(Runnable task) {
        task.run();
    }

    /** Задачи, созданные при старте контекста (пульс потока), остаются; остальные снимаются перед каждым тестом. */
    synchronized void reset() {
        baseline = true;
        tasks.removeIf(t -> !t.permanent);
    }

    /** Активные задачи сверх постоянных: у остановленной игры их нет. */
    synchronized long pendingGameTasks() {
        return tasks.stream().filter(t -> !t.permanent && !t.cancelled).count();
    }

    /** Двигает время на {@code duration}, выполняя наступившие задачи. */
    void advance(Duration duration) {
        long target;
        synchronized (this) {
            target = now + duration.toMillis();
        }
        while (true) {
            Task next;
            synchronized (this) {
                tasks.removeIf(t -> t.cancelled);
                next = tasks.stream()
                        .filter(t -> t.at <= target)
                        .min(Comparator.comparingLong((Task t) -> t.at))
                        .orElse(null);
                if (next == null) {
                    now = target;
                    return;
                }
                now = Math.max(now, next.at);
                if (next.period > 0) {
                    next.at += next.period;
                } else {
                    next.cancelled = true;
                }
            }
            next.body.run();
        }
    }
}
