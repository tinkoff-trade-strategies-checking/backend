package ru.tinvest.trainer.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Ограничение попыток входа за окно {@code window}: не больше {@code maxFailuresPerPair} на пару (почта, IP),
 * {@code maxFailuresPerIp} на IP и {@code maxFailuresPerEmail} на нормализованную почту со всех IP (высокий порог:
 * иначе перебором можно заблокировать владельца почты); следующая попытка — 429.
 */
@ConfigurationProperties("identity.login-limit")
record LoginLimitProperties(
        @DefaultValue("5") int maxFailuresPerPair,
        @DefaultValue("50") int maxFailuresPerEmail,
        @DefaultValue("20") int maxFailuresPerIp,
        @DefaultValue("5m") Duration window) {}
