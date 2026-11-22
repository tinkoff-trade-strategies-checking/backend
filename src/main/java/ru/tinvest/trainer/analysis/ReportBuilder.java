package ru.tinvest.trainer.analysis;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.tinvest.trainer.sessions.SessionArchive;
import ru.tinvest.trainer.sessions.SessionEvents;
import ru.tinvest.trainer.sessions.SessionFinished;
import ru.tinvest.trainer.sessions.SessionRecord;
import ru.tinvest.trainer.worker.SimulationWorkerClient;
import ru.tinvest.trainer.worker.WorkerTypes.AlgorithmInput;
import ru.tinvest.trainer.worker.WorkerTypes.DivergenceOutput;
import ru.tinvest.trainer.workshop.StrategyCatalog;
import ru.tinvest.trainer.workshop.StrategyVersionView;

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
    private final StrategyCatalog strategyCatalog;
    private final SimulationWorkerClient worker;
    private final ThreadPoolTaskExecutor executor;
    private final Duration retryInterval;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    /** Когда сборка сессии упала в последний раз (монотонные наносекунды); в памяти, теряется при рестарте. */
    private final Map<UUID, Long> failedAt = new ConcurrentHashMap<>();

    ReportBuilder(
            SessionArchive archive,
            StoredReports store,
            SessionEvents events,
            StrategyCatalog strategyCatalog,
            SimulationWorkerClient worker,
            ReportBuildProperties properties) {
        this.archive = archive;
        this.store = store;
        this.events = events;
        this.strategyCatalog = strategyCatalog;
        this.worker = worker;
        this.retryInterval = properties.retryInterval();
        // свой пул, не бин: бин Executor отключил бы стандартный пул Spring Boot для остальных асинхронных задач
        this.executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.threads());
        executor.setMaxPoolSize(properties.threads());
        executor.setQueueCapacity(properties.queue());
        executor.setThreadNamePrefix("report-build-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
    }

    /** Завершение зафиксировано: сборка уходит в пул, поток завершения не ждёт. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onFinished(SessionFinished event) {
        failedAt.remove(event.sessionId());
        executor.execute(() -> buildGuarded(event.sessionId()));
    }

    /**
     * Подстраховка из {@code GET report}: ставит сборку в пул, если она не падала недавно. Постоянно падающая сборка не
     * гоняет журнал на каждый запрос: повтор не чаще {@code retry-interval} на сессию.
     */
    void buildInBackground(UUID sessionId) {
        Long failed = failedAt.get(sessionId);
        if (failed != null && System.nanoTime() - failed < retryInterval.toNanos()) {
            return;
        }
        executor.execute(() -> buildGuarded(sessionId));
    }

    private void buildGuarded(UUID sessionId) {
        if (!inFlight.add(sessionId)) {
            return;
        }
        try {
            buildAndStore(sessionId);
            failedAt.remove(sessionId);
        } catch (RuntimeException e) {
            failedAt.put(sessionId, System.nanoTime());
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
                    ReportViews.BackgroundAnalysis analysis = analyzeBackground(completed);
                    Map<String, Object> report = ReportViews.report(completed, analysis);
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

    private ReportViews.@Nullable BackgroundAnalysis analyzeBackground(SessionArchive.CompletedReplay completed) {
        SessionRecord session = completed.session();
        UUID algoVersionId = session.algoVersionId();
        if (algoVersionId == null) {
            return null;
        }
        StrategyVersionView algo = strategyCatalog
                .findVersion(algoVersionId)
                .orElseThrow(() -> new IllegalStateException("Версия фонового алгоритма не найдена: " + algoVersionId));
        DivergenceOutput output = worker.analyzeDivergences(
                completed.market(),
                completed.journal(),
                completed.replay().lastStep(),
                List.of(new AlgorithmInput(algo.versionId().toString(), algo.source(), session.algoParams())));
        return new ReportViews.BackgroundAnalysis(algo.strategyName(), output);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
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
