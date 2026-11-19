package ru.tinvest.trainer.sessions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.tinvest.trainer.api.InvalidRequestException;
import ru.tinvest.trainer.marketdata.LaunchPlanner;
import ru.tinvest.trainer.marketdata.LaunchRequest;
import ru.tinvest.trainer.marketdata.Regime;
import ru.tinvest.trainer.marketdata.StartMode;
import ru.tinvest.trainer.marketdata.Timeframe;

/** Запуск ручной сессии (контракт {@code api/openapi.yaml}, {@code createSession}). */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SessionsController {

    private static final Pattern MONEY = Pattern.compile("^-?[0-9]+(\\.[0-9]{1,2})?$");

    private final LaunchService launchService;
    private final BlindMasking masking;

    SessionsController(LaunchService launchService, BlindMasking masking) {
        this.launchService = launchService;
        this.masking = masking;
    }

    @PostMapping("/sessions")
    ResponseEntity<Map<String, Object>> create(@RequestBody CreateSessionRequest body) {
        UUID id = launchService.launch(body.toLaunchRequest());
        return ResponseEntity.status(HttpStatus.CREATED).body(masking.createdSession(id));
    }

    @ExceptionHandler(LaunchRejectedException.class)
    ResponseEntity<Map<String, Object>> rejected(LaunchRejectedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(masking.launchProblem(e.rejection()));
    }

    /** Тело запроса как в контракте; перечисления приходят строками, неизвестные значения — 400. */
    record CreateSessionRequest(
            String traderKind,
            Integer durationDays,
            String timeframe,
            String regime,
            String startMode,
            LocalDate customStartDate,
            Integer paperCount,
            List<UUID> paperIds,
            String initialCapital) {

        LaunchRequest toLaunchRequest() {
            if (!"human".equals(traderKind)) {
                throw new InvalidRequestException("traderKind must be human");
            }
            if (durationDays == null) {
                throw new InvalidRequestException("durationDays is required");
            }
            Timeframe tf =
                    timeframe == null ? null : Timeframe.fromCode(timeframe).orElse(null);
            if (tf == null) {
                throw new InvalidRequestException("timeframe is invalid");
            }
            Regime r = regime == null ? null : Regime.fromCode(regime).orElse(null);
            if (r == null) {
                throw new InvalidRequestException("regime is invalid");
            }
            StartMode mode = switch (startMode == null ? "" : startMode) {
                case "random" -> StartMode.RANDOM;
                case "custom" -> StartMode.CUSTOM;
                default -> throw new InvalidRequestException("startMode is invalid");
            };
            if (!LaunchPlanner.DURATIONS.contains(durationDays)) {
                throw new InvalidRequestException("durationDays must be one of 20, 60, 120, 250");
            }
            // формат Money контракта: десятичная строка, без экспоненты, знака плюс и не-ASCII цифр
            if (initialCapital == null || !MONEY.matcher(initialCapital).matches()) {
                throw new InvalidRequestException("initialCapital must be a decimal string with up to 2 digits");
            }
            BigDecimal capital = new BigDecimal(initialCapital);
            return new LaunchRequest(durationDays, tf, r, mode, customStartDate, paperCount, paperIds, capital);
        }
    }
}
