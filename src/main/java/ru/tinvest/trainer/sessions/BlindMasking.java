package ru.tinvest.trainer.sessions;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.tinvest.trainer.marketdata.LaunchRejectReason;
import ru.tinvest.trainer.marketdata.LaunchRejection;

/**
 * Единственная точка маскирования ответов сессии (INV-1, INV-7; {@code architecture.md}). Всё, что уходит клиенту о
 * запуске и об отказе, строится здесь: слепая сессия не получает тикеров, названий, дат, рублёвых сумм, σ, MDD и
 * порогов; об отказе — только машинный код, поле и числа лимита, текст {@code detail} — константа без данных. Данные
 * бумаг и периода сессии вне этого класса наружу не передаются. Следующие операции сессии (снимок, события, заявки)
 * добавляют сюда свои виды ответа, а не маскируют сами.
 */
@Component
class BlindMasking {

    /** Ответ запуска ({@code CreatedSession}): только идентификатор, одинаково для слепой и неслепой сессии. */
    Map<String, Object> createdSession(UUID sessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        return body;
    }

    /** Тело 422 ({@code LaunchProblem}); {@code detail} и {@code instance} данных запроса не содержат. */
    Map<String, Object> launchProblem(LaunchRejection rejection) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", "Launch rejected");
        body.put("status", 422);
        body.put("detail", detail(rejection.reason()));
        body.put("instance", null);
        body.put("code", rejection.reason().name());
        body.put("field", rejection.field());
        body.put("barsCount", rejection.barsCount());
        body.put("maxBars", rejection.maxBars());
        return body;
    }

    private static String detail(LaunchRejectReason reason) {
        return switch (reason) {
            case NO_MATCHING_PERIOD -> "No suitable period for the selected parameters";
            case TOO_MANY_BARS -> "Too many candles for a manual session";
            case INVALID_PARAMS -> "Invalid launch parameters";
            case CORPORATE_ACTION_ON_PERIOD -> "A corporate action falls into the period";
        };
    }
}
