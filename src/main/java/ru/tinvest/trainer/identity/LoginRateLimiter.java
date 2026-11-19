package ru.tinvest.trainer.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Ограничитель неудачных попыток входа в памяти одного экземпляра (скользящее окно): по IP и по почте. Блокировка
 * проверяется до вычисления bcrypt. Состояние не общее между экземплярами и теряется при рестарте.
 */
@Component
class LoginRateLimiter {

    private static final int PURGE_THRESHOLD = 10_000;

    private final LoginLimitProperties props;
    private final Clock clock;
    private final Map<String, Deque<Instant>> byEmail = new HashMap<>();
    private final Map<String, Deque<Instant>> byIp = new HashMap<>();

    @Autowired
    LoginRateLimiter(LoginLimitProperties props) {
        this(props, Clock.systemUTC());
    }

    LoginRateLimiter(LoginLimitProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    /** Через сколько можно повторить, если вход для этой пары заблокирован. */
    synchronized Optional<Duration> blockedFor(String ip, String email) {
        Instant now = clock.instant();
        Duration byEmailWait = wait(byEmail, email, props.maxFailuresPerEmail(), now);
        Duration byIpWait = wait(byIp, ip, props.maxFailuresPerIp(), now);
        Duration result = byEmailWait.compareTo(byIpWait) >= 0 ? byEmailWait : byIpWait;
        return result.isZero() ? Optional.empty() : Optional.of(result);
    }

    synchronized void recordFailure(String ip, String email) {
        Instant now = clock.instant();
        if (byEmail.size() > PURGE_THRESHOLD || byIp.size() > PURGE_THRESHOLD) {
            purge(now);
        }
        byEmail.computeIfAbsent(email, k -> new ArrayDeque<>()).addLast(now);
        byIp.computeIfAbsent(ip, k -> new ArrayDeque<>()).addLast(now);
    }

    /** Успешный вход сбрасывает счётчик почты; счётчик IP остаётся. */
    synchronized void clearEmail(String email) {
        byEmail.remove(email);
    }

    synchronized void reset() {
        byEmail.clear();
        byIp.clear();
    }

    private Duration wait(Map<String, Deque<Instant>> failures, String key, int max, Instant now) {
        Deque<Instant> times = failures.get(key);
        if (times == null) {
            return Duration.ZERO;
        }
        prune(times, now);
        if (times.isEmpty()) {
            failures.remove(key);
            return Duration.ZERO;
        }
        if (times.size() < max) {
            return Duration.ZERO;
        }
        Duration left = Duration.between(now, times.peekFirst().plus(props.window()));
        return left.isNegative() || left.isZero() ? Duration.ofSeconds(1) : left;
    }

    private void prune(Deque<Instant> times, Instant now) {
        Instant border = now.minus(props.window());
        while (!times.isEmpty() && !times.peekFirst().isAfter(border)) {
            times.removeFirst();
        }
    }

    private void purge(Instant now) {
        for (Map<String, Deque<Instant>> failures : java.util.List.of(byEmail, byIp)) {
            failures.values().removeIf(times -> {
                prune(times, now);
                return times.isEmpty();
            });
        }
    }
}
