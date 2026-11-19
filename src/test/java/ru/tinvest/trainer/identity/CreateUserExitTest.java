package ru.tinvest.trainer.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.tinvest.trainer.PostgresIntegrationTest;

/** Профиль {@code create-user} в отдельной JVM: пользователь создан, процесс завершился сам с кодом 0. */
class CreateUserExitTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void processExitsByItselfAfterCreatingTheUser() throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        ProcessBuilder builder = new ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                "ru.tinvest.trainer.TrainerApplication",
                "--spring.profiles.active=create-user",
                "--user.email=Exit.User@Example.com");
        builder.environment().put("USER_PASSWORD", "s3cret-пароль");
        builder.environment().put("SPRING_DATASOURCE_URL", pool.getJdbcUrl());
        builder.environment().put("SPRING_DATASOURCE_USERNAME", pool.getUsername());
        builder.environment().put("SPRING_DATASOURCE_PASSWORD", pool.getPassword());
        builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        boolean exited = process.waitFor(120, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        assertThat(exited).as("процесс завершился сам").isTrue();
        assertThat(process.exitValue()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM identity.user_account WHERE email = 'exit.user@example.com'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }
}
