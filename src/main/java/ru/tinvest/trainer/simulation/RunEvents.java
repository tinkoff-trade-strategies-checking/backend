package ru.tinvest.trainer.simulation;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * События прогонов алгоритмов для потока SSE ({@code simulation.run_event}): запись событий в транзакции и живая
 * доставка подписчикам с поддержкой догона по {@code Last-Event-ID}.
 */
@Component
class RunEvents {

    private static final Logger log = LoggerFactory.getLogger(RunEvents.class);
    private static final int PAGE = 200;

    interface Sink {

        void send(long eventId, String type, String data) throws Exception;

        void ping() throws Exception;

        void abort();
    }

    final class Subscription {

        private final UUID runId;
        private final Sink sink;
        private long lastSent;
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicBoolean dirty = new AtomicBoolean();
        private final AtomicBoolean pingRequested = new AtomicBoolean();
        private volatile boolean closed;
        private volatile long sendingSinceMs;

        private Subscription(UUID runId, Sink sink, long lastSent) {
            this.runId = runId;
            this.sink = sink;
            this.lastSent = lastSent;
        }

        void signal() {
            dirty.set(true);
            startIfIdle();
        }

        private void startIfIdle() {
            if (closed || !running.compareAndSet(false, true)) {
                return;
            }
            try {
                sendExecutor.execute(this::run);
            } catch (RuntimeException e) {
                running.set(false);
                throw e;
            }
        }

        private void run() {
            try {
                sends.acquireUninterruptibly();
                try {
                    do {
                        dirty.set(false);
                        sendNew();
                        if (pingRequested.getAndSet(false) && !closed) {
                            mark(sink::ping);
                        }
                    } while ((dirty.get() || pingRequested.get()) && !closed);
                } finally {
                    sends.release();
                }
            } catch (Exception e) {
                closeAfterFailure();
            } finally {
                sendingSinceMs = 0;
                running.set(false);
            }
            if ((dirty.get() || pingRequested.get()) && !closed) {
                startIfIdle();
            }
        }

        private void sendNew() throws Exception {
            while (!closed) {
                List<Stored> page = load(runId, lastSent, PAGE);
                for (Stored event : page) {
                    mark(() -> sink.send(event.id(), event.type(), data(event)));
                    lastSent = event.id();
                }
                if (page.size() < PAGE) {
                    return;
                }
            }
        }

        private void mark(ThrowingRunnable send) throws Exception {
            sendingSinceMs = System.currentTimeMillis();
            try {
                send.run();
            } finally {
                sendingSinceMs = 0;
            }
        }

        void heartbeat(long hangLimitMs) {
            if (closed) {
                return;
            }
            long since = sendingSinceMs;
            if (since != 0 && System.currentTimeMillis() - since > hangLimitMs) {
                abortAsync();
                return;
            }
            pingRequested.set(true);
            startIfIdle();
        }

        private void abortAsync() {
            close();
            abortExecutor.execute(() -> {
                try {
                    sink.abort();
                } catch (RuntimeException e) {
                    log.debug("SSE поток прогона уже закрыт", e);
                }
            });
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            unsubscribe(this);
        }

        private void closeAfterFailure() {
            close();
            try {
                sink.abort();
            } catch (RuntimeException e) {
                log.debug("SSE поток прогона уже закрыт", e);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {

        void run() throws Exception;
    }

    private record Stored(long id, String type, String payload) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final RunProperties properties;
    private final Semaphore sends;
    private final ThreadPoolTaskExecutor sendExecutor;
    private final ExecutorService abortExecutor;
    private final ScheduledExecutorService scheduler;
    private final Map<UUID, List<Subscription>> subscriptions = new ConcurrentHashMap<>();

    RunEvents(JdbcClient jdbc, ObjectMapper json, RunProperties properties) {
        this.jdbc = jdbc;
        this.json = json;
        this.properties = properties;
        this.sends = new Semaphore(properties.maxConcurrentSends());
        this.sendExecutor = new ThreadPoolTaskExecutor();
        sendExecutor.setCorePoolSize(2);
        sendExecutor.setMaxPoolSize(8);
        sendExecutor.setQueueCapacity(1000);
        sendExecutor.setThreadNamePrefix("run-sse-");
        sendExecutor.setWaitForTasksToCompleteOnShutdown(true);
        sendExecutor.initialize();
        this.abortExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "run-sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    void start() {
        long heartbeatMs = properties.heartbeat().toMillis();
        long hangLimitMs = 2 * heartbeatMs;
        scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        List<Subscription> snapshot = new ArrayList<>();
                        synchronized (subscriptions) {
                            for (List<Subscription> list : subscriptions.values()) {
                                snapshot.addAll(list);
                            }
                        }
                        for (Subscription subscription : snapshot) {
                            subscription.heartbeat(hangLimitMs);
                        }
                    } catch (RuntimeException e) {
                        log.error("Ошибка heartbeat потока событий прогона", e);
                    }
                },
                heartbeatMs,
                heartbeatMs,
                TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        cleanup();
                    } catch (RuntimeException e) {
                        log.error("Не удалось очистить события завершённых прогонов", e);
                    }
                },
                1,
                1,
                TimeUnit.HOURS);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
        abortExecutor.shutdownNow();
        sendExecutor.shutdown();
    }

    int cleanup() {
        return jdbc.sql("DELETE FROM simulation.run_event e USING simulation.run r "
                        + "WHERE e.run_id = r.id AND r.status NOT IN ('queued', 'running') "
                        + "AND e.created_at < now() - make_interval(secs => :secs)")
                .param("secs", properties.eventRetention().toSeconds())
                .update();
    }

    /** Дописывает событие в транзакции; подписчики получают его после фиксации. */
    long append(UUID runId, String type, Map<String, Object> payload) {
        long id = jdbc.sql("SELECT coalesce(max(event_id), 0) + 1 FROM simulation.run_event WHERE run_id = :r")
                .param("r", runId)
                .query(Long.class)
                .single();
        jdbc.sql("INSERT INTO simulation.run_event (run_id, event_id, type, payload) "
                        + "VALUES (:r, :id, :type, CAST(:payload AS jsonb))")
                .param("r", runId)
                .param("id", id)
                .param("type", type)
                .param("payload", json.writeValueAsString(payload))
                .update();
        afterCommit(() -> notifySubscribers(runId));
        return id;
    }

    long lastEventId(UUID runId) {
        return jdbc.sql("SELECT coalesce(max(event_id), 0) FROM simulation.run_event WHERE run_id = :r")
                .param("r", runId)
                .query(Long.class)
                .single();
    }

    /**
     * Подписывает получателя на события прогона. С {@code lastEventId} отдаются события после него, без заголовка —
     * вся история событий прогона с начала (чтобы быстро завершившийся прогон не потерял события).
     */
    Subscription subscribe(UUID runId, @Nullable Long lastEventId, Sink sink) {
        long last = lastEventId(runId);
        long startAfter = lastEventId == null ? 0L : Math.min(lastEventId, last);
        Subscription subscription = new Subscription(runId, sink, startAfter);
        synchronized (subscriptions) {
            subscriptions
                    .computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>())
                    .add(subscription);
        }
        subscription.signal();
        return subscription;
    }

    int subscribers(UUID runId) {
        synchronized (subscriptions) {
            List<Subscription> list = subscriptions.get(runId);
            return list == null ? 0 : list.size();
        }
    }

    private void unsubscribe(Subscription subscription) {
        synchronized (subscriptions) {
            List<Subscription> list = subscriptions.get(subscription.runId);
            if (list == null) {
                return;
            }
            list.remove(subscription);
            if (list.isEmpty()) {
                subscriptions.remove(subscription.runId, list);
            }
        }
    }

    void notifySubscribers(UUID runId) {
        List<Subscription> list;
        synchronized (subscriptions) {
            List<Subscription> current = subscriptions.get(runId);
            if (current == null || current.isEmpty()) {
                return;
            }
            list = List.copyOf(current);
        }
        for (Subscription subscription : list) {
            subscription.signal();
        }
    }

    private List<Stored> load(UUID runId, long after, int limit) {
        return jdbc.sql("SELECT event_id, type, payload::text AS payload FROM simulation.run_event "
                        + "WHERE run_id = :r AND event_id > :after ORDER BY event_id LIMIT :limit")
                .param("r", runId)
                .param("after", after)
                .param("limit", limit)
                .query((rs, n) -> new Stored(rs.getLong("event_id"), rs.getString("type"), rs.getString("payload")))
                .list();
    }

    private String data(Stored event) {
        ObjectNode out = json.createObjectNode();
        out.put("type", event.type());
        out.put("eventId", Long.toString(event.id()));
        JsonNode payload = json.readTree(event.payload());
        payload.properties().forEach(e -> out.set(e.getKey(), e.getValue()));
        return json.writeValueAsString(out);
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    action.run();
                } catch (RuntimeException e) {
                    log.error("Не удалось разослать события прогона", e);
                }
            }
        });
    }
}
