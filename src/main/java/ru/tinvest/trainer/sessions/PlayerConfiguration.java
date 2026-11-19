package ru.tinvest.trainer.sessions;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlayerProperties.class)
class PlayerConfiguration {

    private static final int TIMER_THREADS = 4;

    @Bean(destroyMethod = "close")
    SchedulerTimer playerTimer() {
        return new SchedulerTimer(TIMER_THREADS);
    }
}
