package ru.tinvest.trainer.sessions;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * События сессии для потока SSE. Событие пишется в {@code sessions.session_event} в транзакции команды под блокировкой
 * строки сессии, поэтому номера возрастают в порядке фиксации. Подписчику события отдаются из таблицы по порядку
 * номеров: догон по {@code Last-Event-ID} и живая доставка — один и тот же путь, события не теряются и не меняют
 * порядок. Тело события строит только {@link BlindMasking} (слепая сессия — INV-7) и только по шагам не дальше курсора
 * (INV-1); здесь оно не меняется.
 *
 * <p>Отправка подписчику — одна активная на подписку, на отдельном потоке ({@link PlayerTimer#execute}); уведомления,
 * пришедшие во время отправки, схлопываются в один повторный проход. Пульс потока ничего не ждёт: он только просит
 * подписку послать комментарий и проверяет сторожа — отправка, не завершившаяся за {@code 2 * heartbeat}, обрывает
 * подписку (клиент переподключится с {@code Last-Event-ID}). Медленный клиент не задерживает ни пульс, ни такты.
 */
@Component
public class SessionEvents {

    private static final Logger log = LoggerFactory.getLogger(SessionEvents.class);

    /** Событий за одно чтение таблицы при догоне. */
    private static final int PAGE = 200;

    /** Получатель событий: поток SSE. Ошибка отправки — клиент ушёл, подписка снимается. */
    interface Sink {

        void send(long eventId, String type, String data) throws Exception;

        /** Пустая строка-комментарий потока: проверка, что клиент ещё на связи. */
        void ping() throws Exception;

        /** Обрывает поток зависшего клиента. */
        void abort();
    }

    /** Подписка на события сессии; {@link #close()} снимает её. */
    final class Subscription {

        private final UUID sessionId;
        private final Sink sink;
        /** Номер последнего отправленного события; меняет только активная отправка (и подписка до регистрации). */
        private long lastSent;

        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicBoolean dirty = new AtomicBoolean();
        private final AtomicBoolean pingRequested = new AtomicBoolean();
        private volatile boolean closed;
        /** Время начала текущей отправки по часам плеера; 0 — отправки нет. */
        private volatile long sendingSinceMs;

        private Subscription(UUID sessionId, Sink sink, long lastSent) {
            this.sessionId = sessionId;
            this.sink = sink;
            this.lastSent = lastSent;
        }

        /** Просит отправить новые события; если отправка уже идёт, она сделает ещё один проход. */
        void signal() {
            dirty.set(true);
            startIfIdle();
        }

        private void startIfIdle() {
            if (closed || !running.compareAndSet(false, true)) {
                return;
            }
            try {
                timer.execute(this::run);
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
                            mark(() -> sink.ping());
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
            // сигнал пришёл между последней проверкой и снятием флага
            if ((dirty.get() || pingRequested.get()) && !closed) {
                startIfIdle();
            }
        }

        private void sendNew() throws Exception {
            while (!closed) {
                List<Stored> page = load(sessionId, lastSent, PAGE);
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
            sendingSinceMs = timer.nowMs();
            try {
                send.run();
            } finally {
                sendingSinceMs = 0;
            }
        }

        /** Пульс: просит послать комментарий и снимает подписку, у которой отправка висит дольше предела. */
        void heartbeat(long hangLimitMs) {
            if (closed) {
                return;
            }
            long since = sendingSinceMs;
            if (since != 0 && timer.nowMs() - since > hangLimitMs) {
                abort();
                return;
            }
            pingRequested.set(true);
            startIfIdle();
        }

        private void abort() {
            close();
            try {
                sink.abort();
            } catch (RuntimeException e) {
                log.debug("Поток уже закрыт", e);
            }
        }

        /** Снимает подписку; безопасно вызывать повторно и из любого потока. */
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
                log.debug("Поток уже закрыт", e);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {

        void run() throws Exception;
    }

    private record Stored(long id, String type, String payload) {}

    /** Число подписчиков сессии изменилось (после подписки или отписки). */
    record SubscribersChanged(UUID sessionId, int count) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final PlayerTimer timer;
    private final PlayerProperties properties;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate transaction;
    private final Semaphore sends;
    private final Map<UUID, List<Subscription>> subscriptions = new ConcurrentHashMap<>();
    private PlayerTimer.@Nullable Handle heartbeat;
    private PlayerTimer.@Nullable Handle cleanup;

    SessionEvents(
            JdbcClient jdbc,
            ObjectMapper json,
            PlayerTimer timer,
            PlayerProperties properties,
            ApplicationEventPublisher publisher,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.timer = timer;
        this.properties = properties;
        this.publisher = publisher;
        this.transaction = new TransactionTemplate(transactionManager);
        this.sends = new Semaphore(properties.maxConcurrentSends());
    }

    @PostConstruct
    void start() {
        long hangLimitMs = 2 * properties.heartbeat().toMillis();
        heartbeat = timer.every(properties.heartbeat(), () -> {
            for (List<Subscription> list : subscriptions.values()) {
                for (Subscription subscription : list) {
                    subscription.heartbeat(hangLimitMs);
                }
            }
        });
        cleanup = timer.every(Duration.ofHours(1), () -> {
            try {
                cleanup();
            } catch (RuntimeException e) {
                log.error("Не удалось очистить события завершённых сессий", e);
            }
        });
    }

    @PreDestroy
    void stop() {
        if (heartbeat != null) {
            heartbeat.cancel();
        }
        if (cleanup != null) {
            cleanup.cancel();
        }
    }

    /**
     * Удаляет события завершённых сессий старше срока хранения ({@code lab.session.event-retention}): они нужны только
     * для догона после обрыва, источник состояния — журнал (INV-8). Идущей сессии события не удаляются (триггер БД).
     *
     * @return сколько событий удалено
     */
    int cleanup() {
        return jdbc.sql("DELETE FROM sessions.session_event e USING sessions.session s "
                        + "WHERE e.session_id = s.id AND s.status <> 'running' "
                        + "AND e.created_at < now() - make_interval(secs => :secs)")
                .param("secs", properties.eventRetention().toSeconds())
                .update();
    }

    /** Публикует {@code report_ready}: отчёт сессии готов. Вызывает модуль отчёта. */
    public void reportReady(UUID sessionId) {
        transaction.executeWithoutResult(status -> {
            boolean exists = jdbc.sql("SELECT 1 FROM sessions.session WHERE id = :id FOR UPDATE")
                    .param("id", sessionId)
                    .query(Integer.class)
                    .optional()
                    .isPresent();
            if (!exists) {
                throw new IllegalArgumentException("Сессии нет: " + sessionId);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sessionId", sessionId);
            append(sessionId, "report_ready", payload);
        });
    }

    /**
     * Дописывает событие. Вызывать в транзакции под блокировкой строки сессии: номер выдаётся как следующий за
     * максимальным. Подписчикам событие уходит после фиксации транзакции.
     *
     * @return номер события в сессии
     */
    long append(UUID sessionId, String type, Map<String, Object> payload) {
        long id = jdbc.sql("SELECT coalesce(max(event_id), 0) + 1 FROM sessions.session_event WHERE session_id = :s")
                .param("s", sessionId)
                .query(Long.class)
                .single();
        jdbc.sql("INSERT INTO sessions.session_event (session_id, event_id, type, payload) "
                        + "VALUES (:s, :id, :type, CAST(:payload AS jsonb))")
                .param("s", sessionId)
                .param("id", id)
                .param("type", type)
                .param("payload", json.writeValueAsString(payload))
                .update();
        afterCommit(() -> notifySubscribers(sessionId));
        return id;
    }

    /** Последний номер события сессии; 0 — событий не было. */
    long lastEventId(UUID sessionId) {
        return jdbc.sql("SELECT coalesce(max(event_id), 0) FROM sessions.session_event WHERE session_id = :s")
                .param("s", sessionId)
                .query(Long.class)
                .single();
    }

    /**
     * Подписывает получателя. С {@code lastEventId} отдаются события после него, без — только новые. Номер больше
     * последнего считается последним. Начальный номер выставляется до регистрации подписки, поэтому событие, записанное
     * в этот момент, не вызывает отправку всей истории.
     */
    Subscription subscribe(UUID sessionId, @Nullable Long lastEventId, Sink sink) {
        long last = lastEventId(sessionId);
        Subscription subscription =
                new Subscription(sessionId, sink, lastEventId == null ? last : Math.min(lastEventId, last));
        int count;
        synchronized (this) {
            List<Subscription> list = subscriptions.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
            list.add(subscription);
            count = list.size();
        }
        publisher.publishEvent(new SubscribersChanged(sessionId, count));
        // события, записанные после чтения номера, и догон по Last-Event-ID — из таблицы
        subscription.signal();
        return subscription;
    }

    int subscribers(UUID sessionId) {
        List<Subscription> list = subscriptions.get(sessionId);
        return list == null ? 0 : list.size();
    }

    private void unsubscribe(Subscription subscription) {
        int count;
        synchronized (this) {
            List<Subscription> list = subscriptions.get(subscription.sessionId);
            if (list == null || !list.remove(subscription)) {
                return;
            }
            count = list.size();
            if (count == 0) {
                subscriptions.remove(subscription.sessionId);
            }
        }
        publisher.publishEvent(new SubscribersChanged(subscription.sessionId, count));
    }

    private void notifySubscribers(UUID sessionId) {
        List<Subscription> list = subscriptions.get(sessionId);
        if (list == null) {
            return;
        }
        for (Subscription subscription : list) {
            subscription.signal();
        }
    }

    private List<Stored> load(UUID sessionId, long after, int limit) {
        return jdbc.sql("SELECT event_id, type, payload FROM sessions.session_event "
                        + "WHERE session_id = :s AND event_id > :after ORDER BY event_id LIMIT :limit")
                .param("s", sessionId)
                .param("after", after)
                .param("limit", limit)
                .query((rs, n) -> new Stored(rs.getLong("event_id"), rs.getString("type"), rs.getString("payload")))
                .list();
    }

    /** Тело SSE-события: {@code type}, {@code eventId} и поля события. */
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
                    log.error("Не удалось разослать события сессии", e);
                }
            }
        });
    }
}
