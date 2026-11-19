package ru.tinvest.trainer.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.tinvest.trainer.PostgresIntegrationTest;

/** Профиль {@code create-user} без веб-сервера заводит пользователя с bcrypt-хэшем и почтой в нижнем регистре. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.profiles.active=create-user",
            "user.email=Cli.User@Example.com",
            "user.password=s3cret-пароль"
        })
class CreateUserProfileTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Test
    void profileCreatesUser() {
        String hash = jdbc.sql("SELECT password_hash FROM identity.user_account WHERE email = 'cli.user@example.com'")
                .query(String.class)
                .single();

        assertThat(hash).startsWith("$2");
        assertThat(encoder.matches("s3cret-пароль", hash)).isTrue();
    }
}
