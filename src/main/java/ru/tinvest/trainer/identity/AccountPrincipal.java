package ru.tinvest.trainer.identity;

import java.io.Serial;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** Аутентифицированный пользователь в сессии: идентификатор и почта; роль всегда {@code USER}. */
final class AccountPrincipal extends User {

    @Serial
    private static final long serialVersionUID = 1L;

    static final String ROLE = "USER";

    private final UUID id;
    private final boolean guest;

    AccountPrincipal(UUID id, String email, String passwordHash) {
        this(id, email, passwordHash, false);
    }

    AccountPrincipal(UUID id, String email, String passwordHash, boolean guest) {
        super(email, passwordHash, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE)));
        this.id = id;
        this.guest = guest;
    }

    UUID id() {
        return id;
    }

    String email() {
        return getUsername();
    }

    boolean guest() {
        return guest;
    }
}
