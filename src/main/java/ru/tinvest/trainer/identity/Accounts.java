package ru.tinvest.trainer.identity;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ru.tinvest.trainer.config.Uuid7;

/** Учётные записи (схема {@code identity}). Регистрации нет: записи заводит команда профилем {@code create-user}. */
@Service
class Accounts {

    /** Предел bcrypt: пароль длиннее 72 байт в UTF-8 не принимается. */
    static final int MAX_PASSWORD_BYTES = 72;

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;

    Accounts(JdbcClient jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    static boolean passwordTooLong(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES;
    }

    Optional<AccountPrincipal> findByEmail(String email) {
        return jdbc.sql("SELECT id, email, password_hash FROM identity.user_account WHERE lower(email) = :email")
                .param("email", normalizeEmail(email))
                .query((rs, n) -> new AccountPrincipal(
                        rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("password_hash")))
                .optional();
    }

    /** Создаёт пользователя; повторная почта (без учёта регистра) — {@code DuplicateKeyException}. */
    UUID create(String email, String password) {
        if (passwordTooLong(password)) {
            throw new IllegalArgumentException("Password is longer than " + MAX_PASSWORD_BYTES + " bytes");
        }
        UUID id = Uuid7.next();
        jdbc.sql("INSERT INTO identity.user_account (id, email, password_hash) VALUES (:id, :email, :hash)")
                .param("id", id)
                .param("email", normalizeEmail(email))
                .param("hash", passwordEncoder.encode(password))
                .update();
        return id;
    }
}
