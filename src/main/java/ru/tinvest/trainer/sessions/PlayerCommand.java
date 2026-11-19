package ru.tinvest.trainer.sessions;

import java.util.Set;
import org.jspecify.annotations.Nullable;
import ru.tinvest.trainer.api.InvalidRequestException;
import tools.jackson.databind.JsonNode;

/**
 * Тело {@code POST .../player} ({@code PlayerCommand}): {@code action} и {@code speed}. Скорость обязательна при
 * {@code speed} и должна быть из {@link #SPEEDS}; при остальных действиях её нет или {@code null} (число — 400).
 */
record PlayerCommand(@Nullable String action, @Nullable JsonNode speed) {

    static final Set<Integer> SPEEDS = Set.of(1, 5, 10, 100);

    enum Action {
        PLAY,
        PAUSE,
        STEP,
        SPEED
    }

    /** Проверенная команда: скорость есть только у {@link Action#SPEED}. */
    record Valid(Action action, int speed) {

        String fingerprint() {
            return action + ":" + (action == Action.SPEED ? speed : "");
        }
    }

    /** @throws InvalidRequestException неизвестное действие или неверная скорость (400) */
    Valid validate() {
        if (action == null) {
            throw new InvalidRequestException("action is required");
        }
        Action parsed = switch (action) {
            case "play" -> Action.PLAY;
            case "pause" -> Action.PAUSE;
            case "step" -> Action.STEP;
            case "speed" -> Action.SPEED;
            default -> throw new InvalidRequestException("Unknown player action");
        };
        boolean absent = speed == null || speed.isNull();
        if (parsed != Action.SPEED) {
            if (!absent) {
                throw new InvalidRequestException("speed is allowed only with action speed");
            }
            return new Valid(parsed, 0);
        }
        if (absent || !speed.isIntegralNumber() || !speed.canConvertToInt() || !SPEEDS.contains(speed.intValue())) {
            throw new InvalidRequestException("speed must be one of 1, 5, 10, 100");
        }
        return new Valid(parsed, speed.intValue());
    }
}
