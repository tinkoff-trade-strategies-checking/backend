package ru.tinvest.trainer.marketdata;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.tinvest.trainer.api.DependencyUnavailableException;
import ru.tinvest.trainer.api.InvalidRequestException;

/**
 * Серверный скринер акций MOEX ({@code GET /api/v1/instruments/screener}) и календарь рыночных событий
 * ({@code GET /api/v1/instruments/events}) по контракту {@code api/openapi.yaml} (backend#95, frontend#99).
 */
@RestController
@RequestMapping("/api/v1/instruments")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ScreenerEventsController {

    private static final int MAX_TICKER_LENGTH = 32;
    private static final Set<String> ALLOWED_EVENT_TYPES = Set.of("CBR_RATE", "EARNINGS", "DIVIDEND", "MACRO");

    private final LaunchPlanner launchPlanner;
    private final SecurityFundamentals fundamentals;
    private final InstrumentsCatalog catalog;

    ScreenerEventsController(
            LaunchPlanner launchPlanner, SecurityFundamentals fundamentals, InstrumentsCatalog catalog) {
        this.launchPlanner = launchPlanner;
        this.fundamentals = fundamentals;
        this.catalog = catalog;
    }

    @GetMapping("/screener")
    List<ScreenerPaper> listScreenerPapers() {
        Map<String, SecurityFundamental> latestByTicker = fundamentals.findAllLatest();
        List<PlannedPaper> papers;
        try {
            papers = launchPlanner.papers(null);
        } catch (DependencyUnavailableException ignored) {
            papers = List.of();
        }
        if (papers.isEmpty()) {
            return latestByTicker.values().stream()
                    .sorted(Comparator.comparing(SecurityFundamental::ticker))
                    .map(f -> ScreenerPaper.from(f.ticker(), f.ticker(), "", f))
                    .toList();
        }
        return papers.stream()
                .map(p -> {
                    String ticker = SecurityFundamentals.normalizeTicker(p.secid());
                    String name = p.name().isBlank() ? ticker : p.name();
                    String sector = p.sector() == null ? "" : p.sector();
                    return ScreenerPaper.from(ticker, name, sector, latestByTicker.get(ticker));
                })
                .toList();
    }

    @GetMapping("/events")
    List<MarketEventSpec.MarketEventSummary> listMarketEvents(
            @RequestParam(required = false) @Nullable String type,
            @RequestParam(required = false) @Nullable String ticker) {
        String normalizedType = null;
        if (type != null && !type.isBlank()) {
            normalizedType = type.trim().toUpperCase(Locale.ROOT);
            if (!ALLOWED_EVENT_TYPES.contains(normalizedType)) {
                throw new InvalidRequestException("type must be one of CBR_RATE, EARNINGS, DIVIDEND, MACRO");
            }
        }
        String normalizedTicker = null;
        if (ticker != null && !ticker.isBlank()) {
            normalizedTicker = SecurityFundamentals.normalizeTicker(ticker);
            if (normalizedTicker.isEmpty() || normalizedTicker.length() > MAX_TICKER_LENGTH) {
                throw new InvalidRequestException("Invalid ticker");
            }
        }
        return catalog.listMarketEvents(normalizedType, normalizedTicker).stream()
                .map(MarketEventSpec::toSummary)
                .toList();
    }
}
