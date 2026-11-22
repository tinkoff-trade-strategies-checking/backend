package ru.tinvest.trainer.simulation;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.tinvest.trainer.api.ErrorCode;

/**
 * Фоновая очередь исполнения прогонов алгоритмов (UC-14, НФТ-7). Использует собственный пул потоков (не бин
 * {@code Executor}, чтобы не отключать стандартный пул Spring Boot), ограничивает число одновременных {@code running}
 * прогонов на пользователя ({@code maxRunningPerUser}) и корректно завершает текущие задачи при остановке.
 */
@Service
class RunQueue {

    private static final Logger log = LoggerFactory.getLogger(RunQueue.class);

    private final Runs runs;
    private final RunExecutor executorService;
    private final RunProperties properties;
    private final ThreadPoolTaskExecutor pool;
    private final Object queueLock = new Object();
    private final Set<UUID> pending = new LinkedHashSet<>();
    private final AtomicInteger queuedSignals = new AtomicInteger(0);
    private volatile boolean shuttingDown;

    RunQueue(Runs runs, RunExecutor executorService, RunProperties properties) {
        this.runs = runs;
        this.executorService = executorService;
        this.properties = properties;
        this.pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(properties.threads());
        pool.setMaxPoolSize(properties.threads());
        pool.setQueueCapacity(Math.max(properties.threads(), properties.queueCapacity()));
        pool.setThreadNamePrefix("algo-run-");
        pool.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(30);
        pool.initialize();
    }

    /**
     * После перезапуска процесса очередь в памяти пуста: осиротевшие {@code queued}/{@code running} прогоны переводим в
     * {@code failed} ({@code SERVICE_UNAVAILABLE}), чтобы они не зависали навсегда.
     */
    @EventListener(ApplicationReadyEvent.class)
    void failAbandonedAfterRestart() {
        synchronized (queueLock) {
            pending.clear();
        }
        for (UUID runId : runs.findActiveIds()) {
            try {
                runs.finishFailed(runId, ErrorCode.SERVICE_UNAVAILABLE);
            } catch (RuntimeException e) {
                log.error("Не удалось перевести осиротевший прогон {} в failed", runId, e);
            }
        }
    }

    /** Ставит прогон в очередь после фиксации текущей транзакции (или сразу, если транзакции нет). */
    void enqueueAfterCommit(UUID runId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            enqueue(runId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                enqueue(runId);
            }
        });
    }

    private void enqueue(UUID runId) {
        synchronized (queueLock) {
            if (pending.size() >= properties.queueCapacity()) {
                log.error(
                        "Очередь прогонов переполнена (capacity={}), отклоняем {}", properties.queueCapacity(), runId);
                executorService.failRejected(runId);
                return;
            }
            pending.add(runId);
        }
        scheduleWorker();
    }

    private void scheduleWorker() {
        if (shuttingDown) {
            return;
        }
        int limit = properties.threads();
        while (true) {
            int current = queuedSignals.get();
            if (current >= limit) {
                return;
            }
            if (queuedSignals.compareAndSet(current, current + 1)) {
                try {
                    pool.execute(this::runWorkerLoop);
                } catch (RuntimeException e) {
                    queuedSignals.decrementAndGet();
                    log.warn("Не удалось запланировать обработчик очереди прогонов", e);
                }
                return;
            }
        }
    }

    private void runWorkerLoop() {
        queuedSignals.decrementAndGet();
        while (!shuttingDown) {
            List<UUID> candidates;
            synchronized (queueLock) {
                if (pending.isEmpty()) {
                    return;
                }
                candidates = new ArrayList<>(pending);
            }
            Optional<UUID> claimed = runs.claimFirstEligible(candidates, properties.maxRunningPerUser());
            if (claimed.isEmpty()) {
                return;
            }
            UUID runId = claimed.get();
            synchronized (queueLock) {
                pending.remove(runId);
            }
            try {
                executorService.executeClaimed(runId);
            } catch (RuntimeException e) {
                log.error("Непредвиденная ошибка выполнения прогона {}", runId, e);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        shuttingDown = true;
        pool.shutdown();
    }
}
