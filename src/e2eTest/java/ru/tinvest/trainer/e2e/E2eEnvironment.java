package ru.tinvest.trainer.e2e;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Окружение сквозных тестов: ClickHouse с маленьким снапшотом, PostgreSQL и настоящий Simulation Worker (образ из
 * {@code core}, {@code -De2e.worker.image}, по умолчанию {@code simulation-worker:latest}) в одной сети. Контейнеры
 * общие на JVM: Spring кэширует контекст между классами, поэтому останавливает их только Ryuk Testcontainers.
 */
final class E2eEnvironment {

    static final String CLICKHOUSE_USER = "ttsc";
    static final String CLICKHOUSE_PASSWORD = "secret";
    static final String CLICKHOUSE_DB = "ttsc";

    static final MarketFixture FIXTURE = new MarketFixture();

    private static final Network NETWORK = Network.newNetwork();

    static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>("clickhouse/clickhouse-server:25.8-alpine")
            .withNetwork(NETWORK)
            .withNetworkAliases("clickhouse")
            .withEnv("CLICKHOUSE_USER", CLICKHOUSE_USER)
            .withEnv("CLICKHOUSE_PASSWORD", CLICKHOUSE_PASSWORD)
            .withEnv("CLICKHOUSE_DB", CLICKHOUSE_DB)
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123));

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static final GenericContainer<?> WORKER = new GenericContainer<>(
                    System.getProperty("e2e.worker.image", "simulation-worker:latest"))
            .withNetwork(NETWORK)
            .withEnv("WORKER_ADDRESS", "0.0.0.0:50051")
            .withEnv(
                    "CLICKHOUSE_URL",
                    "http://" + CLICKHOUSE_USER + ":" + CLICKHOUSE_PASSWORD + "@clickhouse:8123/" + CLICKHOUSE_DB)
            .withExposedPorts(50051)
            .waitingFor(Wait.forListeningPorts(50051).withStartupTimeout(Duration.ofSeconds(60)));

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static {
        // воркер пишет в stdout; при падении теста лог контейнера помогает понять причину
        WORKER.withLogConsumer(new Slf4jLogConsumer(org.slf4j.LoggerFactory.getLogger("e2e.worker")));
        CLICKHOUSE.start();
        FIXTURE.statements(E2eEnvironment::clickhouse);
        POSTGRES.start();
        WORKER.start();
    }

    private E2eEnvironment() {}

    static String clickhouseUrl() {
        return "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123);
    }

    static String workerAddress() {
        return WORKER.getHost() + ":" + WORKER.getMappedPort(50051);
    }

    /** Выполняет SQL в ClickHouse по HTTP (порт 8123). */
    static void clickhouse(String sql) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(clickhouseUrl() + "/?database=" + CLICKHOUSE_DB))
                .header("X-ClickHouse-User", CLICKHOUSE_USER)
                .header("X-ClickHouse-Key", CLICKHOUSE_PASSWORD)
                .POST(HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("ClickHouse " + response.statusCode() + ": " + response.body());
            }
        } catch (IOException e) {
            throw new IllegalStateException("ClickHouse недоступен", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
