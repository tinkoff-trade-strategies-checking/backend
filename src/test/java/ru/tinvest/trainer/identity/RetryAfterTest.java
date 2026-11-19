package ru.tinvest.trainer.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryAfterTest {

    @Test
    void roundsUpAndIsAtLeastOneSecond() {
        assertThat(AuthController.retryAfterSeconds(Duration.ZERO)).isEqualTo(1);
        assertThat(AuthController.retryAfterSeconds(Duration.ofMillis(100))).isEqualTo(1);
        assertThat(AuthController.retryAfterSeconds(Duration.ofMillis(299_500))).isEqualTo(300);
        assertThat(AuthController.retryAfterSeconds(Duration.ofSeconds(240))).isEqualTo(240);
    }
}
