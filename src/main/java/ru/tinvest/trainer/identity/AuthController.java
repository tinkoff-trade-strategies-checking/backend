package ru.tinvest.trainer.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.tinvest.trainer.api.ErrorCode;
import ru.tinvest.trainer.api.InvalidRequestException;

/** Вход, гостевой демо-вход, выход, профиль и настройки пользователя ({@code api/openapi.yaml}, тег {@code identity}). */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    static final Duration GUEST_SESSION_TTL = Duration.ofHours(24);

    private final AuthenticationManager authenticationManager;
    private final Accounts accounts;
    private final LoginRateLimiter limiter;
    private final String sessionCookieName;
    private final SecurityContextRepository contextRepository;
    private final CsrfTokenRepository csrfTokenRepository;

    AuthController(
            AuthenticationManager authenticationManager,
            Accounts accounts,
            LoginRateLimiter limiter,
            SecurityContextRepository contextRepository,
            CsrfTokenRepository csrfTokenRepository,
            @Value("${server.servlet.session.cookie.name:SESSION}") String sessionCookieName) {
        this.authenticationManager = authenticationManager;
        this.accounts = accounts;
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
            ErrorCode.TOO_MANY_REQUESTS.applyTo(problem);
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
            ErrorCode.INVALID_CREDENTIALS.applyTo(problem);
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

    @PostMapping("/auth/guest")
    ResponseEntity<?> loginGuest(HttpServletRequest request, HttpServletResponse response) {
        String ip = request.getRemoteAddr();
        String guestKey = "guest:" + ip;
        Duration blocked = limiter.tryAcquire(ip, guestKey).orElse(null);
        if (blocked != null) {
            log.warn("Гостевой вход заблокирован ограничителем попыток, ip={}", ip);
            ProblemDetail problem =
                    ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Too many guest login attempts");
            problem.setTitle("Too Many Requests");
            ErrorCode.TOO_MANY_REQUESTS.applyTo(problem);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds(blocked)))
                    .body(problem);
        }

        AccountPrincipal guest = accounts.createGuest(GUEST_SESSION_TTL);
        Authentication authentication =
                UsernamePasswordAuthenticationToken.authenticated(guest, null, guest.getAuthorities());

        HttpSession session;
        if (request.getSession(false) != null) {
            request.changeSessionId();
            session = request.getSession(false);
        } else {
            session = request.getSession(true);
        }
        if (session != null) {
            session.setMaxInactiveInterval((int) GUEST_SESSION_TTL.toSeconds());
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
        return ResponseEntity.ok(new CurrentUserResponse(
                guest.id(), guest.email(), AccountPrincipal.ROLE, true, Accounts.DEFAULT_UI_MODE));
    }

    @GetMapping("/me")
    CurrentUserResponse me(@AuthenticationPrincipal AccountPrincipal principal) {
        Accounts.UserProfile profile = accounts.findProfile(principal.id())
                .orElse(new Accounts.UserProfile(
                        principal.id(), principal.email(), principal.guest(), Accounts.DEFAULT_UI_MODE));
        return new CurrentUserResponse(
                profile.id(), profile.email(), AccountPrincipal.ROLE, profile.guest(), profile.uiMode());
    }

    @PatchMapping("/me/preferences")
    CurrentUserResponse updatePreferences(
            @AuthenticationPrincipal AccountPrincipal principal, @RequestBody UpdatePreferencesRequest body) {
        if (body == null || body.uiMode() == null || body.uiMode().isBlank()) {
            throw new InvalidRequestException("uiMode is required");
        }
        String normalized = body.uiMode().trim().toLowerCase(Locale.ROOT);
        if (!Accounts.UI_MODES.contains(normalized)) {
            throw new InvalidRequestException("uiMode must be 'beginner' or 'pro'");
        }
        Accounts.UserProfile updated = accounts.updateUiMode(principal.id(), normalized);
        return new CurrentUserResponse(
                updated.id(), updated.email(), AccountPrincipal.ROLE, updated.guest(), updated.uiMode());
    }

    record LoginRequest(String email, String password) {}

    record UpdatePreferencesRequest(@Nullable String uiMode) {}

    record CurrentUserResponse(UUID id, String email, String role, boolean guest, String uiMode) {}
}
