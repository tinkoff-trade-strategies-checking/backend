package ru.tinvest.trainer.sessions;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.tinvest.trainer.config.Uuid7;

/** Хранилище пользовательских алертов по цене и RSI ({@code sessions.price_alert}, backend#95). */
@Service
public class PriceAlerts {

    public static final int MAX_ACTIVE_ALERTS_PER_USER = 50;

    private static final String COLUMNS =
            "id, user_id, ticker, condition_type, threshold_value, active, note, created_at";

    private final JdbcClient jdbc;

    PriceAlerts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<PriceAlert> listByUser(UUID userId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM sessions.price_alert WHERE user_id = :userId ORDER BY created_at DESC, id DESC")
                .param("userId", userId)
                .query(PriceAlerts::mapRow)
                .list();
    }

    @Transactional
    public PriceAlert create(
            UUID userId,
            String ticker,
            PriceAlert.ConditionType conditionType,
            BigDecimal thresholdValue,
            @Nullable String note) {
        Integer activeCount = jdbc.sql(
                        "SELECT COUNT(*) FROM sessions.price_alert WHERE user_id = :userId AND active = TRUE")
                .param("userId", userId)
                .query(Integer.class)
                .single();
        if (activeCount != null && activeCount >= MAX_ACTIVE_ALERTS_PER_USER) {
            throw new PriceAlertLimitException(
                    "Active alerts limit reached (max " + MAX_ACTIVE_ALERTS_PER_USER + " per user)");
        }
        UUID id = Uuid7.next();
        return jdbc.sql("INSERT INTO sessions.price_alert (id, user_id, ticker, condition_type, threshold_value, note) "
                        + "VALUES (:id, :userId, :ticker, :conditionType, :thresholdValue, :note) "
                        + "RETURNING " + COLUMNS)
                .param("id", id)
                .param("userId", userId)
                .param("ticker", ticker)
                .param("conditionType", conditionType.name())
                .param("thresholdValue", thresholdValue)
                .param("note", note)
                .query(PriceAlerts::mapRow)
                .single();
    }

    @Transactional
    public boolean deleteOwned(UUID userId, UUID alertId) {
        int deleted = jdbc.sql("DELETE FROM sessions.price_alert WHERE id = :id AND user_id = :userId")
                .param("id", alertId)
                .param("userId", userId)
                .update();
        return deleted > 0;
    }

    private static PriceAlert mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new PriceAlert(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("ticker"),
                PriceAlert.ConditionType.valueOf(rs.getString("condition_type")),
                rs.getBigDecimal("threshold_value"),
                rs.getBoolean("active"),
                rs.getString("note"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    /** Превышен лимит активных алертов пользователя (409 {@code CONFLICT}). */
    static final class PriceAlertLimitException extends RuntimeException {
        PriceAlertLimitException(String message) {
            super(message);
        }
    }
}
