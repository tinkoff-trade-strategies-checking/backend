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
    private final LoginRateLimiter limiter = new LoginRateLimiter(
            new LoginLimitProperties(3, 10, 5, Duration.ofMinutes(5), Duration.ofDays(30), 2), clock);

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
        var limiter = new LoginRateLimiter(
                new LoginLimitProperties(5, 1000, 1000, Duration.ofMinutes(5), Duration.ofDays(30), 2), clock);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 100; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return limiter.tryAcquire("1.1.1.1", "a@x.ru").isEmpty();
                }));
            }
            start.countDown();
            long allowed = 0;
            for (var f : futures) {
                if (f.get()) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void knownPairIsNotBlockedByGlobalEmailThresholdButStillByPairLimit() {
        limiter.tryAcquire("7.7.7.7", "a@x.ru");
        limiter.recordSuccess("7.7.7.7", "a@x.ru");
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("3.3.3." + i, "a@x.ru")).isEmpty();
        }
        // чужие адреса упёрлись в общий порог, известная пара владельца входит
        assertThat(limiter.tryAcquire("4.4.4.4", "a@x.ru")).isPresent();
        assertThat(limiter.tryAcquire("7.7.7.7", "a@x.ru")).isEmpty();
        // лимит самой пары действует и для известной пары
        assertThat(limiter.tryAcquire("7.7.7.7", "a@x.ru")).isEmpty();
        assertThat(limiter.tryAcquire("7.7.7.7", "a@x.ru")).isEmpty();
        assertThat(limiter.tryAcquire("7.7.7.7", "a@x.ru")).isPresent();
    }

    @Test
    void knownPairExpiresAfterTtl() {
        limiter.recordSuccess("7.7.7.7", "a@x.ru");
        clock.now = clock.now.plus(Duration.ofDays(31));
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("3.3.3." + i, "a@x.ru");
        }
        assertThat(limiter.tryAcquire("7.7.7.7", "a@x.ru")).isPresent();
    }

    @Test
    void knownPairListIsBounded() {
        // в тесте список вмещает 2 пары: самая старая вытесняется
        limiter.recordSuccess("1.0.0.1", "b@x.ru");
        limiter.recordSuccess("1.0.0.2", "b@x.ru");
        limiter.recordSuccess("1.0.0.3", "b@x.ru");
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("6.6.6." + i, "b@x.ru");
        }
        assertThat(limiter.tryAcquire("1.0.0.1", "b@x.ru")).isPresent();
        assertThat(limiter.tryAcquire("1.0.0.3", "b@x.ru")).isEmpty();
    }

    @Test
    void successDoesNotClearGlobalEmailCounter() {
        for (int i = 0; i < 9; i++) {
            limiter.tryAcquire("3.3.3." + i, "a@x.ru");
        }
        limiter.tryAcquire("8.8.8.8", "a@x.ru");
        limiter.recordSuccess("8.8.8.8", "a@x.ru");
        // успех владельца снял только свою попытку: 9 чужих неудач остались, десятая блокирует
        assertThat(limiter.tryAcquire("3.3.3.9", "a@x.ru")).isEmpty();
        assertThat(limiter.tryAcquire("4.4.4.4", "a@x.ru")).isPresent();
    }

    @Test
    void releaseReturnsAttemptAndBlockedAttemptIsNotCounted() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("1.1.1.1", "a@x.ru");
        }
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("1.1.1.1", "a@x.ru")).isPresent();
        }
        limiter.release("1.1.1.1", "a@x.ru");
        // вернули одну из учтённых попыток, 429 в счётчики не попадали
        assertThat(limiter.tryAcquire("1.1.1.1", "a@x.ru")).isEmpty();
        assertThat(limiter.tryAcquire("1.1.1.1", "a@x.ru")).isPresent();
    }

    @Test
    void retryAfterIsAtLeastOneSecondNearWindowEnd() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("1.1.1.1", "a@x.ru");
        }
        clock.now = clock.now.plus(Duration.ofMinutes(5)).minusMillis(500);
        assertThat(limiter.tryAcquire("1.1.1.1", "a@x.ru")).contains(Duration.ofMillis(500));
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
