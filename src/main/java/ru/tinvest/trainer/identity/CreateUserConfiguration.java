package ru.tinvest.trainer.identity;

import java.util.UUID;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;

/**
 * Создание учётной записи командой (регистрации нет) — профиль {@code create-user}, без веб-сервера:
 * {@code --spring.profiles.active=create-user --user.email=...}, пароль — переменная {@code USER_PASSWORD}
 * (предпочтительно, не попадает в список процессов) или {@code --user.password}.
 */
@Configuration
@Profile("create-user")
class CreateUserConfiguration {

    @Bean
    ApplicationRunner createUserRunner(Accounts accounts, Environment env) {
        return args -> {
            String email = env.getProperty("user.email", "");
            String password = env.getProperty("user.password", "");
            if (email.isBlank() || !email.contains("@") || password.isEmpty()) {
                throw new IllegalStateException("Задайте --user.email и пароль (USER_PASSWORD или --user.password)");
            }
            if (Accounts.passwordTooLong(password)) {
                throw new IllegalStateException(
                        "Пароль длиннее " + Accounts.MAX_PASSWORD_BYTES + " байт в UTF-8 (предел bcrypt)");
            }
            try {
                UUID id = accounts.create(email, password);
                System.out.println("Пользователь создан: " + Accounts.normalizeEmail(email) + " (" + id + ")");
            } catch (DuplicateKeyException e) {
                throw new IllegalStateException(
                        "Пользователь с такой почтой уже есть: " + Accounts.normalizeEmail(email), e);
            }
        };
    }
}
