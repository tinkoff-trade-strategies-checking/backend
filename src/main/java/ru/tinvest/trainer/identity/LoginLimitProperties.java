package ru.tinvest.trainer.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Ограничение неудачных попыток входа: не больше {@code maxFailuresPerEmail} на нормализованную почту и
 * {@code maxFailuresPerIp} на IP за окно {@code window}; следующая попытка — 429.
 */
@ConfigurationProperties("identity.login-limit")
record LoginLimitProperties(
        @DefaultValue("5") int maxFailuresPerEmail,
        @DefaultValue("20") int maxFailuresPerIp,
        @DefaultValue("5m") Duration window) {}
