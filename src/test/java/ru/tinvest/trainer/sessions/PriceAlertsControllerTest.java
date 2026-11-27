package ru.tinvest.trainer.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import ru.tinvest.trainer.api.InvalidRequestException;
import ru.tinvest.trainer.api.NotFoundException;
import ru.tinvest.trainer.identity.CurrentUser;

class PriceAlertsControllerTest {

    private static final UUID USER_ID = UUID.fromString("01960000-0000-7000-8000-000000000001");

    @Test
    void validatesCreateAndDeleteInputsAndFormatsThreshold() {
        CurrentUser currentUser = new CurrentUser() {
            @Override
            public Optional<UUID> findId() {
                return Optional.of(USER_ID);
            }
        };

        PriceAlerts stubAlerts = new PriceAlerts(null) {
            @Override
            public List<PriceAlert> listByUser(UUID userId) {
                return List.of();
            }

            @Override
            public PriceAlert create(
                    UUID userId,
                    String ticker,
                    PriceAlert.ConditionType conditionType,
                    BigDecimal thresholdValue,
                    String note) {
                return new PriceAlert(
                        UUID.fromString("01960000-0000-7000-8000-000000000002"),
                        userId,
                        ticker,
                        conditionType,
                        thresholdValue,
                        true,
                        note,
                        OffsetDateTime.of(2026, 4, 10, 12, 0, 0, 0, ZoneOffset.UTC));
            }

            @Override
            public boolean deleteOwned(UUID userId, UUID alertId) {
                return false;
            }
        };

        PriceAlertsController controller = new PriceAlertsController(stubAlerts, currentUser);

        ResponseEntity<PriceAlert.PriceAlertResponse> created = controller.createAlert(
                new PriceAlertsController.CreatePriceAlertBody(" sber ", "price_above", "310.5", "  Уровень  "));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().ticker()).isEqualTo("SBER");
        assertThat(created.getBody().conditionType()).isEqualTo("PRICE_ABOVE");
        assertThat(created.getBody().thresholdValue()).isEqualTo("310.50");
        assertThat(created.getBody().note()).isEqualTo("Уровень");

        assertThatThrownBy(() -> controller.createAlert(
                        new PriceAlertsController.CreatePriceAlertBody("", "PRICE_ABOVE", "100", null)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> controller.createAlert(
                        new PriceAlertsController.CreatePriceAlertBody("SBER", "BAD_COND", "100", null)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> controller.createAlert(
                        new PriceAlertsController.CreatePriceAlertBody("SBER", "PRICE_BELOW", "-10", null)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> controller.createAlert(
                        new PriceAlertsController.CreatePriceAlertBody("SBER", "RSI_ABOVE", "101", null)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> controller.createAlert(
                        new PriceAlertsController.CreatePriceAlertBody("SBER", "PRICE_ABOVE", "1.12345", null)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> controller.createAlert(
                        new PriceAlertsController.CreatePriceAlertBody("SBER", "PRICE_ABOVE", "100", "x".repeat(201))))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> controller.deleteAlert("not-a-uuid")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> controller.deleteAlert("01960000-0000-7000-8000-000000000099"))
                .isInstanceOf(NotFoundException.class);

        ProblemDetail limitProblem =
                controller.handleAlertLimit(new PriceAlerts.PriceAlertLimitException("limit reached"));
        assertThat(limitProblem.getStatus()).isEqualTo(409);
        assertThat(limitProblem.getProperties()).containsEntry("code", "CONFLICT");
    }
}
