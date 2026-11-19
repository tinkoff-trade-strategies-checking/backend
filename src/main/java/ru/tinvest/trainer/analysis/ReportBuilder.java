package ru.tinvest.trainer.analysis;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.tinvest.trainer.sessions.SessionArchive;
import ru.tinvest.trainer.sessions.SessionFinished;

/**
 * Отчёт строится в фоне после завершения сессии и сохраняется (решение «Отчёт и история: реализация»): журнал
 * проигрывает воркер ({@code include_report}), отчёт пишется в {@code analysis.session_report}. Пока отчёта нет —
 * {@code BUILDING}. Сборка идёт по событию {@link SessionFinished} после фиксации завершения; не вышла (воркер
 * недоступен) — следующий {@code GET report} запускает её снова. В фоновом потоке нет вошедшего пользователя:
 * идентификатор сессии приходит из системы, не от клиента.
 */
@Service
public class ReportBuilder {

    private static final Logger log = LoggerFactory.getLogger(ReportBuilder.class);

    private final SessionArchive archive;
    private final StoredReports store;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    ReportBuilder(SessionArchive archive, StoredReports store) {
        this.archive = archive;
        this.store = store;
    }

    /** Завершение зафиксировано: строим отчёт, не задерживая ответ завершения. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onFinished(SessionFinished event) {
        buildInBackground(event.sessionId());
    }

    /** Запускает сборку в фоне, если для сессии она ещё не идёт. */
    void buildInBackground(UUID sessionId) {
        if (!inFlight.add(sessionId)) {
            return;
        }
        executor.execute(() -> {
            try {
                buildAndStore(sessionId);
            } catch (RuntimeException e) {
                // в логе только идентификатор и тип ошибки (данные слепой сессии не пишем); повтор — следующим запросом
                log.warn(
                        "Отчёт сессии {} не построен: {}",
                        sessionId,
                        e.getClass().getSimpleName());
            } finally {
                inFlight.remove(sessionId);
            }
        });
    }

    /**
     * Строит и сохраняет отчёт завершённой сессии; повтор ничего не меняет (сохранённый отчёт неизменяем).
     *
     * @return {@code true}, если отчёт сохранён сейчас или уже был; {@code false} — сессии нет или она ещё идёт
     */
    public boolean buildAndStore(UUID sessionId) {
        if (store.find(sessionId).isPresent()) {
            return true;
        }
        return archive.completed(sessionId)
                .map(completed -> {
                    Map<String, Object> report = ReportViews.report(completed);
                    store.insert(
                            sessionId,
                            report,
                            completed.playedDays(),
                            ReportViews.netReturnPct(completed.replay().report()));
                    return true;
                })
                .orElse(false);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
