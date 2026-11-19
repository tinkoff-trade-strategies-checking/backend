package ru.tinvest.trainer.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LoginRateLimiterTest {

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final LoginRateLimiter limiter =
            new LoginRateLimiter(new LoginLimitProperties(3, 5, Duration.ofMinutes(5)), clock);

    @Test
    void blocksAfterMaxFailuresPerEmailAndReportsRetryAfter() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.blockedFor("1.1.1." + i, "a@x.ru")).isEmpty();
            limiter.recordFailure("1.1.1." + i, "a@x.ru");
        }
        clock.now = clock.now.plusSeconds(60);

        assertThat(limiter.blockedFor("9.9.9.9", "a@x.ru")).contains(Duration.ofMinutes(4));
        assertThat(limiter.blockedFor("9.9.9.9", "b@x.ru")).isEmpty();
    }

    @Test
    void blocksAfterMaxFailuresPerIp() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("2.2.2.2", "user" + i + "@x.ru");
        }
        assertThat(limiter.blockedFor("2.2.2.2", "fresh@x.ru")).isPresent();
        assertThat(limiter.blockedFor("3.3.3.3", "fresh@x.ru")).isEmpty();
    }

    @Test
    void windowExpiresAndClearEmailResets() {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("1.1.1.1", "a@x.ru");
        }
        assertThat(limiter.blockedFor("1.1.1.1", "a@x.ru")).isPresent();
        limiter.clearEmail("a@x.ru");
        assertThat(limiter.blockedFor("1.1.1.1", "a@x.ru")).isEmpty();

        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("1.1.1.1", "a@x.ru");
        }
        clock.now = clock.now.plus(Duration.ofMinutes(5));
        assertThat(limiter.blockedFor("1.1.1.1", "a@x.ru")).isEmpty();
    }
}
