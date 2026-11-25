package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Экран запуска: допустимые значения ({@code GET /launch/options}) и каталог бумаг для режима «своя дата»
 * ({@code GET /papers}) по контракту {@code api/openapi.yaml}. Каталог неслепой: тикер и сектор допустимы, потому что
 * бумагу выбирают для сессии со своей датой.
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class LaunchCatalogController {

    /** Название фонового алгоритма ручной сессии по умолчанию (decisions.md, «Фоновый алгоритм»). */
    static final String BACKGROUND_ALGORITHM = "Эталон платформы";

    static final int DEFAULT_PAPER_COUNT = 3;

    private final LaunchPlanner planner;
    private final MarketdataProperties properties;

    LaunchCatalogController(LaunchPlanner planner, MarketdataProperties properties) {
        this.planner = planner;
        this.properties = properties;
    }

    @GetMapping("/launch/options")
    LaunchOptionsResponse options() {
        MarketdataProperties.Execution e = properties.execution();
        Map<String, Integer> maxDurations = new LinkedHashMap<>();
        planner.maxDurations(null).forEach((timeframe, days) -> maxDurations.put(timeframe.code(), days));
        LaunchPlanner.DateRange archiveRange = planner.archiveDateRange(null);
        int maxPapers = Math.max(LaunchPlanner.MIN_PAPERS, planner.papers(null).size());
        return new LaunchOptionsResponse(
                LaunchPlanner.DURATIONS.stream().sorted().toList(),
                LaunchPlanner.DEFAULT_DURATION,
                maxDurations.keySet().stream().toList(),
                maxDurations,
                Timeframe.D1.code(),
                List.of(Regime.CALM.code(), Regime.MEDIUM.code(), Regime.STRESS.code()),
                Regime.MEDIUM.code(),
                LaunchPlanner.CAPITAL_PRESETS.stream()
                        .map(LaunchCatalogController::money)
                        .toList(),
                money(LaunchPlanner.DEFAULT_CAPITAL),
                BACKGROUND_ALGORITHM,
                LaunchPlanner.MIN_PAPERS,
                maxPapers,
                DEFAULT_PAPER_COUNT,
                new ExecutionRules(
                        e.commissionPct(),
                        e.exchangeFeePct(),
                        e.spreadPct(),
                        e.slippageKappa(),
                        e.liquidityLimitPct(),
                        properties.maxBars()),
                archiveRange == null ? null : archiveRange.from(),
                archiveRange == null ? null : archiveRange.to());
    }

    @GetMapping("/papers")
    List<LaunchPaper> papers() {
        // название — SHORTNAME из ISS; нет в снапшоте (загружен без названий) — тикер
        return planner.papers(null).stream()
                .map(p ->
                        new LaunchPaper(p.id(), p.sector(), p.secid(), p.name().isBlank() ? p.secid() : p.name()))
                .toList();
    }

    static String money(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }

    record ExecutionRules(
            String commissionPct,
            String exchangeFeePct,
            String spreadPct,
            String slippageKappa,
            String liquidityLimitPct,
            int maxBars) {}

    record LaunchOptionsResponse(
            List<Integer> durations,
            int defaultDuration,
            List<String> availableTimeframes,
            Map<String, Integer> maxDurationDaysByTimeframe,
            String defaultTimeframe,
            List<String> regimes,
            String defaultRegime,
            List<String> capitalPresets,
            String defaultCapital,
            String backgroundAlgorithmName,
            int minPapers,
            int maxPapers,
            int defaultPaperCount,
            ExecutionRules executionRules,
            @Nullable LocalDate archiveFrom,
            @Nullable LocalDate archiveTo) {}

    record LaunchPaper(UUID id, String sector, String ticker, String name) {}
}
