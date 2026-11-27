package ru.tinvest.trainer.identity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ru.tinvest.trainer.config.Uuid7;

/** Учётные записи (схема {@code identity}): обычные пользователи и временные гостевые сессии (backend#88). */
@Service
class Accounts {

    /** Предел bcrypt: пароль длиннее 72 байт в UTF-8 не принимается. */
    static final int MAX_PASSWORD_BYTES = 72;
    /** Предел длины адреса по RFC 5321 (254 символа). */
    static final int MAX_EMAIL_LENGTH = 254;
    /** Допустимые значения режима интерфейса пользователя (backend#88). */
    static final Set<String> UI_MODES = Set.of("beginner", "pro");

    static final String DEFAULT_UI_MODE = "beginner";

    record UserProfile(UUID id, String email, boolean guest, String uiMode) {}

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
        return jdbc.sql("SELECT id, email, password_hash, is_guest FROM identity.user_account "
                        + "WHERE lower(email) = :email AND (guest_expires_at IS NULL OR guest_expires_at > now())")
                .param("email", normalizeEmail(email))
                .query((rs, n) -> new AccountPrincipal(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getString("password_hash"),
                        rs.getBoolean("is_guest")))
                .optional();
    }

    Optional<UserProfile> findProfile(UUID userId) {
        return jdbc.sql("SELECT id, email, is_guest, ui_mode FROM identity.user_account WHERE id = :id")
                .param("id", userId)
                .query((rs, n) -> new UserProfile(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getBoolean("is_guest"),
                        rs.getString("ui_mode")))
                .optional();
    }

    UserProfile updateUiMode(UUID userId, String uiMode) {
        if (!UI_MODES.contains(uiMode)) {
            throw new IllegalArgumentException("uiMode must be 'beginner' or 'pro'");
        }
        return jdbc.sql("UPDATE identity.user_account SET ui_mode = :uiMode WHERE id = :id "
                        + "RETURNING id, email, is_guest, ui_mode")
                .param("uiMode", uiMode)
                .param("id", userId)
                .query((rs, n) -> new UserProfile(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getBoolean("is_guest"),
                        rs.getString("ui_mode")))
                .single();
    }

    /** Создаёт временную гостевую учётную запись со сроком жизни {@code ttl} (backend#88). */
    AccountPrincipal createGuest(Duration ttl) {
        UUID id = Uuid7.next();
        String shortId = id.toString().replace("-", "").substring(0, 12);
        String email = "guest-" + shortId + "@demo.local";
        String passwordHash = passwordEncoder.encode(UUID.randomUUID().toString());
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plus(ttl);
        jdbc.sql("INSERT INTO identity.user_account (id, email, password_hash, ui_mode, is_guest, guest_expires_at) "
                        + "VALUES (:id, :email, :hash, :uiMode, TRUE, :expiresAt)")
                .param("id", id)
                .param("email", email)
                .param("hash", passwordHash)
                .param("uiMode", DEFAULT_UI_MODE)
                .param("expiresAt", expiresAt)
                .update();
        return new AccountPrincipal(id, email, passwordHash, true);
    }

    /** Создаёт пользователя; повторная почта (без учёта регистра) — {@code DuplicateKeyException}. */
    UUID create(String email, String password) {
        if (passwordTooLong(password)) {
            throw new IllegalArgumentException("Password is longer than " + MAX_PASSWORD_BYTES + " bytes");
        }
        if (normalizeEmail(email).length() > MAX_EMAIL_LENGTH) {
            throw new IllegalArgumentException("Email is longer than " + MAX_EMAIL_LENGTH + " characters");
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
