package ru.tinvest.trainer.simulation;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.tinvest.trainer.api.ErrorCode;
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
import ru.tinvest.trainer.worker.WorkerTypes.CheckLevel;
import ru.tinvest.trainer.worker.WorkerTypes.Costs;
import ru.tinvest.trainer.worker.WorkerTypes.JournalRecord;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyDiagnostic;
import ru.tinvest.trainer.workshop.StrategyVersions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Публичный API и хранилище прогонов алгоритмов ({@code simulation.run}, {@code simulation.run_paper},
 * {@code simulation.run_result}): создание по плану, повтор с сохранением версий и параметров (UC-18), обновление
 * прогресса, сохранение неизменяемого итога (INV-4, INV-5) и выборка истории.
 */
@Service
public class Runs {

    static final Costs DEFAULT_COSTS = new Costs(
            new BigDecimal("0.003"),
            new BigDecimal("0.0001"),
            new BigDecimal("0.001"),
            new BigDecimal("0.1"),
            new BigDecimal("0.1"));

    record HistoryData(List<RunRecord> items, long total) {}

    private final JdbcClient jdbc;
    private final StrategyVersions strategyVersions;
    private final Snapshots snapshots;
    private final CurrentUser currentUser;
    private final RunEvents events;
    private final RunProperties properties;
    private final ObjectMapper json;
    private final RandomGenerator random;

    @Autowired
    Runs(
            JdbcClient jdbc,
            StrategyVersions strategyVersions,
            Snapshots snapshots,
            CurrentUser currentUser,
            RunEvents events,
            RunProperties properties,
            ObjectMapper json) {
        this(jdbc, strategyVersions, snapshots, currentUser, events, properties, json, new SecureRandom());
    }

    Runs(
            JdbcClient jdbc,
            StrategyVersions strategyVersions,
            Snapshots snapshots,
            CurrentUser currentUser,
            RunEvents events,
            RunProperties properties,
            ObjectMapper json,
            RandomGenerator random) {
        this.jdbc = jdbc;
        this.strategyVersions = strategyVersions;
        this.snapshots = snapshots;
        this.currentUser = currentUser;
        this.events = events;
        this.properties = properties;
        this.json = json;
        this.random = random;
    }

    /** Создаёт прогон с базовыми параметрами по ID версий (сохранено для совместимости с тестами неизменяемости). */
    public Run create(UUID strategyVersionId, UUID snapshotId) {
        return insertMinimal(strategyVersionId, snapshotId, null);
    }

    /** Новый прогон с теми же версиями стратегии, снапшота, периодом и бумагами, что у исходного (UC-18). */
    @Transactional
    public Run repeat(UUID runId) {
        RunRecord original = findRecord(runId).orElseThrow(() -> new NotFoundException("Прогон не найден"));
        UUID newId = repeatPlanned(original.userId(), original);
        return find(newId).orElseThrow();
    }

    public Optional<Run> find(UUID id) {
        return jdbc.sql("""
                        SELECT id, strategy_version_id, snapshot_id, repeat_of_run_id, created_at
                        FROM simulation.run WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, n) -> new Run(
                        rs.getObject("id", UUID.class),
                        rs.getObject("strategy_version_id", UUID.class),
                        rs.getObject("snapshot_id", UUID.class),
                        rs.getObject("repeat_of_run_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .optional();
    }

    @Transactional
    UUID createPlanned(
            UUID userId,
            UUID strategyVersionId,
            StartMode startMode,
            int durationDays,
            boolean blind,
            LaunchPlan plan,
            Map<String, Double> paramOverrides) {
        acquireUserQueueSlot(userId);
        UUID id = Uuid7.next();
        jdbc.sql("""
                        INSERT INTO simulation.run (
                            id, user_id, strategy_version_id, snapshot_id, repeat_of_run_id,
                            blind, status, duration_days, timeframe, regime, start_mode,
                            period_start, period_end, trading_days, bars_count,
                            sigma, mdd, p33, p80, initial_capital, warmup_bars,
                            commission_rate, exchange_fee_rate, spread, slippage_kappa, liquidity_share,
                            param_overrides
                        ) VALUES (
                            :id, :user, :sv, :sn, NULL,
                            :blind, 'queued', :duration, :timeframe, :regime, :startMode,
                            :start, :end, :days, :bars,
                            :sigma, :mdd, :p33, :p80, :capital, :warmup,
                            :commission, :exchangeFee, :spread, :kappa, :liquidity,
                            CAST(:overrides AS jsonb)
                        )
                        """)
                .param("id", id)
                .param("user", userId)
                .param("sv", strategyVersionId)
                .param("sn", plan.snapshot().id())
                .param("blind", blind)
                .param("duration", durationDays)
                .param("timeframe", plan.timeframe().code())
                .param("regime", plan.regime().code())
                .param("startMode", startMode.name().toLowerCase(Locale.ROOT))
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
                .param("overrides", json.writeValueAsString(paramOverrides))
                .update();

        List<PlannedPaper> shuffled = new ArrayList<>(plan.papers());
        Collections.shuffle(shuffled, random);
        for (int index = 0; index < shuffled.size(); index++) {
            PlannedPaper p = shuffled.get(index);
            insertPaper(id, index, p.id(), p.secid(), p.sector(), p.lotSize(), p.priceStep(), p.name());
        }
        return id;
    }

    @Transactional
    UUID repeatPlanned(UUID userId, RunRecord original) {
        acquireUserQueueSlot(userId);
        UUID id = Uuid7.next();
        jdbc.sql("""
                        INSERT INTO simulation.run (
                            id, user_id, strategy_version_id, snapshot_id, repeat_of_run_id,
                            blind, status, duration_days, timeframe, regime, start_mode,
                            period_start, period_end, trading_days, bars_count,
                            sigma, mdd, p33, p80, initial_capital, warmup_bars,
                            commission_rate, exchange_fee_rate, spread, slippage_kappa, liquidity_share,
                            param_overrides
                        ) VALUES (
                            :id, :user, :sv, :sn, :repeatOf,
                            :blind, 'queued', :duration, :timeframe, :regime, :startMode,
                            :start, :end, :days, :bars,
                            :sigma, :mdd, :p33, :p80, :capital, :warmup,
                            :commission, :exchangeFee, :spread, :kappa, :liquidity,
                            CAST(:overrides AS jsonb)
                        )
                        """)
                .param("id", id)
                .param("user", userId)
                .param("sv", original.strategyVersionId())
                .param("sn", original.snapshotId())
                .param("repeatOf", original.id())
                .param("blind", original.blind())
                .param("duration", original.durationDays())
                .param("timeframe", original.timeframe().code())
                .param("regime", original.regime().code())
                .param("startMode", original.startMode().name().toLowerCase(Locale.ROOT))
                .param("start", original.periodStart())
                .param("end", original.periodEnd())
                .param("days", original.tradingDays())
                .param("bars", original.barsCount())
                .param("sigma", original.sigma())
                .param("mdd", original.mdd())
                .param("p33", original.p33())
                .param("p80", original.p80())
                .param("capital", original.initialCapital())
                .param("warmup", original.warmupBars())
                .param("commission", original.costs().commissionRate())
                .param("exchangeFee", original.costs().exchangeFeeRate())
                .param("spread", original.costs().spread())
                .param("kappa", original.costs().slippageKappa())
                .param("liquidity", original.costs().liquidityShare())
                .param("overrides", json.writeValueAsString(original.paramOverrides()))
                .update();

        for (RunPaperRecord p : original.papers()) {
            insertPaper(id, p.paperIndex(), p.paperId(), p.secid(), p.sector(), p.lotSize(), p.priceStep(), p.name());
        }
        return id;
    }

    RunRecord requireOwned(UUID runId) {
        UUID userId = currentUser.requireId();
        return findRecord(runId)
                .filter(r -> r.userId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Run not found"));
    }

    Optional<RunRecord> findRecord(UUID runId) {
        return jdbc.sql("SELECT * FROM simulation.run WHERE id = :id")
                .param("id", runId)
                .query((rs, n) -> mapRun(rs))
                .optional();
    }

    /** Активные ({@code queued}/{@code running}) прогоны для восстановления после перезапуска процесса. */
    List<UUID> findActiveIds() {
        return jdbc.sql("SELECT id FROM simulation.run WHERE status IN ('queued', 'running') ORDER BY created_at ASC")
                .query(UUID.class)
                .list();
    }

    Optional<RunResultRecord> findResult(UUID runId) {
        return jdbc.sql("""
                        SELECT run_id, report::text AS report, journal::text AS journal,
                               diagnostics::text AS diagnostics, repeat_match, created_at
                        FROM simulation.run_result
                        WHERE run_id = :id
                        """)
                .param("id", runId)
                .query((rs, n) -> new RunResultRecord(
                        rs.getObject("run_id", UUID.class),
                        rs.getString("report"),
                        rs.getString("journal"),
                        rs.getString("diagnostics"),
                        rs.getObject("repeat_match", Boolean.class),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .optional();
    }

    @Transactional
    boolean markRunning(UUID runId) {
        return jdbc.sql("UPDATE simulation.run SET status = 'running' WHERE id = :id AND status = 'queued'")
                        .param("id", runId)
                        .update()
                > 0;
    }

    /**
     * Атомарно выбирает первый подходящий {@code queued}-прогон из {@code candidateIds}, чей пользователь сейчас имеет
     * меньше {@code maxRunningPerUser} запущенных ({@code running}) прогонов, и переводит его в {@code running}.
     */
    @Transactional
    Optional<UUID> claimFirstEligible(List<UUID> candidateIds, int maxRunningPerUser) {
        if (candidateIds.isEmpty()) {
            return Optional.empty();
        }
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('run-dispatch'))")
                .query()
                .singleRow();
        Optional<UUID> next = jdbc.sql("""
                        SELECT r.id
                        FROM simulation.run r
                        WHERE r.id IN (:ids)
                          AND r.status = 'queued'
                          AND (
                              SELECT count(*)
                              FROM simulation.run r2
                              WHERE r2.user_id = r.user_id
                                AND r2.status = 'running'
                          ) < :maxRunning
                        ORDER BY r.created_at ASC, r.id ASC
                        LIMIT 1
                        """)
                .param("ids", candidateIds)
                .param("maxRunning", maxRunningPerUser)
                .query(UUID.class)
                .optional();
        if (next.isEmpty()) {
            return Optional.empty();
        }
        UUID runId = next.get();
        int updated = jdbc.sql("UPDATE simulation.run SET status = 'running' WHERE id = :id AND status = 'queued'")
                .param("id", runId)
                .update();
        return updated > 0 ? Optional.of(runId) : Optional.empty();
    }

    private void acquireUserQueueSlot(UUID userId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:key))")
                .param("key", "run-user:" + userId)
                .query()
                .singleRow();
        int queued = jdbc.sql("SELECT count(*) FROM simulation.run WHERE user_id = :user AND status = 'queued'")
                .param("user", userId)
                .query(Integer.class)
                .single();
        if (queued >= properties.maxQueuedPerUser()) {
            throw new RunQueueLimitException(
                    "Too many queued runs (max " + properties.maxQueuedPerUser() + " per user)");
        }
    }

    @Transactional
    void updateProgress(UUID runId, long doneSteps, long totalSteps) {
        Optional<String> status = jdbc.sql("SELECT status FROM simulation.run WHERE id = :id FOR UPDATE")
                .param("id", runId)
                .query(String.class)
                .optional();
        if (status.isEmpty() || !"running".equals(status.get())) {
            return;
        }
        jdbc.sql("UPDATE simulation.run SET done_steps = :done, total_steps = :total WHERE id = :id")
                .param("done", doneSteps)
                .param("total", totalSteps)
                .param("id", runId)
                .update();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("doneSteps", doneSteps);
        payload.put("totalSteps", totalSteps);
        events.append(runId, "run_progress", payload);
    }

    @Transactional
    void finishSuccess(
            UUID runId,
            String status,
            long doneSteps,
            long totalSteps,
            long lastStep,
            int playedDays,
            long digest,
            CheckLevel level,
            @Nullable String netReturnPct,
            @Nullable BigDecimal finalEquity,
            @Nullable Boolean repeatMatch,
            Map<String, Object> report,
            List<JournalRecord> journal,
            List<StrategyDiagnostic> diagnostics) {
        jdbc.sql("SELECT 1 FROM simulation.run WHERE id = :id FOR UPDATE")
                .param("id", runId)
                .query(Integer.class)
                .single();
        jdbc.sql("""
                        UPDATE simulation.run
                        SET status = :status,
                            done_steps = :done,
                            total_steps = :total,
                            last_step = :lastStep,
                            played_days = :playedDays,
                            digest = :digest,
                            final_level = :level,
                            net_return_pct = :net,
                            final_equity = :equity,
                            finished_at = now()
                        WHERE id = :id AND status IN ('queued', 'running')
                        """)
                .param("status", status)
                .param("done", doneSteps)
                .param("total", totalSteps)
                .param("lastStep", lastStep)
                .param("playedDays", playedDays)
                .param("digest", digest)
                .param("level", level.name())
                .param("net", netReturnPct)
                .param("equity", finalEquity)
                .param("id", runId)
                .update();

        jdbc.sql("""
                        INSERT INTO simulation.run_result (run_id, report, journal, diagnostics, repeat_match)
                        VALUES (:id, CAST(:report AS jsonb), CAST(:journal AS jsonb), CAST(:diag AS jsonb), :repeatMatch)
                        """)
                .param("id", runId)
                .param("report", json.writeValueAsString(report))
                .param("journal", json.writeValueAsString(journal))
                .param("diag", json.writeValueAsString(diagnostics))
                .param("repeatMatch", repeatMatch)
                .update();

        Map<String, Object> finished = new LinkedHashMap<>();
        finished.put("status", status);
        finished.put("repeatMatch", repeatMatch);
        events.append(runId, "run_finished", finished);

        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("runId", runId);
        events.append(runId, "report_ready", ready);
    }

    @Transactional
    void finishFailed(UUID runId, ErrorCode errorCode) {
        Optional<String> current = jdbc.sql("SELECT status FROM simulation.run WHERE id = :id FOR UPDATE")
                .param("id", runId)
                .query(String.class)
                .optional();
        if (current.isEmpty() || (!"queued".equals(current.get()) && !"running".equals(current.get()))) {
            return;
        }
        jdbc.sql("""
                        UPDATE simulation.run
                        SET status = 'failed',
                            error_code = :errorCode,
                            finished_at = now()
                        WHERE id = :id AND status IN ('queued', 'running')
                        """).param("errorCode", errorCode.name()).param("id", runId).update();
        Map<String, Object> failed = new LinkedHashMap<>();
        failed.put("errorCode", errorCode.name());
        events.append(runId, "run_failed", failed);
    }

    HistoryData history(
            UUID userId, boolean blindOnly, @Nullable String status, @Nullable Regime regime, int page, int pageSize) {
        if ("finished_early".equals(status)) {
            return new HistoryData(List.of(), 0L);
        }
        StringBuilder where = new StringBuilder(" WHERE user_id = :user");
        if (blindOnly) {
            where.append(" AND blind");
        }
        if ("running".equals(status)) {
            where.append(" AND status IN ('queued', 'running')");
        } else if (status != null) {
            where.append(" AND status = :status");
        }
        if (regime != null) {
            where.append(" AND regime = :regime");
        }
        long total = bindHistory(jdbc.sql("SELECT count(*) FROM simulation.run" + where), userId, status, regime)
                .query(Long.class)
                .single();
        List<RunRecord> items = bindHistory(
                        jdbc.sql("SELECT * FROM simulation.run" + where
                                + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset"),
                        userId,
                        status,
                        regime)
                .param("limit", pageSize)
                .param("offset", (long) (page - 1) * pageSize)
                .query((rs, n) -> mapRun(rs))
                .list();
        return new HistoryData(items, total);
    }

    private static JdbcClient.StatementSpec bindHistory(
            JdbcClient.StatementSpec spec, UUID userId, @Nullable String status, @Nullable Regime regime) {
        spec.param("user", userId);
        if (status != null && !"running".equals(status)) {
            spec.param("status", status);
        }
        if (regime != null) {
            spec.param("regime", regime.code());
        }
        return spec;
    }

    private Run insertMinimal(UUID strategyVersionId, UUID snapshotId, @Nullable UUID repeatOf) {
        if (strategyVersions.find(strategyVersionId).isEmpty()) {
            throw new NotFoundException("Версия стратегии не найдена");
        }
        if (snapshots.find(snapshotId).isEmpty()) {
            throw new NotFoundException("Снапшот не найден");
        }
        UUID id = Uuid7.next();
        jdbc.sql("""
                        INSERT INTO simulation.run (id, strategy_version_id, snapshot_id, repeat_of_run_id)
                        VALUES (:id, :sv, :sn, :r)
                        """)
                .param("id", id)
                .param("sv", strategyVersionId)
                .param("sn", snapshotId)
                .param("r", repeatOf)
                .update();
        return find(id).orElseThrow();
    }

    private void insertPaper(
            UUID runId,
            int index,
            UUID paperId,
            String secid,
            String sector,
            long lotSize,
            BigDecimal priceStep,
            String name) {
        jdbc.sql("""
                        INSERT INTO simulation.run_paper (
                            run_id, paper_index, paper_id, secid, sector, lot_size, price_step, name
                        ) VALUES (:run, :idx, :paper, :secid, :sector, :lot, :step, :name)
                        """)
                .param("run", runId)
                .param("idx", index)
                .param("paper", paperId)
                .param("secid", secid)
                .param("sector", sector)
                .param("lot", lotSize)
                .param("step", priceStep)
                .param("name", name)
                .update();
    }

    private List<RunPaperRecord> papersOf(UUID runId) {
        return jdbc.sql("""
                        SELECT paper_index, paper_id, secid, sector, lot_size, price_step, name
                        FROM simulation.run_paper
                        WHERE run_id = :id
                        ORDER BY paper_index
                        """)
                .param("id", runId)
                .query((rs, n) -> new RunPaperRecord(
                        rs.getInt("paper_index"),
                        rs.getObject("paper_id", UUID.class),
                        rs.getString("secid"),
                        rs.getString("sector"),
                        rs.getLong("lot_size"),
                        rs.getBigDecimal("price_step"),
                        rs.getString("name")))
                .list();
    }

    private RunRecord mapRun(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        UUID snapshotId = rs.getObject("snapshot_id", UUID.class);
        String finalLevelRaw = rs.getString("final_level");
        return new RunRecord(
                id,
                rs.getObject("user_id", UUID.class),
                rs.getObject("strategy_version_id", UUID.class),
                snapshotId,
                snapshotKey(snapshotId),
                rs.getObject("repeat_of_run_id", UUID.class),
                rs.getBoolean("blind"),
                rs.getString("status"),
                rs.getInt("duration_days"),
                Timeframe.fromCode(rs.getString("timeframe")).orElseThrow(),
                Regime.fromCode(rs.getString("regime")).orElseThrow(),
                StartMode.valueOf(rs.getString("start_mode").toUpperCase(Locale.ROOT)),
                rs.getObject("period_start", LocalDate.class),
                rs.getObject("period_end", LocalDate.class),
                rs.getInt("trading_days"),
                rs.getInt("bars_count"),
                rs.getDouble("sigma"),
                rs.getDouble("mdd"),
                rs.getDouble("p33"),
                rs.getDouble("p80"),
                rs.getBigDecimal("initial_capital"),
                rs.getInt("warmup_bars"),
                new Costs(
                        rs.getBigDecimal("commission_rate"),
                        rs.getBigDecimal("exchange_fee_rate"),
                        rs.getBigDecimal("spread"),
                        rs.getBigDecimal("slippage_kappa"),
                        rs.getBigDecimal("liquidity_share")),
                parseOverrides(rs.getString("param_overrides")),
                rs.getLong("done_steps"),
                rs.getObject("total_steps", Long.class),
                rs.getObject("last_step", Long.class),
                rs.getObject("played_days", Integer.class),
                rs.getObject("digest", Long.class),
                finalLevelRaw == null ? null : CheckLevel.valueOf(finalLevelRaw),
                rs.getString("net_return_pct"),
                rs.getBigDecimal("final_equity"),
                rs.getString("error_code"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class),
                papersOf(id));
    }

    private Map<String, Double> parseOverrides(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        JsonNode node = json.readTree(raw);
        Map<String, Double> out = new LinkedHashMap<>();
        node.properties().forEach(e -> out.put(e.getKey(), e.getValue().asDouble()));
        return out;
    }

    private String snapshotKey(UUID snapshotId) {
        return snapshots
                .find(snapshotId)
                .map(Snapshot::snapshotKey)
                .orElseThrow(() -> new IllegalStateException("Снапшот прогона не найден: " + snapshotId));
    }
}
