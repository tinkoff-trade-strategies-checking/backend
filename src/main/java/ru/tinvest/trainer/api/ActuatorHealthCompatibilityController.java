package ru.tinvest.trainer.api;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Совместимость для {@code GET /actuator/health} на основном порту ({@code :8080}), когда Actuator вынесен на
 * отдельный внутренний порт управления ({@code management.server.port}, по умолчанию {@code 8081}).
 * Сохраняет работоспособность базового healthcheck в {@code deploy/docker-compose.yml} и интеграционных тестов.
 */
@RestController
@Conditional(ActuatorHealthCompatibilityController.SeparateManagementPortCondition.class)
class ActuatorHealthCompatibilityController {

    @GetMapping("/actuator/health")
    HealthController.HealthResponse actuatorHealth() {
        return new HealthController.HealthResponse("UP");
    }

    static final class SeparateManagementPortCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            String mgmtPort = env.getProperty("management.server.port");
            if (mgmtPort == null || mgmtPort.isBlank() || "-1".equals(mgmtPort.trim())) {
                return false;
            }
            String trimmed = mgmtPort.trim();
            if ("0".equals(trimmed)) {
                return true;
            }
            String serverPort = env.getProperty("server.port", "8080");
            return !trimmed.equals(serverPort.trim());
        }
    }
}
