package ru.tinvest.trainer.api;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.server.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Безопасность HTTP API: cookie-сессия, CSRF через cookie {@code XSRF-TOKEN} и заголовок {@code X-XSRF-TOKEN}
 * (решения 19.11), все {@code /api/v1/**} кроме входа требуют аутентификации, ошибки — ProblemDetail.
 * Метрики Prometheus ({@code /actuator/prometheus}) разрешены исключительно на отдельном внутреннем порту управления.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfiguration {

    private final AtomicInteger managementPort = new AtomicInteger(-1);

    @EventListener
    void onWebServerInitialized(ServletWebServerInitializedEvent event) {
        if ("management".equals(event.getApplicationContext().getServerNamespace())) {
            managementPort.set(event.getWebServer().getPort());
        }
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Lax"));
        return repository;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CsrfTokenRepository csrfTokenRepository,
            SecurityContextRepository contextRepository,
            ObjectMapper objectMapper)
            throws Exception {
        ProblemResponses problems = new ProblemResponses(objectMapper);
        RequestMatcher managementPrometheus = request -> {
            int port = managementPort.get();
            return port > 0 && request.getLocalPort() == port && "/actuator/prometheus".equals(request.getRequestURI());
        };
        http.csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository)
                        // без XOR-маскировки: Angular отправляет значение cookie как есть
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .securityContext(context -> context.securityContextRepository(contextRepository))
                // анонимный запрос не должен создавать сессию
                .requestCache(cache -> cache.disable())
                .formLogin(login -> login.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers("/api/health", "/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .requestMatchers(managementPrometheus)
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login")
                        .permitAll()
                        .requestMatchers("/api/v1/**")
                        .authenticated()
                        .anyRequest()
                        .denyAll())
                .exceptionHandling(handling -> handling.authenticationEntryPoint(
                                (request, response, ex) -> problems.write(
                                        response,
                                        401,
                                        "Unauthorized",
                                        "Authentication required",
                                        ErrorCode.UNAUTHENTICATED))
                        .accessDeniedHandler((request, response, ex) -> problems.write(
                                response,
                                403,
                                "Forbidden",
                                "Access denied",
                                ex instanceof CsrfException ? ErrorCode.CSRF_INVALID : ErrorCode.FORBIDDEN)));
        return http.build();
    }

    /**
     * Токен CSRF отложенный: без обращения к нему cookie не выставляется, а {@code GET /me} (в том числе 401) обязан её
     * выдать.
     */
    private static final class CsrfCookieFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}
