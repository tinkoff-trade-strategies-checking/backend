package ru.tinvest.trainer.sessions;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
 */
@Component
public class SessionEvents {

    private static final Logger log = LoggerFactory.getLogger(SessionEvents.class);

    /** Получатель событий: поток SSE. Ошибка отправки — клиент ушёл, подписка снимается. */
    interface Sink {

        void send(long eventId, String type, String data) throws Exception;

        /** Пустая строка-комментарий потока: проверка, что клиент ещё на связи. */
        void ping() throws Exception;
    }

    /** Подписка на события сессии; {@link #close()} снимает её. */
    final class Subscription {

        private final UUID sessionId;
        private final Sink sink;
        private long lastSent;
        private boolean closed;

        private Subscription(UUID sessionId, Sink sink) {
            this.sessionId = sessionId;
            this.sink = sink;
        }

        /** Отправляет события после последнего отправленного, по порядку номеров. */
        synchronized void drain() {
            if (closed) {
                return;
            }
            try {
                for (Stored event : load(sessionId, lastSent)) {
                    sink.send(event.id(), event.type(), data(event));
                    lastSent = event.id();
                }
            } catch (Exception e) {
                closeAfterFailure();
            }
        }

        synchronized void heartbeat() {
            if (closed) {
                return;
            }
            try {
                sink.ping();
            } catch (Exception e) {
                closeAfterFailure();
            }
        }

        /** Снимает подписку; безопасно вызывать повторно. */
        void close() {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            unsubscribe(this);
        }

        // вызывается под монитором подписки; снятие подписки не ждёт других мониторов
        private void closeAfterFailure() {
            closed = true;
            unsubscribe(this);
        }
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
    private final Map<UUID, List<Subscription>> subscriptions = new ConcurrentHashMap<>();
    private PlayerTimer.@Nullable Handle heartbeat;

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
    }

    @PostConstruct
    void start() {
        heartbeat = timer.every(properties.heartbeat(), () -> {
            for (List<Subscription> list : subscriptions.values()) {
                for (Subscription subscription : list) {
                    subscription.heartbeat();
                }
            }
        });
    }

    @PreDestroy
    void stop() {
        if (heartbeat != null) {
            heartbeat.cancel();
        }
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
     * Подписывает получателя. С {@code lastEventId} сразу отдаются события после него, без — только новые. Номер
     * больше последнего считается последним.
     */
    Subscription subscribe(UUID sessionId, @Nullable Long lastEventId, Sink sink) {
        Subscription subscription = new Subscription(sessionId, sink);
        // сначала регистрация, потом чтение номера: событие между ними не потеряется
        int count;
        synchronized (this) {
            List<Subscription> list = subscriptions.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
            list.add(subscription);
            count = list.size();
        }
        long last = lastEventId(sessionId);
        synchronized (subscription) {
            subscription.lastSent = lastEventId == null ? last : Math.min(lastEventId, last);
        }
        publisher.publishEvent(new SubscribersChanged(sessionId, count));
        subscription.drain();
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
            timer.execute(subscription::drain);
        }
    }

    private List<Stored> load(UUID sessionId, long after) {
        return jdbc.sql("SELECT event_id, type, payload FROM sessions.session_event "
                        + "WHERE session_id = :s AND event_id > :after ORDER BY event_id")
                .param("s", sessionId)
                .param("after", after)
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
