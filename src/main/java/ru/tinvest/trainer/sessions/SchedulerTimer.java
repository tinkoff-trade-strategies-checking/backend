package ru.tinvest.trainer.sessions;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Реальные часы: пул потоков {@link ScheduledExecutorService}. Такт с фиксированной задержкой: очередь тактов не копится. */
final class SchedulerTimer implements PlayerTimer, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SchedulerTimer.class);

    private final ScheduledExecutorService executor;

    SchedulerTimer(int threads) {
        this.executor = Executors.newScheduledThreadPool(
                threads, Thread.ofPlatform().name("player-timer-", 0).factory());
    }

    @Override
    public long nowMs() {
        return System.currentTimeMillis();
    }

    @Override
    public Handle every(Duration period, Runnable task) {
        ScheduledFuture<?> future = executor.scheduleWithFixedDelay(
                guarded(task), period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public Handle after(Duration delay, Runnable task) {
        ScheduledFuture<?> future = executor.schedule(guarded(task), delay.toMillis(), TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public void execute(Runnable task) {
        Thread.ofVirtual().name("session-events-", 0).start(guarded(task));
    }

    /** Исключение задачи не должно останавливать повторяющееся расписание. */
    private static Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("Ошибка задачи плеера", e);
            }
        };
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
