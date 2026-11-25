package ru.tinvest.trainer.marketdata;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.tinvest.trainer.api.InvalidRequestException;

/**
 * Фундаментальные показатели бумаги для экрана запуска ({@code GET /api/v1/papers/{ticker}/fundamentals},
 * {@code api/openapi.yaml}). Неслепой каталог: доступен при выборе бумаги для сессии со своей датой.
 */
@RestController
@RequestMapping("/api/v1/papers")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityFundamentalsController {

    private static final int MAX_TICKER_LENGTH = 32;

    private final SecurityFundamentals fundamentals;

    SecurityFundamentalsController(SecurityFundamentals fundamentals) {
        this.fundamentals = fundamentals;
    }

    @GetMapping("/{ticker}/fundamentals")
    Map<String, @Nullable Object> paperFundamentals(@PathVariable String ticker) {
        String normalized = SecurityFundamentals.normalizeTicker(ticker);
        if (normalized.isEmpty() || normalized.length() > MAX_TICKER_LENGTH) {
            throw new InvalidRequestException("Invalid ticker");
        }
        Optional<SecurityFundamental> latest = fundamentals.findLatest(normalized);
        Map<String, @Nullable Object> out = new LinkedHashMap<>();
        out.put("ticker", normalized);
        out.put("available", latest.isPresent());
        out.put("snapshot", latest.map(SecurityFundamental::toSnapshotMap).orElse(null));
        return out;
    }
}
