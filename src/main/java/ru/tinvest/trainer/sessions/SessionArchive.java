package ru.tinvest.trainer.sessions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.tinvest.trainer.api.NotFoundException;
import ru.tinvest.trainer.identity.CurrentUser;
import ru.tinvest.trainer.marketdata.Regime;
import ru.tinvest.trainer.worker.SimulationWorkerClient;
import ru.tinvest.trainer.worker.WorkerTypes.ReplayResult;

/**
 * Публичный вход модуля {@code sessions} для отчёта и истории ({@code analysis}): сессии текущего пользователя в виде
 * {@link SessionLookup}. Финансовых расчётов нет — только журнал, проигранный воркером (INV-8).
 */
@Service
public class SessionArchive {

    private final Sessions sessions;
    private final ArenaJournal journals;
    private final SimulationWorkerClient worker;
    private final PlayerStates players;
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;

    SessionArchive(
            Sessions sessions,
            ArenaJournal journals,
            SimulationWorkerClient worker,
            PlayerStates players,
            JdbcClient jdbc,
            CurrentUser currentUser) {
        this.sessions = sessions;
        this.journals = journals;
        this.worker = worker;
        this.players = players;
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    /**
     * Фильтры истории сессий; {@code status} — значение {@code HistoryStatus} контракта (совпадает со статусом сессии).
     */
    public record HistoryFilter(
            boolean blindOnly,
            @Nullable String status,
            @Nullable Regime regime,
            int page,
            int pageSize) {}

    /** Страница истории: записи по дате создания, новые сверху, и общее число подходящих сессий. */
    public record HistoryPage(List<SessionLookup> items, long total) {}

    /** Состояние сессии пользователя: нет или чужая — {@link SessionLookup.Missing}. */
    public SessionLookup lookup(UUID sessionId) {
        SessionRecord session;
        try {
            session = sessions.requireOwned(sessionId);
        } catch (NotFoundException e) {
            return new SessionLookup.Missing();
        }
        return resolve(session);
    }

    /** Страница истории сессий текущего пользователя. */
    public HistoryPage history(HistoryFilter filter) {
        UUID userId = currentUser.requireId();
        StringBuilder where = new StringBuilder(" WHERE user_id = :user");
        if (filter.blindOnly()) {
            where.append(" AND blind");
        }
        if (filter.status() != null) {
            where.append(" AND status = :status");
        }
        if (filter.regime() != null) {
            where.append(" AND regime = :regime");
        }
        long total = bind(jdbc.sql("SELECT count(*) FROM sessions.session" + where), userId, filter)
                .query(Long.class)
                .single();
        List<UUID> ids = bind(
                        jdbc.sql("SELECT id FROM sessions.session" + where
                                + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset"),
                        userId,
                        filter)
                .param("limit", filter.pageSize())
                .param("offset", (long) (filter.page() - 1) * filter.pageSize())
                .query(UUID.class)
                .list();
        List<SessionLookup> items = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            items.add(resolve(sessions.requireOwned(id)));
        }
        return new HistoryPage(items, total);
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, UUID userId, HistoryFilter filter) {
        spec.param("user", userId);
        if (filter.status() != null) {
            spec.param("status", filter.status());
        }
        if (filter.regime() != null) {
            spec.param("regime", filter.regime().code());
        }
        return spec;
    }

    private SessionLookup resolve(SessionRecord session) {
        return "running".equals(session.status()) ? unfinished(session) : new SessionLookup.Finished(session);
    }

    /** Идущая сессия: только номер дня на курсоре — лёгкий вызов воркера без журнала, истории баров и отчёта. */
    SessionLookup.Unfinished unfinished(SessionRecord session) {
        return new SessionLookup.Unfinished(session, playedDays(session));
    }

    /**
     * Номер торгового дня на курсоре. Основной путь — день, который плеер запомнил в {@code player_state.cursor_day} на
     * шаге курсора, без воркера (история не зависит от доступности ядра и данных снапшота). Запасной — строки нет или
     * она на другом шаге: {@code current_day} ядра без истории и отчёта, журнал не нужен.
     */
    public int playedDays(SessionRecord session) {
        PlayerStates.Row row = players.get(session.id());
        if (row.cursorStep().isPresent() && row.cursorStep().getAsLong() == session.currentStep()) {
            return row.cursorDay();
        }
        return worker.tradingDaysAt(Arena.market(session), session.currentStep());
    }

    /** Завершённая сессия с полным воспроизведением: вход фоновой сборки отчёта. */
    public record CompletedReplay(SessionRecord session, ReplayResult replay, int playedDays) {}

    /**
     * Завершённая сессия по идентификатору из события завершения, без проверки владельца: только для фоновой сборки
     * отчёта ({@code analysis}), идентификатор приходит из системы, не от клиента. Журнал проигрывается целиком до
     * итогового шага с историей баров и отчётом ядра.
     *
     * @return пусто, если сессии нет или она ещё идёт
     */
    public Optional<CompletedReplay> completed(UUID sessionId) {
        return sessions.findAny(sessionId)
                .filter(s -> !"running".equals(s.status()))
                .map(session -> {
                    ReplayResult replay = worker.replayWithReport(
                            Arena.market(session), journals.records(session), session.currentStep());
                    if (replay.report() == null) {
                        throw new IllegalStateException("Воркер не вернул отчёт завершённой сессии " + session.id());
                    }
                    return new CompletedReplay(session, replay, replay.currentDay());
                });
    }
}
