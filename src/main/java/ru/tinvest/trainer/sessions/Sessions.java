package ru.tinvest.trainer.sessions;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final CurrentUser currentUser;
    private final Snapshots snapshots;
    private final RandomGenerator random;

    @Autowired
    Sessions(JdbcClient jdbc, CurrentUser currentUser, Snapshots snapshots) {
        this(jdbc, currentUser, snapshots, new SecureRandom());
    }

    Sessions(JdbcClient jdbc, CurrentUser currentUser, Snapshots snapshots, RandomGenerator random) {
        this.snapshots = snapshots;
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.random = random;
    }

    /**
     * Создаёт сессию по подобранному плану. Бумаги получают индекс в случайном порядке, не по тикеру
     * ({@code api/openapi.yaml}, {@code PaperIndex}).
     */
    @Transactional
    UUID create(UUID userId, StartMode startMode, int durationDays, LaunchPlan plan) {
        UUID id = Uuid7.next();
        jdbc.sql("INSERT INTO sessions.session (id, user_id, blind, status, trader_kind, duration_days, timeframe, "
                        + "regime, start_mode, snapshot_id, period_start, period_end, trading_days, bars_count, "
                        + "sigma, mdd, p33, p80, initial_capital, warmup_bars, commission_rate, exchange_fee_rate, spread, "
                        + "slippage_kappa, liquidity_share) VALUES (:id, :user, :blind, :status, 'human', "
                        + ":duration, :timeframe, :regime, :startMode, :snapshot, :start, :end, :days, :bars, "
                        + ":sigma, :mdd, :p33, :p80, :capital, :warmup, :commission, :exchangeFee, :spread, "
                        + ":kappa, :liquidity)")
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
                .update();
        List<PlannedPaper> shuffled = new ArrayList<>(plan.papers());
        Collections.shuffle(shuffled, random);
        for (int index = 0; index < shuffled.size(); index++) {
            PlannedPaper p = shuffled.get(index);
            jdbc.sql("INSERT INTO sessions.session_paper (session_id, paper_index, paper_id, secid, sector, "
                            + "lot_size, price_step) VALUES (:session, :idx, :paper, :secid, :sector, :lot, :step)")
                    .param("session", id)
                    .param("idx", index)
                    .param("paper", p.id())
                    .param("secid", p.secid())
                    .param("sector", p.sector())
                    .param("lot", p.lotSize())
                    .param("step", p.priceStep())
                    .update();
        }
        return id;
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
                .query((rs, n) -> new SessionRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getBoolean("blind"),
                        rs.getString("status"),
                        rs.getInt("duration_days"),
                        Timeframe.fromCode(rs.getString("timeframe")).orElseThrow(),
                        Regime.fromCode(rs.getString("regime")).orElseThrow(),
                        StartMode.valueOf(rs.getString("start_mode").toUpperCase(Locale.ROOT)),
                        rs.getObject("snapshot_id", UUID.class),
                        snapshotKey(rs.getObject("snapshot_id", UUID.class)),
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
                        papers(sessionId)))
                .optional();
    }

    /**
     * Ключ снапшота в ClickHouse ({@code snapshot_id} воркера): сессия хранит первичный ключ снапшота PostgreSQL, а
     * воркер читает данные по ключу. Нет строки снапшота — ключ равен идентификатору.
     */
    private String snapshotKey(UUID snapshotId) {
        return snapshots.find(snapshotId).map(Snapshot::snapshotKey).orElse(snapshotId.toString());
    }

    private List<PaperRecord> papers(UUID sessionId) {
        return jdbc.sql("SELECT paper_index, paper_id, secid, sector, lot_size, price_step "
                        + "FROM sessions.session_paper WHERE session_id = :id ORDER BY paper_index")
                .param("id", sessionId)
                .query((rs, n) -> new PaperRecord(
                        rs.getInt("paper_index"),
                        rs.getObject("paper_id", UUID.class),
                        rs.getString("secid"),
                        rs.getString("sector"),
                        rs.getLong("lot_size"),
                        rs.getBigDecimal("price_step")))
                .list();
    }
}
