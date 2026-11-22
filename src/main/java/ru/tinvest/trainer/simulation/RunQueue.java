package ru.tinvest.trainer.simulation;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
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
 * Фоновая очередь исполнения прогонов алгоритмов (UC-14). Использует собственный пул потоков (не бин {@code Executor},
 * чтобы не отключать стандартный пул Spring Boot) и корректно завершает текущие задачи при остановке.
 */
@Service
class RunQueue {

    private static final Logger log = LoggerFactory.getLogger(RunQueue.class);

    private final Runs runs;
    private final RunExecutor executorService;
    private final ThreadPoolTaskExecutor pool;

    RunQueue(Runs runs, RunExecutor executorService, RunProperties properties) {
        this.runs = runs;
        this.executorService = executorService;
        this.pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(properties.threads());
        pool.setMaxPoolSize(properties.threads());
        pool.setQueueCapacity(properties.queueCapacity());
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
            submit(runId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(runId);
            }
        });
    }

    private void submit(UUID runId) {
        try {
            pool.execute(() -> executorService.execute(runId));
        } catch (RuntimeException e) {
            log.error("Не удалось поставить прогон {} в очередь исполнения", runId, e);
            executorService.failRejected(runId);
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }
}
