package ru.tinvest.trainer.sessions;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.tinvest.trainer.api.ErrorCode;
import ru.tinvest.trainer.api.InvalidRequestException;
import ru.tinvest.trainer.api.NotFoundException;
import ru.tinvest.trainer.identity.CurrentUser;

/**
 * Пользовательские алерты по цене и RSI ({@code /api/v1/alerts/**}, {@code api/openapi.yaml}, backend#95).
 */
@RestController
@RequestMapping("/api/v1/alerts")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class PriceAlertsController {

    private static final int MAX_TICKER_LENGTH = 32;
    private static final int MAX_NOTE_LENGTH = 200;
    private static final BigDecimal MAX_THRESHOLD = new BigDecimal("1000000000");
    private static final BigDecimal MAX_RSI = new BigDecimal("100");

    private final PriceAlerts alerts;
    private final CurrentUser currentUser;

    PriceAlertsController(PriceAlerts alerts, CurrentUser currentUser) {
        this.alerts = alerts;
        this.currentUser = currentUser;
    }

    @GetMapping
    List<PriceAlert.PriceAlertResponse> listAlerts() {
        UUID userId = currentUser.requireId();
        return alerts.listByUser(userId).stream().map(PriceAlert::toResponse).toList();
    }

    @PostMapping
    ResponseEntity<PriceAlert.PriceAlertResponse> createAlert(@RequestBody CreatePriceAlertBody body) {
        if (body == null) {
            throw new InvalidRequestException("Request body is required");
        }
        String ticker = parseTicker(body.ticker());
        PriceAlert.ConditionType conditionType = parseConditionType(body.conditionType());
        BigDecimal threshold = parseThreshold(body.thresholdValue(), conditionType);
        String note = parseNote(body.note());
        UUID userId = currentUser.requireId();
        PriceAlert created = alerts.create(userId, ticker, conditionType, threshold, note);
        return ResponseEntity.status(HttpStatus.CREATED).body(created.toResponse());
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> deleteAlert(@PathVariable String id) {
        UUID alertId = parseUuid(id);
        UUID userId = currentUser.requireId();
        if (!alerts.deleteOwned(userId, alertId)) {
            throw new NotFoundException("Price alert not found");
        }
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(PriceAlerts.PriceAlertLimitException.class)
    ProblemDetail handleAlertLimit(PriceAlerts.PriceAlertLimitException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Conflict");
        ErrorCode.CONFLICT.applyTo(problem);
        return problem;
    }

    record CreatePriceAlertBody(
            @Nullable String ticker,
            @Nullable String conditionType,
            @Nullable String thresholdValue,
            @Nullable String note) {}

    private static String parseTicker(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException("ticker is required");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > MAX_TICKER_LENGTH) {
            throw new InvalidRequestException("ticker must be at most 32 characters");
        }
        return normalized;
    }

    private static PriceAlert.ConditionType parseConditionType(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException("conditionType is required");
        }
        try {
            return PriceAlert.ConditionType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("conditionType must be PRICE_ABOVE, PRICE_BELOW, RSI_BELOW or RSI_ABOVE");
        }
    }

    private static BigDecimal parseThreshold(@Nullable String raw, PriceAlert.ConditionType conditionType) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException("thresholdValue is required");
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("Invalid thresholdValue");
        }
        if (parsed.compareTo(BigDecimal.ZERO) <= 0 || parsed.compareTo(MAX_THRESHOLD) > 0) {
            throw new InvalidRequestException("thresholdValue must be positive and within allowed range");
        }
        if (parsed.stripTrailingZeros().scale() > 4) {
            throw new InvalidRequestException("thresholdValue must have at most 4 decimal places");
        }
        if ((conditionType == PriceAlert.ConditionType.RSI_BELOW || conditionType == PriceAlert.ConditionType.RSI_ABOVE)
                && parsed.compareTo(MAX_RSI) > 0) {
            throw new InvalidRequestException("RSI thresholdValue must be in (0, 100]");
        }
        return parsed;
    }

    private static @Nullable String parseNote(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_NOTE_LENGTH) {
            throw new InvalidRequestException("note must be at most 200 characters");
        }
        return trimmed;
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("id must be a UUID");
        }
    }
}
