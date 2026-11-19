package ru.tinvest.trainer.sessions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import ru.tinvest.trainer.api.NotFoundException;
import ru.tinvest.trainer.worker.SimulationWorkerClient;
import ru.tinvest.trainer.worker.WorkerTypes.BarInterval;
import ru.tinvest.trainer.worker.WorkerTypes.Command;
import ru.tinvest.trainer.worker.WorkerTypes.CommandOutcome;
import ru.tinvest.trainer.worker.WorkerTypes.EstimateResult;
import ru.tinvest.trainer.worker.WorkerTypes.JournalRecord;
import ru.tinvest.trainer.worker.WorkerTypes.Market;
import ru.tinvest.trainer.worker.WorkerTypes.OrderKind;
import ru.tinvest.trainer.worker.WorkerTypes.OrderParams;
import ru.tinvest.trainer.worker.WorkerTypes.OrderState;
import ru.tinvest.trainer.worker.WorkerTypes.Paper;
import ru.tinvest.trainer.worker.WorkerTypes.RejectCode;
import ru.tinvest.trainer.worker.WorkerTypes.ReplayResult;

/**
 * Арена: снимок сессии на курсоре, подача, отмена и замена заявок, оценка заявки. Состояние сессии — проигрывание
 * журнала воркером до текущего шага (INV-8), у backend другого изменяемого состояния нет. Журнал пишется только
 * командами; оценка и чтение снимка его не меняют. Команды одной сессии выполняются по очереди (блокировка строки
 * сессии), ответ строит {@link BlindMasking}.
 */
@Service
class Arena {

    private static final String STATUS_RUNNING = "running";
    private static final String BENCHMARK = "IMOEX";

    private final Sessions sessions;
    private final ArenaJournal journals;
    private final OrderRefs orderRefs;
    private final CommandRunner commands;
    private final SessionEvents events;
    private final SimulationWorkerClient worker;
    private final BlindMasking masking;
    private final PlayerStates players;

    Arena(
            Sessions sessions,
            ArenaJournal journals,
            OrderRefs orderRefs,
            CommandRunner commands,
            SessionEvents events,
            SimulationWorkerClient worker,
            BlindMasking masking,
            PlayerStates players) {
        this.sessions = sessions;
        this.journals = journals;
        this.orderRefs = orderRefs;
        this.commands = commands;
        this.events = events;
        this.worker = worker;
        this.masking = masking;
        this.players = players;
    }

    /** Условия сессии для воркера: бумаги в порядке {@code paper_index} (индекс = {@code instrument_index} ядра). */
    static Market market(SessionRecord session) {
        List<Paper> papers = new ArrayList<>(session.papers().size());
        for (PaperRecord p : session.papers()) {
            papers.add(new Paper(p.secid(), p.secid(), p.lotSize(), p.priceStep()));
        }
        return new Market(
                session.snapshotKey(),
                papers,
                BarInterval.valueOf(session.timeframe().name()),
                session.periodStart(),
                session.periodEnd(),
                session.costs(),
                session.initialCapital(),
                BENCHMARK,
                session.warmupBars());
    }

    // ---------- снимок ----------

    /** Снимок на текущем шаге; курсор не двигает. */
    Map<String, Object> snapshot(UUID sessionId) {
        SessionRecord session = sessions.requireOwned(sessionId);
        List<JournalRecord> journal = journals.records(session);
        ReplayResult replay =
                worker.replaySession(market(session), journal, session.currentStep(), true, session.blind());
        return masking.snapshot(state(session, replay, journals.seenSteps(session.id())));
    }

    // ---------- оценка ----------

    /** Оценка заявки по close последнего бара до курсора; в журнал не пишет (INV-8). */
    Map<String, Object> estimate(UUID sessionId, OrderRequest request) {
        SessionRecord session = sessions.requireOwned(sessionId);
        if (!STATUS_RUNNING.equals(session.status())) {
            return masking.estimate(
                    session,
                    new ArenaState.EstimateView(
                            false, RejectCode.SESSION_NOT_ACTIVE, null, ArenaState.EstimateView.zeros(), List.of()));
        }
        try {
            List<OrderParams> legs = request.legs(session, session.blind());
            List<JournalRecord> journal = journals.records(session);
            // каждая нога оценивается отдельно (у OCO — стоп и тейк-профит); canPlace — по всем ногам
            List<ArenaState.EstimateLeg> results = new ArrayList<>(legs.size());
            ArenaState.EstimateLeg failed = null;
            for (OrderParams leg : legs) {
                EstimateResult result = worker.estimateOrder(market(session), journal, session.currentStep(), leg);
                ArenaState.EstimateLeg item = new ArenaState.EstimateLeg(
                        leg.kind(),
                        result.canPlace(),
                        result.canPlace() ? null : result.reason(),
                        result.canPlace() ? null : legField(result.invalidField(), legs, session),
                        result);
                results.add(item);
                if (!item.canPlace() && failed == null) {
                    failed = item;
                }
            }
            EstimateResult first = results.getFirst().amounts();
            return masking.estimate(
                    session,
                    failed == null
                            ? new ArenaState.EstimateView(true, null, null, first, results)
                            : new ArenaState.EstimateView(false, failed.reason(), failed.field(), first, results));
        } catch (FormRejection e) {
            return masking.estimate(
                    session,
                    new ArenaState.EstimateView(
                            false, e.reason(), e.field(), ArenaState.EstimateView.zeros(), List.of()));
        }
    }

    // ---------- команды ----------

    Object place(UUID sessionId, @Nullable UUID key, OrderRequest request) {
        return runCommand(sessionId, key, "place", request.fingerprint("place"), session -> {
            List<JournalRecord> journal = journals.records(session);
            try {
                long seenStep = request.requireSeenStep();
                guard(session, seenStep);
                List<OrderParams> legs = resolve(session, journal, request.legs(session, session.blind()));
                Command.Place command = new Command.Place(legs.get(0), legs.size() > 1 ? legs.get(1) : null);
                return execute(session, journal, command, seenStep);
            } catch (FormRejection e) {
                return rejection(session, journal, e);
            }
        });
    }

    Object cancel(UUID sessionId, UUID orderId, long seenStep, @Nullable UUID key) {
        return runCommand(
                sessionId, key, "cancel:" + orderId, OrderRequest.cancelFingerprint(orderId, seenStep), session -> {
                    long coreId = coreId(session, orderId);
                    List<JournalRecord> journal = journals.records(session);
                    try {
                        guard(session, seenStep);
                        return execute(session, journal, new Command.Cancel(coreId), seenStep);
                    } catch (FormRejection e) {
                        return rejection(session, journal, e);
                    }
                });
    }

    Object replace(UUID sessionId, UUID orderId, @Nullable UUID key, OrderRequest request) {
        return runCommand(sessionId, key, "replace:" + orderId, request.fingerprint("replace:" + orderId), session -> {
            long coreId = coreId(session, orderId);
            List<JournalRecord> journal = journals.records(session);
            try {
                long seenStep = request.requireSeenStep();
                guard(session, seenStep);
                // тип, сторона и бумага — у заменяемой заявки (в ядре); для OCO заменяется вся пара
                ReplayResult current =
                        worker.replaySession(market(session), journal, session.currentStep(), false, false);
                List<OrderState> existing = legsOf(current, coreId);
                List<OrderParams> replacements = new ArrayList<>(existing.size());
                for (OrderState leg : existing) {
                    replacements.add(request.replacement(leg.spec(), session.blind()));
                }
                replacements = resolve(session, journal, replacements);
                Command.Replace command = new Command.Replace(
                        coreId,
                        new Command.Place(replacements.get(0), replacements.size() > 1 ? replacements.get(1) : null));
                return execute(session, journal, command, seenStep);
            } catch (FormRejection e) {
                return rejection(session, journal, e);
            }
        });
    }

    private Object runCommand(
            UUID sessionId,
            @Nullable UUID key,
            String operation,
            String requestHash,
            Function<SessionRecord, Map<String, Object>> body) {
        return commands.run(sessionId, key, operation, requestHash, body);
    }

    /** Пишет команду в журнал вместе с итогом воркера; отказ воркера тоже записывается (accepted = false). */
    private Map<String, Object> execute(
            SessionRecord session, List<JournalRecord> journal, Command command, long seenStep) {
        long seq = journals.nextSeq(session.id());
        JournalRecord record = new JournalRecord(seq, session.currentStep(), command);
        List<JournalRecord> full = new ArrayList<>(journal);
        full.add(record);
        ReplayResult replay =
                worker.replaySession(market(session), full, session.currentStep(), false, session.blind());
        CommandOutcome outcome = replay.commandResults().stream()
                .filter(c -> c.seq() == seq)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Воркер не вернул результат команды " + seq));
        journals.append(session, record, seenStep, outcome.accepted(), outcome.reason(), outcome.invalidField());
        Map<Long, Long> seenSteps = journals.seenSteps(session.id());
        ArenaState state = state(session, replay, seenSteps);
        if (!outcome.accepted()) {
            return masking.commandResult(
                    state,
                    new ArenaState.CommandReply(
                            false,
                            outcome.reason() == null ? RejectCode.INVALID_PARAMS : outcome.reason(),
                            clientField(outcome.invalidField(), commandKinds(command), session),
                            List.of(),
                            List.of()));
        }
        if (command instanceof Command.Cancel cancel) {
            List<Long> shown = legIds(replay, cancel.orderId());
            orderUpdated(session, state, shown);
            return masking.commandResult(state, new ArenaState.CommandReply(true, null, null, List.of(), shown));
        }
        if (outcome.orderIds().isEmpty()) {
            throw new IllegalStateException("Воркер принял команду " + seq + " без номеров созданных заявок");
        }
        List<Long> created = outcome.orderIds();
        orderUpdated(session, state, created);

        return masking.commandResult(state, new ArenaState.CommandReply(true, null, null, created, created));
    }

    /** Событие {@code order_updated} по каждой заявке принятой команды; пишется в транзакции команды. */
    private void orderUpdated(SessionRecord session, ArenaState state, List<Long> coreOrderIds) {
        for (long id : coreOrderIds) {
            events.append(session.id(), "order_updated", masking.orderUpdatedEvent(state, id));
        }
    }

    /** Отказ до воркера: в журнал не пишется, ответ — 200 с {@code accepted: false}. */
    private Map<String, Object> rejection(SessionRecord session, List<JournalRecord> journal, FormRejection e) {
        ReplayResult replay =
                worker.replaySession(market(session), journal, session.currentStep(), false, session.blind());
        return masking.commandResult(
                state(session, replay, journals.seenSteps(session.id())),
                new ArenaState.CommandReply(false, e.reason(), e.field(), List.of(), List.of()));
    }

    private static void guard(SessionRecord session, long seenStep) {
        if (!STATUS_RUNNING.equals(session.status())) {
            throw new FormRejection(RejectCode.SESSION_NOT_ACTIVE, null);
        }
        if (seenStep > session.currentStep()) {
            throw new FormRejection(RejectCode.INVALID_PARAMS, "seenStep");
        }
    }

    private long coreId(SessionRecord session, UUID orderId) {
        return orderRefs.coreId(session.id(), orderId).orElseThrow(() -> new NotFoundException("Order not found"));
    }

    /** Заменяемая нога и, для OCO, её пара: стоп первым. */
    private static List<OrderState> legsOf(ReplayResult replay, long coreId) {
        OrderState target = replay.orders().stream()
                .filter(o -> o.id() == coreId)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Order not found"));
        List<OrderState> legs = new ArrayList<>(2);
        legs.add(target);
        if (target.ocoSiblingId() >= 0) {
            replay.orders().stream()
                    .filter(o -> o.id() == target.ocoSiblingId())
                    .findFirst()
                    .ifPresent(legs::add);
        }
        if (legs.size() == 2 && legs.get(0).spec().kind() == OrderKind.TAKE_PROFIT) {
            legs = List.of(legs.get(1), legs.get(0));
        }
        return legs;
    }

    private static List<Long> legIds(ReplayResult replay, long coreId) {
        return legsOf(replay, coreId).stream().map(OrderState::id).toList();
    }

    // ---------- перевод слепых цен ----------

    /**
     * Слепая заявка переводится в рубли {@code EstimateOrder}; в журнал идёт {@code resolved_order}. Не вышло перевести
     * (нет рублёвой заявки) — отказ без записи в журнал. Открытая сессия цены не переводит.
     */
    private List<OrderParams> resolve(SessionRecord session, List<JournalRecord> journal, List<OrderParams> legs) {
        if (!session.blind()) {
            return legs;
        }
        List<OrderParams> resolved = new ArrayList<>(legs.size());
        for (OrderParams leg : legs) {
            EstimateResult estimate = worker.estimateOrder(market(session), journal, session.currentStep(), leg);
            OrderParams rubles = estimate.resolvedOrder();
            if (rubles == null) {
                throw new FormRejection(
                        estimate.reason() == null ? RejectCode.INVALID_PARAMS : estimate.reason(),
                        legField(estimate.invalidField(), legs, session));
            }
            if (rubles.blindPrice()) {
                throw new IllegalStateException("Воркер вернул заявку в слепой шкале вместо рублёвой");
            }
            resolved.add(rubles);
        }
        return resolved;
    }

    // ---------- состояние ----------

    ArenaState state(SessionRecord session, ReplayResult replay, Map<Long, Long> seenSteps) {
        TreeSet<Long> ids = new TreeSet<>();
        replay.orders().forEach(o -> ids.add(o.id()));
        replay.fills().forEach(f -> ids.add(f.orderId()));
        return new ArenaState(
                session, replay, orderRefs.ensure(session.id(), ids), seenSteps, players.get(session.id()));
    }

    private static List<OrderKind> commandKinds(Command command) {
        Command.Place place = switch (command) {
            case Command.Place p -> p;
            case Command.Replace r -> r.replacement();
            case Command.Cancel c -> null;
        };
        if (place == null) {
            return List.of();
        }
        return place.ocoWith() == null
                ? List.of(place.order().kind())
                : List.of(place.order().kind(), place.ocoWith().kind());
    }

    private static @Nullable String legField(String coreField, List<OrderParams> legs, SessionRecord session) {
        return clientField(coreField, legs.stream().map(OrderParams::kind).toList(), session);
    }

    /** Имя поля ядра в имени поля запроса: уровень тейка — {@code takeProfitPrice}, бумага — {@code paperIndex/paperId}. */
    private static @Nullable String clientField(String coreField, List<OrderKind> kinds, SessionRecord session) {
        return ViewFormat.clientField(coreField, kinds, session.blind());
    }
}
