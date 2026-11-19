package ru.tinvest.trainer.analysis;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.tinvest.trainer.sessions.SessionArchive;
import ru.tinvest.trainer.sessions.SessionEvents;
import ru.tinvest.trainer.sessions.SessionFinished;

/**
 * Отчёт строится в фоне после завершения сессии и сохраняется (решение «Отчёт и история: реализация»): журнал
 * проигрывает воркер ({@code include_report}), отчёт пишется в {@code analysis.session_report}, затем в поток событий
 * уходит {@code report_ready}. Пока отчёта нет — {@code BUILDING}. Сборка идёт по событию {@link SessionFinished} после
 * фиксации завершения в ограниченном пуле {@code reportBuildExecutor}. Событие без {@code spring-modulith-events} при
 * падении процесса между фиксацией и сборкой теряется; не вышла сборка и по другой причине (воркер недоступен) — отчёт
 * строит следующий {@code GET report}, пока его нет. В фоновом потоке нет вошедшего пользователя: идентификатор сессии
 * приходит из системы, не от клиента.
 */
@Service
public class ReportBuilder {

    private static final Logger log = LoggerFactory.getLogger(ReportBuilder.class);

    private final SessionArchive archive;
    private final StoredReports store;
    private final SessionEvents events;
    private final Executor executor;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    ReportBuilder(
            SessionArchive archive,
            StoredReports store,
            SessionEvents events,
            @Qualifier("reportBuildExecutor") Executor executor) {
        this.archive = archive;
        this.store = store;
        this.events = events;
        this.executor = executor;
    }

    /** Завершение зафиксировано: строим отчёт в пуле, не задерживая поток завершения. */
    @Async("reportBuildExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFinished(SessionFinished event) {
        buildGuarded(event.sessionId());
    }

    /** Ставит сборку в пул, если для сессии она ещё не идёт (подстраховка из {@code GET report}). */
    void buildInBackground(UUID sessionId) {
        executor.execute(() -> buildGuarded(sessionId));
    }

    private void buildGuarded(UUID sessionId) {
        if (!inFlight.add(sessionId)) {
            return;
        }
        try {
            buildAndStore(sessionId);
        } catch (RuntimeException e) {
            // в логе только идентификатор и тип ошибки (данные слепой сессии не пишем); повтор — следующим запросом
            log.warn("Отчёт сессии {} не построен: {}", sessionId, e.getClass().getSimpleName());
        } finally {
            inFlight.remove(sessionId);
        }
    }

    /**
     * Строит и сохраняет отчёт завершённой сессии; повтор ничего не меняет (сохранённый отчёт неизменяем). Записанный
     * сейчас отчёт объявляется событием {@code report_ready}.
     *
     * @return {@code true}, если отчёт сохранён сейчас или уже был; {@code false} — сессии нет или она ещё идёт
     */
    public boolean buildAndStore(UUID sessionId) {
        if (store.find(sessionId).isPresent()) {
            return true;
        }
        boolean stored = archive.completed(sessionId)
                .map(completed -> {
                    Map<String, Object> report = ReportViews.report(completed);
                    return store.insert(
                            sessionId,
                            report,
                            completed.playedDays(),
                            ReportViews.netReturnPct(completed.replay().report()));
                })
                .orElse(false);
        if (stored) {
            announce(sessionId);
        }
        return stored || store.find(sessionId).isPresent();
    }

    /** Отчёт уже сохранён; не вышло объявить — клиент увидит его при следующем чтении, события не повторяем. */
    private void announce(UUID sessionId) {
        try {
            events.reportReady(sessionId);
        } catch (RuntimeException e) {
            log.warn(
                    "report_ready сессии {} не опубликован: {}",
                    sessionId,
                    e.getClass().getSimpleName());
        }
    }
}
