package ru.tinvest.trainer.sessions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import ru.tinvest.trainer.api.InvalidRequestException;
import ru.tinvest.trainer.api.NotFoundException;
import ru.tinvest.trainer.worker.SimulationWorkerClient;
import ru.tinvest.trainer.worker.WorkerTypes.JournalRecord;
import ru.tinvest.trainer.worker.WorkerTypes.ReplayResult;

/**
 * Воспроизведение завершённой сессии без серверного состояния (INV-8): курсор хранит клиент и передаёт явный
 * {@code step}; ответ — журнал, проигранный воркером до этого шага. Идущая сессия не воспроизводится вовсе
 * ({@code NOT_FINISHED}, INV-1, INV-7), шаг дальше итогового ограничивается им. Ответ раскрытый: после завершения
 * слепые данные раскрываются (INV-7).
 */
@Service
class Replay {

    private final Sessions sessions;
    private final SessionArchive archive;
    private final ArenaJournal journals;
    private final OrderRefs orderRefs;
    private final SimulationWorkerClient worker;

    Replay(
            Sessions sessions,
            SessionArchive archive,
            ArenaJournal journals,
            OrderRefs orderRefs,
            SimulationWorkerClient worker) {
        this.sessions = sessions;
        this.archive = archive;
        this.journals = journals;
        this.orderRefs = orderRefs;
        this.worker = worker;
    }

    /**
     * @param step запрошенный шаг, не меньше 0
     * @param fromStep шаг, до которого клиент уже всё знает; {@code null} — вернуть снимок, а не приращение
     */
    Map<String, Object> replay(UUID sessionId, long step, @Nullable Long fromStep) {
        if (step < 0) {
            throw new InvalidRequestException("step must not be negative");
        }
        if (fromStep != null && (fromStep < 0 || fromStep > step)) {
            throw new InvalidRequestException("fromStep must be between 0 and step");
        }
        SessionRecord session;
        try {
            session = sessions.requireOwned(sessionId);
        } catch (NotFoundException e) {
            return PublicViews.notFound(sessionId);
        }
        if ("running".equals(session.status())) {
            return PublicViews.notFinished(archive.unfinished(session));
        }

        // шаг дальше итогового ограничивается им: будущего у завершённой сессии нет
        long last = session.currentStep();
        long effective = Math.min(step, last);
        long from = fromStep == null ? 0 : Math.min(fromStep, effective);
        List<JournalRecord> journal = journals.records(session).stream()
                .filter(r -> r.step() <= effective)
                .toList();
        ReplayResult replay = worker.replaySession(Arena.market(session), journal, effective, true, false);
        ArenaState state = state(atStep(session, effective), replay);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "READY");
        out.put("kind", (fromStep == null ? ReplayKind.SNAPSHOT : ReplayKind.INCREMENT).name());
        out.put("session", meta(session, replay));
        boolean finished = effective >= last;
        if (fromStep == null) {
            Map<String, Object> snapshot = OpenViews.snapshot(state);
            // воспроизведение — не плеер: состояния плеера нет (контракт: null в снимке воспроизведения)
            snapshot.put("playing", null);
            snapshot.put("lastEventId", null);
            snapshot.put("speed", null);
            snapshot.put("finished", finished);
            out.put("snapshot", snapshot);
        } else {
            ViewFormat.StepIndex index = new ViewFormat.StepIndex();
            replay.history().forEach(b -> index.add(b.step(), b.tradingDay()));
            index.build();
            Map<String, Object> increment =
                    OpenViews.stepResult(state, from, replay.history(), index.positions(), index.at(effective)[0]);
            // воспроизведение: «завершено» — дошли до итогового шага, а не статус сессии
            increment.put("finished", finished);
            out.put("increment", increment);
        }
        return out;
    }

    private static Map<String, Object> meta(SessionRecord session, ReplayResult replay) {
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("startDate", session.periodStart().toString());
        period.put("endDate", session.periodEnd().toString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", session.id());
        out.put("blind", session.blind());
        out.put("period", period);
        out.put("regime", session.regime().code());
        out.put("totalSteps", replay.totalSteps());
        // итоговый шаг: у досрочно завершённой и обанкротившейся короче шкалы, дальше него воспроизведение не идёт
        out.put("lastStep", session.currentStep());
        out.put("totalDays", session.tradingDays());
        return out;
    }

    private ArenaState state(SessionRecord session, ReplayResult replay) {
        TreeSet<Long> ids = new TreeSet<>();
        replay.orders().forEach(o -> ids.add(o.id()));
        replay.fills().forEach(f -> ids.add(f.orderId()));
        return new ArenaState(
                session,
                replay,
                orderRefs.ensure(session.id(), ids),
                journals.seenSteps(session.id()),
                PlayerStates.Row.DEFAULT);
    }

    /** Та же сессия с курсором на шаге воспроизведения: ответы строятся от {@code currentStep}, как у арены. */
    static SessionRecord atStep(SessionRecord s, long step) {
        return new SessionRecord(
                s.id(),
                s.userId(),
                s.blind(),
                s.status(),
                s.durationDays(),
                s.timeframe(),
                s.regime(),
                s.startMode(),
                s.snapshotId(),
                s.snapshotKey(),
                s.periodStart(),
                s.periodEnd(),
                s.tradingDays(),
                s.barsCount(),
                s.sigma(),
                s.mdd(),
                s.p33(),
                s.p80(),
                s.initialCapital(),
                s.createdAt(),
                step,
                s.warmupBars(),
                s.costs(),
                s.papers(),
                s.algoVersionId(),
                s.algoParams(),
                s.dailyLossLimitPct(),
                s.killSwitchTriggered());
    }
}
