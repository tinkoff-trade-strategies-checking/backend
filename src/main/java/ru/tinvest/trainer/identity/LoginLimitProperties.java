package ru.tinvest.trainer.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Ограничение попыток входа за окно {@code window}: не больше {@code maxFailuresPerPair} на пару (почта, IP),
 * {@code maxFailuresPerIp} на IP и {@code maxFailuresPerEmail} на нормализованную почту со всех IP. Общий порог по почте не
 * действует на пару с успешным входом за последние {@code knownPairTtl} (не больше {@code maxKnownPairs} пар в
 * памяти); следующая попытка — 429.
 */
@ConfigurationProperties("identity.login-limit")
record LoginLimitProperties(
        @DefaultValue("5") int maxFailuresPerPair,
        @DefaultValue("50") int maxFailuresPerEmail,
        @DefaultValue("20") int maxFailuresPerIp,
        @DefaultValue("5m") Duration window,
        @DefaultValue("30d") Duration knownPairTtl,
        @DefaultValue("10000") int maxKnownPairs) {}
