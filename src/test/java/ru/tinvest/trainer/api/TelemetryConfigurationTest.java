package ru.tinvest.trainer.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Проверка телеметрии backend#69:
 * <ul>
 *   <li>метрики Prometheus и пробы Actuator доступны на отдельном внутреннем порту управления;</li>
 *   <li>{@code /actuator/prometheus} недоступен на основном порту HTTP API;</li>
 *   <li>{@code /api/health} и {@code /actuator/health} на основном порту остаются рабочими;</li>
 *   <li>структурированное JSON-логирование (logstash) включено по умолчанию и отключается в профиле {@code local}.</li>
 * </ul>
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
        classes = TelemetryConfigurationTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "spring.datasource.url=jdbc:postgresql://localhost:1/unused",
            "spring.datasource.username=unused",
            "spring.datasource.password=unused"
        })
class TelemetryConfigurationTest {

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private PrometheusMeterRegistry prometheusMeterRegistry;

    @Autowired
    private Environment environment;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void prometheusIsExposedOnlyOnManagementPort() throws Exception {
        assertThat(managementPort).isGreaterThan(0).isNotEqualTo(serverPort);
        assertThat(prometheusMeterRegistry).isNotNull();

        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> mgmtProm = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + "/actuator/prometheus"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(mgmtProm.statusCode()).isEqualTo(200);
        assertThat(mgmtProm.body()).contains("jvm_memory_used_bytes");
        assertThat(mgmtProm.body()).contains("application=\"trainer-backend\"");

        HttpResponse<String> mgmtHealth = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + "/actuator/health"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(mgmtHealth.statusCode()).isEqualTo(200);
        assertThat(mgmtHealth.body()).contains("\"status\":\"UP\"");

        HttpResponse<String> liveness = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + "/actuator/health/liveness"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(liveness.statusCode()).isEqualTo(200);

        HttpResponse<String> readiness = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + "/actuator/health/readiness"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(readiness.statusCode()).isEqualTo(200);

        HttpResponse<String> apiProm = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + "/actuator/prometheus"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(apiProm.statusCode()).isEqualTo(401);

        HttpResponse<String> apiHealth = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + "/api/health"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(apiHealth.statusCode()).isEqualTo(200);
        assertThat(apiHealth.body()).contains("\"status\":\"UP\"");

        HttpResponse<String> compatActuatorHealth = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + "/actuator/health"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(compatActuatorHealth.statusCode()).isEqualTo(200);
        assertThat(compatActuatorHealth.body()).contains("\"status\":\"UP\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void structuredJsonLoggingIsConfiguredAndFormatsValidJson(CapturedOutput output) throws IOException {
        assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("logstash");

        Map<String, Object> appYaml = (Map<String, Object>) new Yaml()
                .load(Files.readString(Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8));
        Map<String, Object> mgmt = (Map<String, Object>) appYaml.get("management");
        Map<String, Object> mgmtServer = (Map<String, Object>) mgmt.get("server");
        assertThat(String.valueOf(mgmtServer.get("port"))).isEqualTo("${MANAGEMENT_PORT:8081}");

        Map<String, Object> localYaml = (Map<String, Object>) new Yaml()
                .load(Files.readString(Path.of("src/main/resources/application-local.yml"), StandardCharsets.UTF_8));
        Map<String, Object> localLogging = (Map<String, Object>) localYaml.get("logging");
        Map<String, Object> localStructured = (Map<String, Object>) localLogging.get("structured");
        Map<String, Object> localFormat = (Map<String, Object>) localStructured.get("format");
        assertThat(String.valueOf(localFormat.get("console"))).isEqualTo("${LOGGING_STRUCTURED_FORMAT:}");

        Logger logger = LoggerFactory.getLogger(TelemetryConfigurationTest.class);
        logger.info("telemetry check {}", "ok");

        String jsonLine = output.getOut()
                .lines()
                .filter(line -> line.contains("telemetry check ok"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("JSON log line not found in output:\n" + output.getOut()));
        JsonNode node = objectMapper.readTree(jsonLine);
        assertThat(node.get("level").asString()).isEqualTo("INFO");
        assertThat(node.get("message").asString()).isEqualTo("telemetry check ok");
        assertThat(node.get("logger_name").asString()).isEqualTo(TelemetryConfigurationTest.class.getName());
        assertThat(node.has("@timestamp")).isTrue();
    }

    @Configuration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import({SecurityConfiguration.class, HealthController.class, ActuatorHealthCompatibilityController.class})
    static class TestApp {}
}
