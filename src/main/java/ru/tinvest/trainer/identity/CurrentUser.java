package ru.tinvest.trainer.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Публичный API модуля: идентификатор пользователя текущего запроса. Для других модулей — единственный способ узнать,
 * кто вошёл; на защищённых {@code /api/v1/**} пользователь всегда есть.
 */
@Component
public class CurrentUser {

    public Optional<UUID> findId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AccountPrincipal principal) {
            return Optional.of(principal.id());
        }
        return Optional.empty();
    }

    /** Идентификатор вошедшего пользователя; вне аутентифицированного запроса — ошибка программиста. */
    public UUID requireId() {
        return findId().orElseThrow(() -> new IllegalStateException("No authenticated user in the current request"));
    }
}
