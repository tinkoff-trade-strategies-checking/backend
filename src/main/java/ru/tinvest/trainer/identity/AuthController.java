package ru.tinvest.trainer.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.tinvest.trainer.api.InvalidRequestException;

/** Вход, выход и текущий пользователь (контракт {@code api/openapi.yaml}, тег {@code identity}). */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final LoginRateLimiter limiter;
    private final String sessionCookieName;
    private final SecurityContextRepository contextRepository;
    private final CsrfTokenRepository csrfTokenRepository;

    AuthController(
            AuthenticationManager authenticationManager,
            LoginRateLimiter limiter,
            SecurityContextRepository contextRepository,
            CsrfTokenRepository csrfTokenRepository,
            @Value("${server.servlet.session.cookie.name:SESSION}") String sessionCookieName) {
        this.authenticationManager = authenticationManager;
        this.limiter = limiter;
        this.sessionCookieName = sessionCookieName;
        this.contextRepository = contextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    @PostMapping("/auth/login")
    ResponseEntity<?> login(@RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        if (body.email() == null
                || body.email().isBlank()
                || !body.email().contains("@")
                || body.password() == null
                || body.password().isEmpty()) {
            throw new InvalidRequestException("email and password are required");
        }
        if (Accounts.passwordTooLong(body.password())) {
            throw new InvalidRequestException("password is longer than " + Accounts.MAX_PASSWORD_BYTES + " bytes");
        }

        String email = Accounts.normalizeEmail(body.email());
        if (email.length() > Accounts.MAX_EMAIL_LENGTH) {
            throw new InvalidRequestException("email is longer than " + Accounts.MAX_EMAIL_LENGTH + " characters");
        }
        String ip = request.getRemoteAddr();
        Duration blocked = limiter.tryAcquire(ip, email).orElse(null);
        if (blocked != null) {
            // bcrypt не вычисляется
            log.warn("Вход заблокирован ограничителем попыток, ip={}", ip);
            ProblemDetail problem =
                    ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts");
            problem.setTitle("Too Many Requests");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds(blocked)))
                    .body(problem);
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(email, body.password()));
        } catch (InternalAuthenticationServiceException e) {
            // Сбой при загрузке пользователя (БД и т. п.) — не «неверный пароль»: причина уходит в ApiExceptionHandler.
            limiter.release(ip, email);
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        } catch (AuthenticationException e) {
            log.warn("Неудачная попытка входа, ip={}", ip);
            // Неизвестная почта и неверный пароль неразличимы.
            ProblemDetail problem =
                    ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid email or password");
            problem.setTitle("Unauthorized");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
        }

        // Защита от фиксации сессии: после входа идентификатор сессии и CSRF-токен новые.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        } else {
            request.getSession(true);
        }
        limiter.recordSuccess(ip, email);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
        return ResponseEntity.noContent().build();
    }

    /** Секунды вверх, не меньше 1: нулевое или усечённое значение даёт повторный 429. */
    static long retryAfterSeconds(Duration blocked) {
        long seconds = blocked.toSeconds() + (blocked.toNanosPart() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        csrfTokenRepository.saveToken(null, request, response);
        // клиент не хранит мёртвый идентификатор сессии
        ResponseCookie expired = ResponseCookie.from(sessionCookieName, "")
                .path("/")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, expired.toString());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    CurrentUserResponse me(@AuthenticationPrincipal AccountPrincipal principal) {
        return new CurrentUserResponse(principal.id(), principal.email(), AccountPrincipal.ROLE);
    }

    record LoginRequest(String email, String password) {}

    record CurrentUserResponse(UUID id, String email, String role) {}
}
