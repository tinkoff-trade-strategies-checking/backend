package ru.tinvest.trainer.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WorkerPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void defaultDeadlinesAndMaxInboundMessageSize() {
        contextRunner.run(context -> {
            WorkerProperties props = context.getBean(WorkerProperties.class);
            assertThat(props.deadline()).isEqualTo(Duration.ofSeconds(30));
            assertThat(props.runDeadline()).isEqualTo(Duration.ofMinutes(10));
            assertThat(props.maxInboundMessageSize()).isEqualTo(268_435_456);
        });
    }

    @Test
    void workerConfigurationCreatesChannelWithConfiguredMaxInboundMessageSize() {
        new ApplicationContextRunner()
                .withUserConfiguration(WorkerConfiguration.class)
                .withPropertyValues("worker.max-inbound-message-size=134217728")
                .run(context -> {
                    WorkerProperties props = context.getBean(WorkerProperties.class);
                    assertThat(props.maxInboundMessageSize()).isEqualTo(134_217_728);
                    ManagedChannel channel = context.getBean(ManagedChannel.class);
                    assertThat(channel.isShutdown()).isFalse();
                });
    }

    @EnableConfigurationProperties(WorkerProperties.class)
    static class Config {}
}
