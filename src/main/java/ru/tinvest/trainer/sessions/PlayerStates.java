package ru.tinvest.trainer.sessions;

import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Состояние плеера сессии ({@code sessions.player_state}): играет ли время и скорость. Строки создаются по первой
 * команде плеера; нет строки — пауза на ×1. Менять состояние — под блокировкой строки сессии.
 */
@Component
class PlayerStates {

    /**
     * Состояние плеера; {@code totalSteps} — число шагов шкалы, если оно уже известно; {@code cursorStep},
     * {@code cursorDay}, {@code cursorCandle} — день и номер свечи в дне на указанном шаге.
     */
    record Row(
            boolean playing,
            int speed,
            OptionalLong totalSteps,
            OptionalLong cursorStep,
            int cursorDay,
            int cursorCandle) {

        static final Row DEFAULT = new Row(false, 1, OptionalLong.empty(), OptionalLong.empty(), 0, 0);
    }

    private final JdbcClient jdbc;

    PlayerStates(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Row get(UUID sessionId) {
        return jdbc.sql("SELECT playing, speed, total_steps, cursor_step, cursor_day, cursor_candle "
                        + "FROM sessions.player_state WHERE session_id = :id")
                .param("id", sessionId)
                .query((rs, n) -> {
                    long total = rs.getLong("total_steps");
                    OptionalLong totalSteps = rs.wasNull() ? OptionalLong.empty() : OptionalLong.of(total);
                    long cursor = rs.getLong("cursor_step");
                    OptionalLong cursorStep = rs.wasNull() ? OptionalLong.empty() : OptionalLong.of(cursor);
                    return new Row(
                            rs.getBoolean("playing"),
                            rs.getInt("speed"),
                            totalSteps,
                            cursorStep,
                            rs.getInt("cursor_day"),
                            rs.getInt("cursor_candle"));
                })
                .optional()
                .orElse(Row.DEFAULT);
    }

    /** Запоминает день и номер свечи в дне на шаге курсора. */
    void setCursor(UUID sessionId, long step, int day, int candle) {
        jdbc.sql("INSERT INTO sessions.player_state (session_id, cursor_step, cursor_day, cursor_candle) "
                        + "VALUES (:id, :step, :day, :candle) ON CONFLICT (session_id) DO UPDATE SET "
                        + "cursor_step = :step, cursor_day = :day, cursor_candle = :candle, updated_at = now()")
                .param("id", sessionId)
                .param("step", step)
                .param("day", day)
                .param("candle", candle)
                .update();
    }

    void setPlaying(UUID sessionId, boolean playing) {
        upsert(sessionId, "playing = :value", "playing", playing);
    }

    void setSpeed(UUID sessionId, int speed) {
        upsert(sessionId, "speed = :value", "speed", speed);
    }

    void setTotalSteps(UUID sessionId, long totalSteps) {
        upsert(sessionId, "total_steps = :value", "total_steps", totalSteps);
    }

    private void upsert(UUID sessionId, String assignment, String column, Object value) {
        jdbc.sql("INSERT INTO sessions.player_state (session_id, " + column + ") VALUES (:id, :value) "
                        + "ON CONFLICT (session_id) DO UPDATE SET " + assignment + ", updated_at = now()")
                .param("id", sessionId)
                .param("value", value)
                .update();
    }

    /** Сессии, у которых время идёт: после перезапуска процесса таймеров у них нет. */
    List<UUID> playing() {
        return jdbc.sql("SELECT session_id FROM sessions.player_state WHERE playing")
                .query(UUID.class)
                .list();
    }
}
