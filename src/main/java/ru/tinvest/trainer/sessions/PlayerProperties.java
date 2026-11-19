package ru.tinvest.trainer.sessions;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки плеера ({@code lab.session}). {@code disconnectPauseAfter} — сколько ручная сессия играет без подписчиков
 * SSE ({@code decisions.md}, «Завершение и восстановление сессии»); {@code minTick} — самый частый такт часов: на
 * больших скоростях за такт проходит несколько свечей; {@code heartbeat} — период пустых строк в потоке, по которым
 * обрыв клиента замечается без новых событий; отправка, не завершившаяся за {@code 2 * heartbeat}, обрывает
 * подписку медленного клиента; {@code eventRetention} — как долго хранятся события завершённой сессии;
 * {@code maxConcurrentTicks} и {@code maxConcurrentSends} — сколько тактов и отправок идёт одновременно.
 */
@ConfigurationProperties("lab.session")
record PlayerProperties(
        @DefaultValue("5s") Duration disconnectPauseAfter,
        @DefaultValue("200ms") Duration minTick,
        @DefaultValue("2s") Duration heartbeat,
        @DefaultValue("24h") Duration eventRetention,
        @DefaultValue("8") int maxConcurrentTicks,
        @DefaultValue("256") int maxConcurrentSends) {

    PlayerProperties {
        if (disconnectPauseAfter.isNegative()
                || minTick.isZero()
                || minTick.isNegative()
                || heartbeat.isZero()
                || eventRetention.isNegative()
                || maxConcurrentTicks < 1
                || maxConcurrentSends < 1) {
            throw new IllegalArgumentException("lab.session: неверные длительности");
        }
    }

    /** Период такта часов на скорости: одна свеча за {@code 1000 / speed} мс, но не чаще {@code minTick}. */
    Duration tickPeriod(int speed) {
        Duration perCandle = Duration.ofMillis(1000L / speed);
        return perCandle.compareTo(minTick) < 0 ? minTick : perCandle;
    }
}
