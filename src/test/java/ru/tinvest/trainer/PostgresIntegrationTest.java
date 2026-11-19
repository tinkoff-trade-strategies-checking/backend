package ru.tinvest.trainer;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * База для интеграционных тестов с PostgreSQL и миграциями Flyway. Контейнер один на JVM: Spring кэширует контекст
 * между классами, поэтому контейнер нельзя останавливать после первого класса.
 */
// Подбор запуска обязателен, поэтому в тестах ClickHouse — заглушка; реальные запросы проверяет
// ClickHouseArchiveCatalogTest.
@SpringBootTest(
        properties = {"marketdata.clickhouse.url=http://localhost:1", "spring.datasource.hikari.maximum-pool-size=8"})
public abstract class PostgresIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    static {
        postgres.start();
    }
}
