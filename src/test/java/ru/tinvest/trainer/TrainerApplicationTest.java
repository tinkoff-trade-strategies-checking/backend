package ru.tinvest.trainer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "marketdata.clickhouse.url=http://localhost:1")
class TrainerApplicationTest extends PostgresIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void contextLoadsWithPostgresAndFlywaySchemas() {
        List<String> schemas = jdbcClient.sql("""
                        SELECT schema_name
                        FROM information_schema.schemata
                        WHERE schema_name IN ('identity', 'marketdata', 'workshop', 'sessions', 'simulation', 'analysis')
                        """).query(String.class).list();

        assertThat(schemas)
                .containsExactlyInAnyOrder("identity", "marketdata", "workshop", "sessions", "simulation", "analysis");
    }

    @Test
    void healthEndpointsRespondUp() {
        RestClient client = RestClient.create("http://localhost:" + port);

        ResponseEntity<String> apiHealth =
                client.get().uri("/api/health").retrieve().toEntity(String.class);
        assertThat(apiHealth.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(apiHealth.getBody()).contains("\"status\":\"UP\"");

        ResponseEntity<String> actuatorHealth =
                client.get().uri("/actuator/health").retrieve().toEntity(String.class);
        assertThat(actuatorHealth.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(actuatorHealth.getBody()).contains("\"status\":\"UP\"");
    }
}
