package ru.tinvest.trainer.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WorkerPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void defaultRunDeadlineIsTenMinutes() {
        contextRunner.run(context -> {
            WorkerProperties props = context.getBean(WorkerProperties.class);
            assertThat(props.deadline()).isEqualTo(Duration.ofSeconds(5));
            assertThat(props.runDeadline()).isEqualTo(Duration.ofMinutes(10));
        });
    }

    @EnableConfigurationProperties(WorkerProperties.class)
    static class Config {}
}
