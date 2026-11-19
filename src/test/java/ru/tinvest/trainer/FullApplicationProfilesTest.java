package ru.tinvest.trainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Всё приложение (не отдельные конфигурации) в профиле {@code import}, в профиле {@code create-user} и в обычном
 * режиме: каждая зависимость между модулями должна быть либо везде, либо под тем же условием. Контейнер PostgreSQL —
 * общий с {@link PostgresIntegrationTest}.
 */
class FullApplicationProfilesTest extends PostgresIntegrationTest {

    /** Свойства — аргументами командной строки: application.yml с неразрешёнными плейсхолдерами их не перекроет. */
    private static String[] args(String... properties) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword()));
        for (String p : properties) {
            args.add("--" + p);
        }
        return args.toArray(String[]::new);
    }

    private static SpringApplicationBuilder app(WebApplicationType type) {
        return new SpringApplicationBuilder(TrainerApplication.class).web(type);
    }

    /** Корневая причина в цепочке, у которой есть сообщение. */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return String.valueOf(t.getMessage());
    }

    @Test
    void importProfileStartsWithoutPlannerAndWithoutClickHouseUrl() {
        // контекст собирается целиком; дальше упирается только в сам запуск импорта (нет --import.from), а не в бины
        assertThatThrownBy(() -> app(WebApplicationType.NONE)
                        .run(args(
                                "spring.profiles.active=import",
                                "import.clickhouse.url=http://localhost:1",
                                "import.clickhouse.database=d",
                                "import.clickhouse.user=u",
                                "import.clickhouse.password=p")))
                .satisfies(e -> {
                    assertThat(rootMessage(e)).contains("--import.from");
                    assertThat(e.toString())
                            .doesNotContain("UnsatisfiedDependency")
                            .doesNotContain("NoSuchBean");
                });
    }

    @Test
    void createUserProfileStartsWithoutPlanner() {
        try (ConfigurableApplicationContext ctx = app(WebApplicationType.NONE)
                .run(args(
                        "spring.profiles.active=create-user",
                        "user.email=full.app@example.com",
                        "user.password=s3cret"))) {
            assertThat(ctx.getBeanNamesForType(ru.tinvest.trainer.marketdata.LaunchPlanner.class))
                    .isEmpty();
        }
    }

    @Test
    void webApplicationWithoutClickHouseUrlFailsFastWithClearMessage() {
        assertThatThrownBy(() -> app(WebApplicationType.SERVLET).run(args("server.port=0")))
                .satisfies(e -> assertThat(rootMessage(e)).contains("MARKETDATA_CLICKHOUSE_URL"));
    }

    @Test
    void webApplicationStartsWithPlanner() {
        try (ConfigurableApplicationContext ctx = app(WebApplicationType.SERVLET)
                .run(args(
                        "server.port=0", "marketdata.clickhouse.url=http://localhost:1", "marketdata.warm-up=false"))) {
            assertThat(ctx.getBeanNamesForType(ru.tinvest.trainer.marketdata.LaunchPlanner.class))
                    .hasSize(1);
        }
    }
}
