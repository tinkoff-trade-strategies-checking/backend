package ru.tinvest.trainer.sessions;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
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
import ru.tinvest.trainer.worker.WorkerTypes.StepBars;
import ru.tinvest.trainer.worker.WorkerTypes.StepReplay;

/**
 * Плеер: виртуальное время ручной сессии, которое ведёт сервер ({@code decisions.md}, «Плеер»). На ×1 идёт одна свеча
 * в секунду реального времени, на ×5, ×10, ×100 — пять, десять, сто. Курсор ({@code current_step}) двигают только такты
 * этого класса и команда {@code step}, оба под блокировкой строки сессии, поэтому заявки арены видят согласованный
 * курсор. Состояние плеера хранится в БД, таймеры — только в памяти процесса: после перезапуска сессии стоят на паузе
 * (призрачных таймеров нет). Событие каждого изменения пишется в {@link SessionEvents} в той же транзакции.
 *
 * <p>Такт сессии идёт на отдельном потоке и не ждёт тактов других сессий: планировщик только запускает такт, а если
 * предыдущий такт этой сессии ещё идёт, пропускает; одновременных тактов не больше {@code lab.session.max-concurrent-ticks}.
 * Таймер привязан к экземпляру {@link Running}: снимает и ставит его только код, который знает этот экземпляр, поэтому
 * запоздавший после коммита снятие не трогает таймер следующего {@code play}. Если состояние в БД и таймер всё же
 * разошлись, такт сам перечитывает {@code playing} под блокировкой и снимает лишний таймер.
 *
 * <p>Команды {@code pause}, {@code speed} и {@code play} воркера не вызывают, кроме самого первого раза у сессии
 * (число шагов шкалы и день курсора запоминаются в {@code player_state}); воркер нужен {@code step} и тактам. Пауза без
 * подписчиков SSE дольше {@code lab.session.disconnect-pause-after} и сбой воркера в такте ставят игру на паузу. Конец
 * периода и банкротство (воркер вернул не {@code RUNNING}) завершают сессию.
 */
@Service
class Player {

    private static final Logger log = LoggerFactory.getLogger(Player.class);

    private static final String STATUS_RUNNING = "running";

    /** Таймеры идущей игры одной сессии. Поля такта меняет только поток такта, он один на экземпляр (флаг {@code busy}). */
    private static final class Running {

        final int speed;
        final AtomicBoolean busy = new AtomicBoolean();
        volatile PlayerTimer.@Nullable Handle tick;
        long lastTickMs;
        /** Накопленные «свечо-миллисекунды»: тысяча — одна свеча. */
        long credit;
        /** С какого момента нет подписчиков SSE; {@code -1} — подписчик есть. */
        long noSubscribersSinceMs;

        Running(int speed, long nowMs, boolean noSubscribers) {
            this.speed = speed;
            this.lastTickMs = nowMs;
            this.noSubscribersSinceMs = noSubscribers ? nowMs : -1;
        }

        void cancel() {
            PlayerTimer.Handle handle = tick;
            if (handle != null) {
                handle.cancel();
            }
        }
    }

    /** День и номер свечи в дне на шаге курсора. */
    private record Position(int day, int candle) {}

    /** Итог продвижения курсора: сессия на новом курсоре, день курсора и результат шага. */
    private record Advance(SessionRecord session, int day, Map<String, Object> stepResult) {}

    /** Итог завершения: итог сессии и результат последнего шага (если шаг пройден). */
    private record Completed(
            Map<String, Object> finishResult, @Nullable Map<String, Object> stepResult) {}

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
    private final Semaphore ticks;
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
        this.ticks = new Semaphore(properties.maxConcurrentTicks());
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
        switch (command.action()) {
            case PLAY -> {
                long total = totalSteps(session, row);
                if (session.currentStep() >= total - 1) {
                    throw SessionConflictException.playerState("The session is on its last step");
                }
                int speed = row.speed();
                if (!row.playing()) {
                    players.setPlaying(id, true);
                    events.append(id, "player", masking.playerEvent(true, speed));
                    afterCommit(() -> startRunning(id, speed));
                } else if (!running.containsKey(id)) {
                    // в БД «играет», а таймера нет: перезапускаем
                    afterCommit(() -> startRunning(id, speed));
                }
            }
            case PAUSE -> {
                if (row.playing()) {
                    players.setPlaying(id, false);
                    pausedEvents(id, row.speed(), "user");
                }
                Running seen = running.get(id);
                afterCommit(() -> stop(id, seen));
            }
            case STEP -> {
                if (row.playing()) {
                    throw SessionConflictException.playerState("The player is playing");
                }
                long total = totalSteps(session, row);
                if (session.currentStep() >= total - 1) {
                    throw SessionConflictException.playerState("The session is on its last step");
                }
                Advance advance = advance(session, journals.records(session), 1, total, row);
                return masking.playerState(advance.session(), players.get(id), advance.day(), advance.stepResult());
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
        int day = cursor(session, row).day();
        return masking.playerState(session, players.get(id), day, null);
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
        PlayerStates.Row row = players.get(id);
        List<JournalRecord> journal = journals.records(session);
        ReplayResult replay =
                worker.replaySession(Arena.market(session), journal, session.currentStep(), false, session.blind());
        int day = cursor(session, row).day();
        if (!STATUS_RUNNING.equals(session.status())) {
            return masking.finishResult(
                    arena.state(session, replay, journals.seenSteps(id)), endReason(session.status()), day);
        }
        String status = switch (replay.state()) {
            case FINISHED_END -> "completed";
            case BANKRUPT -> "bankrupt";
            case RUNNING -> "finished_early";
        };
        return complete(session, replay, status, null, day).finishResult();
    }

    // ---------- такты ----------

    private void startRunning(UUID sessionId, int speed) {
        Running next = new Running(speed, timer.nowMs(), events.subscribers(sessionId) == 0);
        Running previous = running.put(sessionId, next);
        if (previous != null) {
            previous.cancel();
        }
        next.tick = timer.every(properties.tickPeriod(speed), () -> onTimer(sessionId, next));
        // экземпляр успели вытеснить, пока ставился таймер
        if (running.get(sessionId) != next) {
            next.cancel();
        }
    }

    /** Снимает таймер {@code run}, если он ещё текущий; таймер более нового {@code play} не трогает. */
    private void stop(UUID sessionId, @Nullable Running run) {
        if (run != null && running.remove(sessionId, run)) {
            run.cancel();
        }
    }

    /** Вызов планировщика: запускает такт на отдельном потоке; пока предыдущий такт сессии идёт, пропускает. */
    private void onTimer(UUID sessionId, Running run) {
        if (running.get(sessionId) != run) {
            run.cancel();
            return;
        }
        if (!run.busy.compareAndSet(false, true)) {
            return;
        }
        try {
            timer.execute(() -> {
                ticks.acquireUninterruptibly();
                try {
                    tick(sessionId, run);
                } finally {
                    ticks.release();
                    run.busy.set(false);
                }
            });
        } catch (RuntimeException e) {
            run.busy.set(false);
            throw e;
        }
    }

    private void tick(UUID sessionId, Running run) {
        if (running.get(sessionId) != run) {
            return;
        }
        long now = timer.nowMs();
        // без подписчиков дольше срока — пауза; число подписчиков читаем сами, порядок событий о нём не важен
        if (events.subscribers(sessionId) == 0) {
            if (run.noSubscribersSinceMs < 0) {
                run.noSubscribersSinceMs = now;
            }
            if (now - run.noSubscribersSinceMs
                    >= properties.disconnectPauseAfter().toMillis()) {
                pause(sessionId, run, "disconnect");
                return;
            }
        } else {
            run.noSubscribersSinceMs = -1;
        }
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
            transaction.executeWithoutResult(status -> tickStep(sessionId, run, candles));
        } catch (DependencyUnavailableException e) {
            log.warn("Воркер недоступен в такте плеера, сессия {} ставится на паузу", sessionId);
            pause(sessionId, run, "worker_failure");
        } catch (RuntimeException e) {
            log.error("Сбой такта плеера, сессия {} ставится на паузу", sessionId, e);
            pause(sessionId, run, "worker_failure");
        }
    }

    private void tickStep(UUID sessionId, Running run, int candles) {
        SessionRecord session = sessions.lockById(sessionId).orElse(null);
        if (session == null || !STATUS_RUNNING.equals(session.status())) {
            afterCommit(() -> stop(sessionId, run));
            return;
        }
        PlayerStates.Row row = players.get(sessionId);
        if (!row.playing() || row.totalSteps().isEmpty()) {
            afterCommit(() -> stop(sessionId, run));
            return;
        }
        advance(session, journals.records(session), candles, row.totalSteps().getAsLong(), row);
    }

    /** Ставит игру на паузу по причине системы ({@code disconnect}, {@code worker_failure}); пауза уже есть — ничего. */
    private void pause(UUID sessionId, Running run, String reason) {
        transaction.executeWithoutResult(status -> {
            SessionRecord session = sessions.lockById(sessionId).orElse(null);
            if (session != null) {
                PlayerStates.Row row = players.get(sessionId);
                if (row.playing()) {
                    players.setPlaying(sessionId, false);
                    pausedEvents(sessionId, row.speed(), reason);
                }
            }
            // таймер снимается после коммита: откат паузы оставляет игру идущей вместе с таймером
            afterCommit(() -> stop(sessionId, run));
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
                    pausedEvents(sessionId, row.speed(), "restart");
                }
            });
        }
    }

    // ---------- шаги и завершение ----------

    /**
     * Двигает курсор на {@code candles} шагов (не дальше последнего; банкротство останавливает раньше) под блокировкой
     * сессии, пишет событие {@code step} и, если игра кончилась, завершает сессию. Воркеру уходит один запрос с событиями
     * шагов пачки, без истории: в событии свечи всех шагов пачки.
     */
    private Advance advance(
            SessionRecord session, List<JournalRecord> journal, int candles, long totalSteps, PlayerStates.Row row) {
        UUID id = session.id();
        long from = session.currentStep();
        long target = Math.min(from + candles, totalSteps - 1);
        Position previous = cursor(session, row);
        StepReplay stepReplay = worker.replaySteps(Arena.market(session), journal, target, from, session.blind());
        ReplayResult replay = stepReplay.replay();
        long step = Math.max(replay.lastStep(), from);
        sessions.advance(id, step);

        TreeMap<Long, int[]> positions = new TreeMap<>();
        Position position = positions(session.blind(), stepReplay.steps(), from, step, previous, positions);
        players.setCursor(id, step, position.day(), position.candle());

        String endStatus = switch (replay.state()) {
            case FINISHED_END -> "completed";
            case BANKRUPT -> "bankrupt";
            case RUNNING -> step >= totalSteps - 1 ? "completed" : null;
        };
        Function<ArenaState, Map<String, Object>> stepBuilder =
                state -> masking.stepResult(state, from, stepReplay.steps(), positions, position.day());
        if (endStatus != null) {
            Completed completed = complete(sessions.reload(id), replay, endStatus, stepBuilder, position.day());
            return new Advance(sessions.reload(id), position.day(), completed.stepResult());
        }
        SessionRecord moved = sessions.reload(id);
        ArenaState state = arena.state(moved, replay, journals.seenSteps(id));
        Map<String, Object> result = stepBuilder.apply(state);
        events.append(id, "step", masking.stepEvent(result));
        return new Advance(moved, position.day(), result);
    }

    /**
     * День и номер свечи в дне для шагов пачки: свечи дня нумеруются с 1 по различным шагам с барами, продолжая с
     * позиции перед пачкой.
     */
    private static Position positions(
            boolean blind, StepBars steps, long from, long to, Position previous, Map<Long, int[]> positions) {
        TreeMap<Long, Integer> days = new TreeMap<>();
        if (blind) {
            steps.blindBars().forEach(b -> days.put(b.step(), b.tradingDay()));
        } else {
            steps.bars().forEach(b -> days.put(b.step(), b.tradingDay()));
        }
        int day = previous.day();
        int candle = previous.candle();
        for (Map.Entry<Long, Integer> e : days.subMap(from, false, to, true).entrySet()) {
            if (e.getValue() == day) {
                candle++;
            } else {
                day = e.getValue();
                candle = 1;
            }
            positions.put(e.getKey(), new int[] {day, candle});
        }
        return new Position(day, candle);
    }

    /**
     * День и номер свечи на курсоре сессии. Хранятся в {@code player_state} вместе с шагом; нет записи или шаг не
     * совпал с курсором — на шаге 0 это первая свеча первого дня, иначе у воркера по истории (один раз, результат
     * запоминается).
     */
    private Position cursor(SessionRecord session, PlayerStates.Row row) {
        if (row.cursorStep().isPresent() && row.cursorStep().getAsLong() == session.currentStep()) {
            return new Position(row.cursorDay(), row.cursorCandle());
        }
        Position found;
        if (session.currentStep() == 0) {
            found = new Position(1, 1);
        } else {
            ReplayResult replay = worker.replaySession(
                    Arena.market(session), journals.records(session), session.currentStep(), true, false);
            ViewFormat.StepIndex index = new ViewFormat.StepIndex();
            replay.history().forEach(b -> index.add(b.step(), b.tradingDay()));
            int[] at = index.build().at(session.currentStep());
            found = new Position(at[0], at[1]);
        }
        players.setCursor(session.id(), session.currentStep(), found.day(), found.candle());
        return found;
    }

    /** Число шагов шкалы: известно из {@code player_state}, иначе (первая команда сессии) — у воркера. */
    private long totalSteps(SessionRecord session, PlayerStates.Row row) {
        if (row.totalSteps().isPresent()) {
            return row.totalSteps().getAsLong();
        }
        ReplayResult probe = worker.replaySession(
                Arena.market(session), journals.records(session), session.currentStep(), false, false);
        players.setTotalSteps(session.id(), probe.totalSteps());
        return probe.totalSteps();
    }

    /**
     * Завершает сессию: статус, остановка игры, события {@code step} (если шаг только что пройден), {@code player} и
     * {@code finished}; публикует {@link SessionFinished}. Раскрытие слепой сессии — следствие статуса
     * ({@link SessionRecord#revealed()}): отчёт и воспроизведение отдают реальные данные только после него.
     *
     * @param stepBuilder строит результат последнего шага по состоянию завершённой сессии; {@code null} — продвижения не
     *     было (досрочное завершение)
     */
    private Completed complete(
            SessionRecord session,
            ReplayResult replay,
            String status,
            @Nullable Function<ArenaState, Map<String, Object>> stepBuilder,
            int day) {
        UUID id = session.id();
        sessions.finish(id, status);
        PlayerStates.Row row = players.get(id);
        if (row.playing()) {
            players.setPlaying(id, false);
        }
        SessionRecord ended = sessions.reload(id);
        ArenaState state = arena.state(ended, replay, journals.seenSteps(id));
        Map<String, Object> stepResult = stepBuilder == null ? null : stepBuilder.apply(state);
        if (stepResult != null) {
            events.append(id, "step", masking.stepEvent(stepResult));
        }
        if (row.playing()) {
            events.append(id, "player", masking.playerEvent(false, row.speed()));
        }
        Map<String, Object> result = masking.finishResult(state, endReason(status), day);
        events.append(id, "finished", masking.finishedEvent(result));
        publisher.publishEvent(new SessionFinished(id));
        Running seen = running.get(id);
        afterCommit(() -> stop(id, seen));
        return new Completed(result, stepResult);
    }

    private void pausedEvents(UUID sessionId, int speed, String reason) {
        events.append(sessionId, "player", masking.playerEvent(false, speed));
        events.append(sessionId, "paused", masking.pausedEvent(reason));
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
