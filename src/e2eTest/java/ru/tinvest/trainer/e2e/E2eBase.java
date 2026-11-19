package ru.tinvest.trainer.e2e;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.tinvest.trainer.marketdata.Snapshots;

/**
 * Полное приложение на настоящем сервере (случайный порт) против ClickHouse, PostgreSQL и воркера ядра из
 * {@link E2eEnvironment}. Запросы идёт по HTTP клиентом {@link ApiClient}, как это делает браузер.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // подбор запуска читает архив по запросу, фоновый прогрев кэша не нужен
            "marketdata.warm-up=false",
            // IMOEX лежит в securities (его требует воркер для бенчмарка), но торговать им нельзя: пул — три бумаги
            "marketdata.pool=SBER,GAZP,LKOH",
            "analysis.report-build.retry-interval=1s",
            // настоящий расчёт ядра дольше заглушки
            "worker.deadline=30s"
        })
abstract class E2eBase {

    static final String PASSWORD = "correct horse";

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    Snapshots snapshots;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", E2eEnvironment.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", E2eEnvironment.POSTGRES::getUsername);
        registry.add("spring.datasource.password", E2eEnvironment.POSTGRES::getPassword);
        registry.add("marketdata.clickhouse.url", E2eEnvironment::clickhouseUrl);
        registry.add("marketdata.clickhouse.database", () -> E2eEnvironment.CLICKHOUSE_DB);
        registry.add("marketdata.clickhouse.user", () -> E2eEnvironment.CLICKHOUSE_USER);
        registry.add("marketdata.clickhouse.password", () -> E2eEnvironment.CLICKHOUSE_PASSWORD);
        registry.add("worker.address", E2eEnvironment::workerAddress);
    }

    @BeforeEach
    void registerSnapshot() {
        // снапшот готов в ClickHouse (snapshots_ready); приложению он известен по строке в PostgreSQL, как после
        // импорта
        if (snapshots.findByKey(MarketFixture.SNAPSHOT).isEmpty()) {
            snapshots.register(MarketFixture.SNAPSHOT, "e2e");
        }
    }

    /** Новый пользователь и клиент, уже вошедший в систему. */
    ApiClient newUser() {
        String email = "e2e-" + UUID.randomUUID() + "@example.com";
        jdbc.sql("INSERT INTO identity.user_account (id, email, password_hash) VALUES (:id, :e, :h)")
                .param("id", UUID.randomUUID())
                .param("e", email)
                .param("h", encoder.encode(PASSWORD))
                .update();
        ApiClient client = new ApiClient(port);
        client.login(email, PASSWORD);
        return client;
    }

    static LocalDate day(int index) {
        return E2eEnvironment.FIXTURE.days().get(index);
    }
}
