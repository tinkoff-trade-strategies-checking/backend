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
            new LoginRateLimiter(new LoginLimitProperties(3, 10, 5, Duration.ofMinutes(5)), clock);

    @Test
    void blocksPairAfterMaxAttemptsAndReportsRetryAfterButNotOtherIpOrEmail() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("1.1.1.1", "a@x.ru")).isEmpty();
        }
        clock.now = clock.now.plusSeconds(60);

        assertThat(limiter.tryAcquire("1.1.1.1", "a@x.ru")).contains(Duration.ofMinutes(4));
        // владелец почты с другого адреса и другая почта с того же адреса не заблокированы
        assertThat(limiter.tryAcquire("9.9.9.9", "a@x.ru")).isEmpty();
        assertThat(limiter.tryAcquire("1.1.1.1", "b@x.ru")).isEmpty();
    }

    @Test
    void blocksEmailAcrossManyIpsOnlyAfterHighThreshold() {
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("3.3.3." + i, "a@x.ru")).isEmpty();
        }
        assertThat(limiter.tryAcquire("4.4.4.4", "a@x.ru")).isPresent();
        assertThat(limiter.tryAcquire("4.4.4.4", "b@x.ru")).isEmpty();
    }

    @Test
    void blocksAfterMaxAttemptsPerIp() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("2.2.2.2", "user" + i + "@x.ru")).isEmpty();
        }
        assertThat(limiter.tryAcquire("2.2.2.2", "fresh@x.ru")).isPresent();
        assertThat(limiter.tryAcquire("3.3.3.3", "fresh@x.ru")).isEmpty();
    }

    @Test
    void acquireIsAtomicUnderConcurrency() throws Exception {
        var limiter = new LoginRateLimiter(new LoginLimitProperties(5, 1000, 1000, Duration.ofMinutes(5)), clock);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
            for (int i = 0; i < 100; i++) {
                tasks.add(() -> limiter.tryAcquire("1.1.1.1", "a@x.ru").isEmpty());
            }
            long allowed = pool.invokeAll(tasks).stream()
                    .filter(f -> {
                        try {
                            return f.get();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .count();
            assertThat(allowed).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void windowExpiresAndSuccessResets() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("1.1.1.1", "a@x.ru");
        }
        assertThat(limiter.tryAcquire("1.1.1.1", "a@x.ru")).isPresent();
        limiter.recordSuccess("1.1.1.1", "a@x.ru");
        assertThat(limiter.tryAcquire("1.1.1.1", "a@x.ru")).isEmpty();

        for (int i = 0; i < 2; i++) {
            limiter.tryAcquire("1.1.1.1", "a@x.ru");
        }
        clock.now = clock.now.plus(Duration.ofMinutes(5));
        assertThat(limiter.tryAcquire("1.1.1.1", "a@x.ru")).isEmpty();
    }
}
