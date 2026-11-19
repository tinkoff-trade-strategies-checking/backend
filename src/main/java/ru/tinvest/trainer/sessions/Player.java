package ru.tinvest.trainer.sessions;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.tinvest.trainer.api.DependencyUnavailableException;
import ru.tinvest.trainer.worker.SimulationWorkerClient;
import ru.tinvest.trainer.worker.WorkerTypes.JournalRecord;
import ru.tinvest.trainer.worker.WorkerTypes.ReplayResult;
import ru.tinvest.trainer.worker.WorkerTypes.RunState;

/**
 * Плеер: виртуальное время ручной сессии, которое ведёт сервер ({@code decisions.md}, «Плеер»). На ×1 идёт одна свеча
 * в секунду реального времени, на ×5, ×10, ×100 — пять, десять, сто. Курсор ({@code current_step}) двигают только такты
 * этого класса и команда {@code step}, оба под блокировкой строки сессии, поэтому заявки арены видят согласованный
 * курсор. Состояние плеера хранится в БД, таймеры — только в памяти процесса: после перезапуска сессии стоят на паузе
 * (призрачных таймеров нет). Событие каждого изменения пишется в {@link SessionEvents} в той же транзакции.
 *
 * <p>Пауза без подписчиков SSE дольше {@code lab.session.disconnect-pause-after} и сбой воркера в такте ставят игру на
 * паузу. Конец периода и банкротство (воркер вернул не {@code RUNNING}) завершают сессию.
 */
@Service
class Player {

    private static final Logger log = LoggerFactory.getLogger(Player.class);

    private static final String STATUS_RUNNING = "running";

    /** Таймеры идущей игры одной сессии. */
    private static final class Running {

        final int speed;
        PlayerTimer.@Nullable Handle tick;
        PlayerTimer.@Nullable Handle idle;
        long lastTickMs;
        /** Накопленные «свечо-миллисекунды»: тысяча — одна свеча. */
        long credit;

        Running(int speed, long nowMs) {
            this.speed = speed;
            this.lastTickMs = nowMs;
        }

        synchronized void cancel() {
            if (tick != null) {
                tick.cancel();
            }
            cancelIdle();
        }

        synchronized void cancelIdle() {
            if (idle != null) {
                idle.cancel();
                idle = null;
            }
        }
    }

    private final Sessions sessions;
    private final Arena arena;
    private final ArenaJournal journals;
    private final CommandRunner commands;
    private final PlayerStates players;
    private final SessionEvents events;
    private final SimulationWorkerClient worker;
    private final BlindMasking masking;
    private final PlayerTimer timer;
    private final PlayerProperties properties;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate transaction;
    private final Map<UUID, Running> running = new ConcurrentHashMap<>();

    Player(
            Sessions sessions,
            Arena arena,
            ArenaJournal journals,
            CommandRunner commands,
            PlayerStates players,
            SessionEvents events,
            SimulationWorkerClient worker,
            BlindMasking masking,
            PlayerTimer timer,
            PlayerProperties properties,
            ApplicationEventPublisher publisher,
            PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.arena = arena;
        this.journals = journals;
        this.commands = commands;
        this.players = players;
        this.events = events;
        this.worker = worker;
        this.masking = masking;
        this.timer = timer;
        this.properties = properties;
        this.publisher = publisher;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    // ---------- команды ----------

    /** {@code POST .../player}: play, pause, step, speed. */
    Object control(UUID sessionId, @Nullable UUID key, PlayerCommand command) {
        PlayerCommand.Valid valid = command.validate();
        return commands.run(sessionId, key, "player", valid.fingerprint(), session -> control(session, valid));
    }

    private Map<String, Object> control(SessionRecord session, PlayerCommand.Valid command) {
        if (!STATUS_RUNNING.equals(session.status())) {
            throw SessionConflictException.notActive();
        }
        UUID id = session.id();
        PlayerStates.Row row = players.get(id);
        List<JournalRecord> journal = journals.records(session);
        ReplayResult probe = replay(session, journal, session.currentStep());
        if (row.totalSteps().isEmpty()) {
            players.setTotalSteps(id, probe.totalSteps());
        }
        boolean onLastStep = probe.state() != RunState.RUNNING || session.currentStep() >= probe.totalSteps() - 1;
        switch (command.action()) {
            case PLAY -> {
                if (onLastStep) {
                    throw SessionConflictException.playerState("The session is on its last step");
                }
                if (!row.playing()) {
                    players.setPlaying(id, true);
                    events.append(id, "player", masking.playerEvent(true, row.speed()));
                    int speed = row.speed();
                    afterCommit(() -> startRunning(id, speed));
                }
            }
            case PAUSE -> {
                if (row.playing()) {
                    players.setPlaying(id, false);
                    pausedEvents(id, row.speed(), "user");
                    afterCommit(() -> stopRunning(id));
                }
            }
            case STEP -> {
                if (row.playing()) {
                    throw SessionConflictException.playerState("The player is playing");
                }
                if (onLastStep) {
                    throw SessionConflictException.playerState("The session is on its last step");
                }
                Advance advance = advance(session, journal, 1, probe.totalSteps());
                return masking.playerState(advance.state(), advance.stepResult());
            }
            case SPEED -> {
                if (row.speed() != command.speed()) {
                    players.setSpeed(id, command.speed());
                    events.append(id, "player", masking.playerEvent(row.playing(), command.speed()));
                    if (row.playing()) {
                        int speed = command.speed();
                        afterCommit(() -> startRunning(id, speed));
                    }
                }
            }
        }
        return masking.playerState(arena.state(sessions.reload(id), probe, journals.seenSteps(id)), null);
    }

    /**
     * {@code POST .../finish}: досрочное завершение. Уже завершённая сессия — текущий итог. Воркер недоступен — исключение,
     * транзакция откатывается, сессия остаётся активной.
     */
    Object finish(UUID sessionId) {
        return commands.run(sessionId, null, "finish", "", this::finish);
    }

    private Map<String, Object> finish(SessionRecord session) {
        UUID id = session.id();
        List<JournalRecord> journal = journals.records(session);
        ReplayResult replay = replay(session, journal, session.currentStep());
        if (!STATUS_RUNNING.equals(session.status())) {
            return masking.finishResult(
                    arena.state(session, replay, journals.seenSteps(id)), endReason(session.status()));
        }
        String status = switch (replay.state()) {
            case FINISHED_END -> "completed";
            case BANKRUPT -> "bankrupt";
            case RUNNING -> "finished_early";
        };
        return complete(session, replay, status, null).finishResult();
    }

    // ---------- такты ----------

    private void startRunning(UUID sessionId, int speed) {
        Running next = new Running(speed, timer.nowMs());
        Running previous = running.put(sessionId, next);
        if (previous != null) {
            previous.cancel();
        }
        synchronized (next) {
            next.tick = timer.every(properties.tickPeriod(speed), () -> tick(sessionId));
            if (events.subscribers(sessionId) == 0) {
                next.idle = timer.after(properties.disconnectPauseAfter(), () -> pauseIfNoSubscribers(sessionId));
            }
        }
    }

    private void stopRunning(UUID sessionId) {
        Running previous = running.remove(sessionId);
        if (previous != null) {
            previous.cancel();
        }
    }

    private void tick(UUID sessionId) {
        Running run = running.get(sessionId);
        if (run == null) {
            return;
        }
        long now = timer.nowMs();
        long period = properties.tickPeriod(run.speed).toMillis();
        // долгий такт не копит очередь: догоняем не больше двух периодов
        long elapsed = Math.min(now - run.lastTickMs, 2 * period);
        run.lastTickMs = now;
        run.credit += elapsed * run.speed;
        int candles = (int) (run.credit / 1000);
        run.credit %= 1000;
        if (candles < 1) {
            return;
        }
        try {
            transaction.executeWithoutResult(status -> tickStep(sessionId, candles));
        } catch (DependencyUnavailableException e) {
            log.warn("Воркер недоступен в такте плеера, сессия {} ставится на паузу", sessionId);
            pause(sessionId, "worker_failure");
        } catch (RuntimeException e) {
            log.error("Сбой такта плеера, сессия {} ставится на паузу", sessionId, e);
            pause(sessionId, "worker_failure");
        }
    }

    private void tickStep(UUID sessionId, int candles) {
        SessionRecord session = sessions.lockById(sessionId).orElse(null);
        if (session == null || !STATUS_RUNNING.equals(session.status())) {
            afterCommit(() -> stopRunning(sessionId));
            return;
        }
        PlayerStates.Row row = players.get(sessionId);
        if (!row.playing() || row.totalSteps().isEmpty()) {
            afterCommit(() -> stopRunning(sessionId));
            return;
        }
        advance(session, journals.records(session), candles, row.totalSteps().getAsLong());
    }

    @EventListener
    void onSubscribersChanged(SessionEvents.SubscribersChanged changed) {
        Running run = running.get(changed.sessionId());
        if (run == null) {
            return;
        }
        synchronized (run) {
            if (changed.count() > 0) {
                run.cancelIdle();
            } else if (run.idle == null) {
                UUID id = changed.sessionId();
                run.idle = timer.after(properties.disconnectPauseAfter(), () -> pauseIfNoSubscribers(id));
            }
        }
    }

    private void pauseIfNoSubscribers(UUID sessionId) {
        if (events.subscribers(sessionId) == 0) {
            pause(sessionId, "disconnect");
        }
    }

    /** Ставит игру на паузу по причине системы ({@code disconnect}, {@code worker_failure}); пауза уже есть — ничего. */
    private void pause(UUID sessionId, String reason) {
        stopRunning(sessionId);
        transaction.executeWithoutResult(status -> {
            SessionRecord session = sessions.lockById(sessionId).orElse(null);
            if (session == null) {
                return;
            }
            PlayerStates.Row row = players.get(sessionId);
            if (row.playing()) {
                players.setPlaying(sessionId, false);
                pausedEvents(sessionId, row.speed(), reason);
            }
        });
    }

    /** После перезапуска процесса таймеров нет: игра на паузе, об этом знают и вкладки, догоняющие по Last-Event-ID. */
    @EventListener(ApplicationReadyEvent.class)
    void pauseAfterRestart() {
        for (UUID sessionId : players.playing()) {
            transaction.executeWithoutResult(status -> {
                if (sessions.lockById(sessionId).isEmpty()) {
                    return;
                }
                PlayerStates.Row row = players.get(sessionId);
                if (row.playing()) {
                    players.setPlaying(sessionId, false);
                    events.append(sessionId, "player", masking.playerEvent(false, row.speed()));
                }
            });
        }
    }

    // ---------- шаги и завершение ----------

    /** Итог завершения: состояние на курсоре, итог сессии и результат последнего шага (если шаг пройден). */
    private record Completed(
            ArenaState state,
            Map<String, Object> finishResult,
            @Nullable Map<String, Object> stepResult) {}

    /** Итог продвижения курсора: состояние на новом курсоре и результат шага. */
    private record Advance(ArenaState state, Map<String, Object> stepResult) {}

    /**
     * Двигает курсор на {@code candles} шагов (не дальше последнего; банкротство останавливает раньше) под блокировкой
     * сессии, пишет событие {@code step} и, если игра кончилась, завершает сессию.
     */
    private Advance advance(SessionRecord session, List<JournalRecord> journal, int candles, long totalSteps) {
        UUID id = session.id();
        long from = session.currentStep();
        long target = Math.min(from + candles, totalSteps - 1);
        ReplayResult replay = replay(session, journal, target);
        long step = Math.max(replay.lastStep(), from);
        sessions.advance(id, step);
        SessionRecord moved = sessions.reload(id);
        String endStatus = switch (replay.state()) {
            case FINISHED_END -> "completed";
            case BANKRUPT -> "bankrupt";
            case RUNNING -> step >= totalSteps - 1 ? "completed" : null;
        };
        if (endStatus != null) {
            Completed completed = complete(moved, replay, endStatus, from);
            return new Advance(completed.state(), completed.stepResult());
        }
        ArenaState state = arena.state(moved, replay, journals.seenSteps(id));
        Map<String, Object> result = masking.stepResult(state, from);
        events.append(id, "step", masking.stepEvent(result));
        return new Advance(state, result);
    }

    /**
     * Завершает сессию: статус, остановка игры, события {@code step} (если шаг только что пройден), {@code player} и
     * {@code finished}. Раскрытие слепой сессии — следствие статуса ({@link SessionRecord#revealed()}): отчёт и
     * воспроизведение отдают реальные данные только после него.
     *
     * @param fromStep шаг до продвижения; {@code null} — продвижения не было (досрочное завершение)
     */
    private Completed complete(SessionRecord session, ReplayResult replay, String status, @Nullable Long fromStep) {
        UUID id = session.id();
        sessions.finish(id, status);
        PlayerStates.Row row = players.get(id);
        if (row.playing()) {
            players.setPlaying(id, false);
        }
        SessionRecord ended = sessions.reload(id);
        ArenaState state = arena.state(ended, replay, journals.seenSteps(id));
        Map<String, Object> stepResult = fromStep == null ? null : masking.stepResult(state, fromStep);
        if (stepResult != null) {
            events.append(id, "step", masking.stepEvent(stepResult));
        }
        if (row.playing()) {
            events.append(id, "player", masking.playerEvent(false, row.speed()));
        }
        Map<String, Object> result = masking.finishResult(state, endReason(status));
        events.append(id, "finished", masking.finishedEvent(result));
        publisher.publishEvent(new SessionFinished(id));
        afterCommit(() -> stopRunning(id));
        return new Completed(state, result, stepResult);
    }

    private void pausedEvents(UUID sessionId, int speed, String reason) {
        events.append(sessionId, "player", masking.playerEvent(false, speed));
        events.append(sessionId, "paused", masking.pausedEvent(reason));
    }

    private ReplayResult replay(SessionRecord session, List<JournalRecord> journal, long upToStep) {
        return worker.replaySession(Arena.market(session), journal, upToStep, true, session.blind());
    }

    private static String endReason(String status) {
        return switch (status) {
            case "completed" -> "period_end";
            case "finished_early" -> "early";
            case "bankrupt" -> "bankrupt";
            default -> throw new IllegalStateException("Неизвестный статус завершённой сессии: " + status);
        };
    }

    /** После фиксации транзакции (откат отменяет действие); вне транзакции — сразу. */
    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
