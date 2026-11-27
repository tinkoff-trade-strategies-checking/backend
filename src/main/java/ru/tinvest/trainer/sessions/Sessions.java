package ru.tinvest.trainer.sessions;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.tinvest.trainer.api.NotFoundException;
import ru.tinvest.trainer.config.Uuid7;
import ru.tinvest.trainer.identity.CurrentUser;
import ru.tinvest.trainer.marketdata.LaunchPlan;
import ru.tinvest.trainer.marketdata.LaunchPlanner;
import ru.tinvest.trainer.marketdata.PlannedPaper;
import ru.tinvest.trainer.marketdata.Regime;
import ru.tinvest.trainer.marketdata.Snapshot;
import ru.tinvest.trainer.marketdata.Snapshots;
import ru.tinvest.trainer.marketdata.StartMode;
import ru.tinvest.trainer.marketdata.Timeframe;
import ru.tinvest.trainer.worker.WorkerTypes.Costs;
import ru.tinvest.trainer.workshop.StrategyCatalog;
import ru.tinvest.trainer.workshop.StrategyVersionView;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Хранилище сессий (схема {@code sessions}) и единственная точка доступа к ним по владельцу: чужая сессия
 * неотличима от несуществующей (404). Параметры и план после создания не меняются (триггер в БД).
 */
@Service
public class Sessions {

    private static final String STATUS_RUNNING = "running";

    /** Издержки исполнения по умолчанию ({@code decisions.md}, «Модель исполнения»); фиксируются в сессии при запуске. */
    static final Costs DEFAULT_COSTS = new Costs(
            new BigDecimal("0.003"),
            new BigDecimal("0.0001"),
            new BigDecimal("0.001"),
            new BigDecimal("0.1"),
            new BigDecimal("0.1"));

    private final JdbcClient jdbc;
    private final @Nullable JdbcOperations jdbcOps;
    private final CurrentUser currentUser;
    private final Snapshots snapshots;
    private final StrategyCatalog strategyCatalog;
    private final ObjectMapper json;
    private final RandomGenerator random;

    @Autowired
    Sessions(
            JdbcClient jdbc,
            JdbcOperations jdbcOps,
            CurrentUser currentUser,
            Snapshots snapshots,
            StrategyCatalog strategyCatalog,
            ObjectMapper json) {
        this(jdbc, jdbcOps, currentUser, snapshots, strategyCatalog, json, new SecureRandom());
    }

    Sessions(
            JdbcClient jdbc,
            CurrentUser currentUser,
            Snapshots snapshots,
            StrategyCatalog strategyCatalog,
            ObjectMapper json,
            RandomGenerator random) {
        this(jdbc, null, currentUser, snapshots, strategyCatalog, json, random);
    }

    private Sessions(
            JdbcClient jdbc,
            @Nullable JdbcOperations jdbcOps,
            CurrentUser currentUser,
            Snapshots snapshots,
            StrategyCatalog strategyCatalog,
            ObjectMapper json,
            RandomGenerator random) {
        this.snapshots = snapshots;
        this.jdbc = jdbc;
        this.jdbcOps = jdbcOps;
        this.currentUser = currentUser;
        this.strategyCatalog = strategyCatalog;
        this.json = json;
        this.random = random;
    }

    /**
     * Создаёт сессию по подобранному плану с фоновым алгоритмом по умолчанию («Эталон платформы» v1). Бумаги
     * получают индекс в случайном порядке, не по тикеру ({@code api/openapi.yaml}, {@code PaperIndex}).
     */
    @Transactional
    UUID create(UUID userId, StartMode startMode, int durationDays, LaunchPlan plan) {
        return create(userId, startMode, durationDays, plan, strategyCatalog.systemDefaultVersion(), null);
    }

    /**
     * Создаёт сессию по подобранному плану с фоновым алгоритмом по умолчанию и необязательным лимитом дневного убытка.
     */
    @Transactional
    UUID create(
            UUID userId,
            StartMode startMode,
            int durationDays,
            LaunchPlan plan,
            @Nullable BigDecimal dailyLossLimitPct) {
        return create(userId, startMode, durationDays, plan, strategyCatalog.systemDefaultVersion(), dailyLossLimitPct);
    }

    /**
     * Создаёт сессию по подобранному плану с указанной опубликованной версией фонового алгоритма.
     */
    @Transactional
    UUID create(UUID userId, StartMode startMode, int durationDays, LaunchPlan plan, StrategyVersionView algoVersion) {
        return create(userId, startMode, durationDays, plan, algoVersion, null);
    }

    /**
     * Создаёт сессию по подобранному плану с указанной опубликованной версией фонового алгоритма и необязательным
     * лимитом дневного убытка (kill-switch).
     */
    @Transactional
    UUID create(
            UUID userId,
            StartMode startMode,
            int durationDays,
            LaunchPlan plan,
            StrategyVersionView algoVersion,
            @Nullable BigDecimal dailyLossLimitPct) {
        UUID id = Uuid7.next();
        Map<String, Double> algoParams = algoVersion.isSystem()
                ? Map.of("size_pct", (double) Math.max(1, 95 / plan.papers().size()))
                : Map.of();
        jdbc.sql("INSERT INTO sessions.session (id, user_id, blind, status, trader_kind, duration_days, timeframe, "
                        + "regime, start_mode, snapshot_id, period_start, period_end, trading_days, bars_count, "
                        + "sigma, mdd, p33, p80, initial_capital, warmup_bars, commission_rate, exchange_fee_rate, spread, "
                        + "slippage_kappa, liquidity_share, algo_version_id, algo_params, daily_loss_limit_pct) "
                        + "VALUES (:id, :user, :blind, :status, 'human', "
                        + ":duration, :timeframe, :regime, :startMode, :snapshot, :start, :end, :days, :bars, "
                        + ":sigma, :mdd, :p33, :p80, :capital, :warmup, :commission, :exchangeFee, :spread, "
                        + ":kappa, :liquidity, :algoVersionId, CAST(:algoParams AS jsonb), :dailyLossLimitPct)")
                .param("id", id)
                .param("user", userId)
                .param("blind", plan.blind())
                .param("status", STATUS_RUNNING)
                .param("duration", durationDays)
                .param("timeframe", plan.timeframe().code())
                .param("regime", plan.regime().code())
                .param("startMode", startMode.name().toLowerCase(Locale.ROOT))
                .param("snapshot", plan.snapshot().id())
                .param("start", plan.startDate())
                .param("end", plan.endDate())
                .param("days", plan.tradingDays())
                .param("bars", plan.barsCount())
                .param("sigma", plan.sigma())
                .param("mdd", plan.mdd())
                .param("p33", plan.p33())
                .param("p80", plan.p80())
                .param("capital", plan.capital())
                .param("warmup", LaunchPlanner.WARMUP_BARS)
                .param("commission", DEFAULT_COSTS.commissionRate())
                .param("exchangeFee", DEFAULT_COSTS.exchangeFeeRate())
                .param("spread", DEFAULT_COSTS.spread())
                .param("kappa", DEFAULT_COSTS.slippageKappa())
                .param("liquidity", DEFAULT_COSTS.liquidityShare())
                .param("algoVersionId", algoVersion.versionId())
                .param("algoParams", json.writeValueAsString(algoParams))
                .param("dailyLossLimitPct", dailyLossLimitPct)
                .update();
        List<PlannedPaper> shuffled = new ArrayList<>(plan.papers());
        Collections.shuffle(shuffled, random);
        insertPapers(id, shuffled);
        // На шаге 0 сессия стоит на первой свече первого дня (согласовано с Player.cursor): история не ходит в воркер.
        jdbc.sql("INSERT INTO sessions.player_state (session_id, cursor_step, cursor_day, cursor_candle) "
                        + "VALUES (:id, 0, 1, 1) ON CONFLICT (session_id) DO NOTHING")
                .param("id", id)
                .update();
        return id;
    }

    private void insertPapers(UUID sessionId, List<PlannedPaper> shuffled) {
        if (shuffled.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO sessions.session_paper (session_id, paper_index, paper_id, secid, sector, "
                + "lot_size, price_step, name) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        if (jdbcOps != null) {
            List<Object[]> batch = new ArrayList<>(shuffled.size());
            for (int index = 0; index < shuffled.size(); index++) {
                PlannedPaper p = shuffled.get(index);
                batch.add(new Object[] {
                    sessionId, index, p.id(), p.secid(), p.sector(), p.lotSize(), p.priceStep(), p.name()
                });
            }
            jdbcOps.batchUpdate(sql, batch);
            return;
        }
        for (int index = 0; index < shuffled.size(); index++) {
            PlannedPaper p = shuffled.get(index);
            jdbc.sql(sql)
                    .params(sessionId, index, p.id(), p.secid(), p.sector(), p.lotSize(), p.priceStep(), p.name())
                    .update();
        }
    }

    /**
     * Сессия текущего пользователя.
     *
     * @throws NotFoundException сессии нет или она чужая (не различаются)
     */
    public SessionRecord requireOwned(UUID sessionId) {
        return requireOwned(sessionId, false);
    }

    /**
     * Сессия текущего пользователя с блокировкой строки до конца транзакции: команды одной сессии (запись в журнал)
     * выполняются по очереди. Вызывать внутри транзакции.
     */
    SessionRecord requireOwnedForUpdate(UUID sessionId) {
        return requireOwned(sessionId, true);
    }

    /**
     * Сессия по идентификатору без проверки владельца и с блокировкой строки: для часов плеера, у которых нет
     * пользователя запроса. Вызывать внутри транзакции.
     */
    Optional<SessionRecord> lockById(UUID sessionId) {
        return find(sessionId, true);
    }

    /** Сессия по идентификатору без проверки владельца (после изменения под блокировкой — перечитать). */
    SessionRecord reload(UUID sessionId) {
        return find(sessionId, false).orElseThrow(() -> new NotFoundException("Session not found"));
    }

    /** Двигает курсор вперёд; назад триггер БД не пускает. Вызывать под блокировкой строки сессии. */
    void advance(UUID sessionId, long step) {
        jdbc.sql("UPDATE sessions.session SET current_step = :step WHERE id = :id")
                .param("step", step)
                .param("id", sessionId)
                .update();
    }

    /** Переводит сессию из {@code running} в завершённый статус (триггер БД не пускает обратно). */
    void finish(UUID sessionId, String status) {
        jdbc.sql("UPDATE sessions.session SET status = :status WHERE id = :id AND status = 'running'")
                .param("status", status)
                .param("id", sessionId)
                .update();
    }

    /** Фиксирует факт срабатывания kill-switch для идущей сессии. */
    @Transactional
    void markKillSwitchTriggered(UUID sessionId) {
        jdbc.sql("UPDATE sessions.session SET kill_switch_triggered = TRUE "
                        + "WHERE id = :id AND status = 'running' AND NOT kill_switch_triggered")
                .param("id", sessionId)
                .update();
    }

    /**
     * Сессия по идентификатору без проверки владельца: только для фоновых задач по событию (отчёт после завершения),
     * идентификатор приходит из системы, не от клиента.
     */
    Optional<SessionRecord> findAny(UUID sessionId) {
        return find(sessionId, false);
    }

    private SessionRecord requireOwned(UUID sessionId, boolean lock) {
        UUID userId = currentUser.requireId();
        return find(sessionId, lock)
                .filter(s -> s.userId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Session not found"));
    }

    private Optional<SessionRecord> find(UUID sessionId, boolean lock) {
        return jdbc.sql("SELECT * FROM sessions.session WHERE id = :id" + (lock ? " FOR UPDATE" : ""))
                .param("id", sessionId)
                .query((rs, n) -> mapSession(rs, papers(sessionId)))
                .optional();
    }

    /** Подставляет батчем загруженные бумаги в список сессий (1 SQL-запрос вместо N). */
    List<SessionRecord> attachPapers(List<SessionRecord> rawSessions) {
        if (rawSessions.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rawSessions.stream().map(SessionRecord::id).toList();
        Map<UUID, List<PaperRecord>> bySession = papersBySession(ids);
        List<SessionRecord> out = new ArrayList<>(rawSessions.size());
        for (SessionRecord s : rawSessions) {
            out.add(new SessionRecord(
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
                    s.currentStep(),
                    s.warmupBars(),
                    s.costs(),
                    bySession.getOrDefault(s.id(), List.of()),
                    s.algoVersionId(),
                    s.algoParams(),
                    s.dailyLossLimitPct(),
                    s.killSwitchTriggered()));
        }
        return out;
    }

    /** Батчевая загрузка бумаг для списка сессий одним SQL-запросом (для истории без N+1). */
    Map<UUID, List<PaperRecord>> papersBySession(Collection<UUID> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<PaperRecord>> bySession = new HashMap<>(sessionIds.size());
        jdbc.sql("SELECT session_id, paper_index, paper_id, secid, sector, lot_size, price_step, name "
                        + "FROM sessions.session_paper WHERE session_id IN (:ids) ORDER BY session_id, paper_index")
                .param("ids", sessionIds)
                .query(rs -> {
                    UUID sid = rs.getObject("session_id", UUID.class);
                    bySession
                            .computeIfAbsent(sid, k -> new ArrayList<>())
                            .add(new PaperRecord(
                                    rs.getInt("paper_index"),
                                    rs.getObject("paper_id", UUID.class),
                                    rs.getString("secid"),
                                    rs.getString("sector"),
                                    rs.getLong("lot_size"),
                                    rs.getBigDecimal("price_step"),
                                    rs.getString("name")));
                });
        return bySession;
    }

    SessionRecord mapSession(ResultSet rs, List<PaperRecord> sessionPapers) throws SQLException {
        UUID snapshotId = rs.getObject("snapshot_id", UUID.class);
        return new SessionRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getBoolean("blind"),
                rs.getString("status"),
                rs.getInt("duration_days"),
                Timeframe.fromCode(rs.getString("timeframe")).orElseThrow(),
                Regime.fromCode(rs.getString("regime")).orElseThrow(),
                StartMode.valueOf(rs.getString("start_mode").toUpperCase(Locale.ROOT)),
                snapshotId,
                snapshotKey(snapshotId),
                rs.getObject("period_start", LocalDate.class),
                rs.getObject("period_end", LocalDate.class),
                rs.getInt("trading_days"),
                rs.getInt("bars_count"),
                rs.getDouble("sigma"),
                rs.getDouble("mdd"),
                rs.getDouble("p33"),
                rs.getDouble("p80"),
                rs.getObject("initial_capital", BigDecimal.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getLong("current_step"),
                rs.getInt("warmup_bars"),
                new Costs(
                        rs.getBigDecimal("commission_rate"),
                        rs.getBigDecimal("exchange_fee_rate"),
                        rs.getBigDecimal("spread"),
                        rs.getBigDecimal("slippage_kappa"),
                        rs.getBigDecimal("liquidity_share")),
                sessionPapers,
                rs.getObject("algo_version_id", UUID.class),
                parseAlgoParams(rs.getString("algo_params")),
                rs.getBigDecimal("daily_loss_limit_pct"),
                rs.getBoolean("kill_switch_triggered"));
    }

    private Map<String, Double> parseAlgoParams(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        JsonNode node = json.readTree(raw);
        Map<String, Double> out = new LinkedHashMap<>();
        node.properties().forEach(e -> out.put(e.getKey(), e.getValue().asDouble()));
        return out;
    }

    /**
     * Ключ снапшота в ClickHouse ({@code snapshot_id} воркера): сессия хранит первичный ключ снапшота PostgreSQL, а
     * воркер читает данные по ключу. Нет строки снапшота — ошибка (подставлять идентификатор нельзя).
     */
    private String snapshotKey(UUID snapshotId) {
        return snapshots
                .find(snapshotId)
                .map(Snapshot::snapshotKey)
                .orElseThrow(() -> new IllegalStateException("Снапшот сессии не найден: " + snapshotId));
    }

    private List<PaperRecord> papers(UUID sessionId) {
        return jdbc.sql("SELECT paper_index, paper_id, secid, sector, lot_size, price_step, name "
                        + "FROM sessions.session_paper WHERE session_id = :id ORDER BY paper_index")
                .param("id", sessionId)
                .query((rs, n) -> new PaperRecord(
                        rs.getInt("paper_index"),
                        rs.getObject("paper_id", UUID.class),
                        rs.getString("secid"),
                        rs.getString("sector"),
                        rs.getLong("lot_size"),
                        rs.getBigDecimal("price_step"),
                        rs.getString("name")))
                .list();
    }
}
