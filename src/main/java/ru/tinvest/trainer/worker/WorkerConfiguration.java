package ru.tinvest.trainer.worker;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerProperties.class)
class WorkerConfiguration {

    @Bean(destroyMethod = "shutdownNow")
    ManagedChannel workerChannel(WorkerProperties properties) {
        return ManagedChannelBuilder.forTarget(properties.address())
                // внутренняя сеть, TLS вне скоупа
                .usePlaintext()
                .build();
    }

    @Bean
    SimulationWorkerClient simulationWorkerClient(ManagedChannel workerChannel, WorkerProperties properties) {
        return new GrpcSimulationWorkerClient(workerChannel, properties.deadline(), properties.runDeadline());
    }
}
