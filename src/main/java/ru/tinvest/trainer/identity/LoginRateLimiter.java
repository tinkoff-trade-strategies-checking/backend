package ru.tinvest.trainer.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Ограничитель попыток входа в памяти одного экземпляра (скользящее окно): по паре (почта, IP), по IP и по почте со всех
 * IP. Общий порог по почте не действует на «известную» пару: пару с успешным входом помнят {@code knownPairTtl}
 * (размер списка ограничен), поэтому владельца почты со своего IP перебор с чужих адресов не блокирует. Блокировка
 * проверяется до вычисления bcrypt. Состояние не общее между экземплярами и теряется при рестарте (после него список
 * известных пар пуст).
 */
@Component
class LoginRateLimiter {

    private static final int PURGE_THRESHOLD = 10_000;
    private static final Duration PURGE_INTERVAL = Duration.ofSeconds(10);

    private final LoginLimitProperties props;
    private final Clock clock;
    private final Map<String, Deque<Instant>> byPair = new HashMap<>();
    private final Map<String, Deque<Instant>> byEmail = new HashMap<>();
    private final Map<String, Deque<Instant>> byIp = new HashMap<>();
    private final Map<String, Instant> knownPairs;
    private Instant lastPurge = Instant.MIN;

    @Autowired
    LoginRateLimiter(LoginLimitProperties props) {
        this(props, Clock.systemUTC());
    }

    LoginRateLimiter(LoginLimitProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        int max = props.maxKnownPairs();
        this.knownPairs = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
                return size() > max;
            }
        };
    }

    /**
     * Атомарно проверяет лимиты и, если вход не заблокирован, сразу учитывает попытку (до проверки пароля): пачка
     * параллельных запросов не проскакивает лимит. Успешный вход снимает учёт через {@link #recordSuccess}. Возвращает,
     * через сколько можно повторить, если вход заблокирован.
     */
    synchronized Optional<Duration> tryAcquire(String ip, String email) {
        Instant now = clock.instant();
        String pair = pairKey(ip, email);
        Duration result = wait(byPair, pair, props.maxFailuresPerPair(), now);
        if (!isKnown(pair, now)) {
            result = max(result, wait(byEmail, email, props.maxFailuresPerEmail(), now));
        }
        result = max(result, wait(byIp, ip, props.maxFailuresPerIp(), now));
        if (!result.isZero()) {
            return Optional.of(result);
        }
        if ((byPair.size() > PURGE_THRESHOLD || byEmail.size() > PURGE_THRESHOLD || byIp.size() > PURGE_THRESHOLD)
                && !now.isBefore(lastPurge.plus(PURGE_INTERVAL))) {
            lastPurge = now;
            purge(now);
        }
        byPair.computeIfAbsent(pair, k -> new ArrayDeque<>()).addLast(now);
        byEmail.computeIfAbsent(email, k -> new ArrayDeque<>()).addLast(now);
        byIp.computeIfAbsent(ip, k -> new ArrayDeque<>()).addLast(now);
        return Optional.empty();
    }

    /**
     * Успешный вход: сбрасывает счётчик пары, не засчитывает попытку в счётчики почты и IP и запоминает пару как
     * известную. Общий счётчик почты остаётся: чужие неудачи с других IP не стираются.
     */
    synchronized void recordSuccess(String ip, String email) {
        String pair = pairKey(ip, email);
        byPair.remove(pair);
        dropLast(byEmail, email);
        dropLast(byIp, ip);
        knownPairs.remove(pair);
        knownPairs.put(pair, clock.instant());
    }

    /** Возвращает попытку, учтённую {@link #tryAcquire}, если пароль не проверялся (сбой БД). */
    synchronized void release(String ip, String email) {
        dropLast(byPair, pairKey(ip, email));
        dropLast(byEmail, email);
        dropLast(byIp, ip);
    }

    private boolean isKnown(String pair, Instant now) {
        Instant since = knownPairs.get(pair);
        if (since == null) {
            return false;
        }
        if (!since.plus(props.knownPairTtl()).isAfter(now)) {
            knownPairs.remove(pair);
            return false;
        }
        return true;
    }

    private static void dropLast(Map<String, Deque<Instant>> failures, String key) {
        Deque<Instant> times = failures.get(key);
        if (times != null) {
            times.pollLast();
            if (times.isEmpty()) {
                failures.remove(key);
            }
        }
    }

    synchronized void reset() {
        byPair.clear();
        byEmail.clear();
        byIp.clear();
        knownPairs.clear();
    }

    private static String pairKey(String ip, String email) {
        return email + '\n' + ip;
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
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
        for (Map<String, Deque<Instant>> failures : java.util.List.of(byPair, byEmail, byIp)) {
            failures.values().removeIf(times -> {
                prune(times, now);
                return times.isEmpty();
            });
        }
    }
}
