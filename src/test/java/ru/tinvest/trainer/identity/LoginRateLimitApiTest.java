package ru.tinvest.trainer.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.tinvest.trainer.PostgresIntegrationTest;

@AutoConfigureMockMvc
class LoginRateLimitApiTest extends PostgresIntegrationTest {

    private static final String EMAIL = "limit@example.com";
    private static final String PASSWORD = "correct horse";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Accounts accounts;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private LoginRateLimiter limiter;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        limiter.reset();
        jdbc.sql("DELETE FROM identity.user_account").update();
        accounts.create(EMAIL, PASSWORD);
        token = mockMvc.perform(get("/api/v1/me"))
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN")
                .getValue();
    }

    private int status(String email, String password, String ip) throws Exception {
        return login(email, password, ip).getResponse().getStatus();
    }

    private MvcResult login(String email, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        })
                        .cookie(new Cookie("XSRF-TOKEN", token))
                        .header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
    }

    @Test
    void sixthFailedAttemptIs429WithRetryAfterAndEvenCorrectPasswordIsRejected() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(status(EMAIL, "wrong", "10.0.0.1")).isEqualTo(401);
        }

        MvcResult blocked = login(EMAIL, "wrong", "10.0.0.1");
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(blocked.getResponse().getContentType()).contains("application/problem+json");
        assertThat(blocked.getResponse().getContentAsString()).contains("\"status\":429");
        assertThat(Long.parseLong(blocked.getResponse().getHeader("Retry-After")))
                .isBetween(1L, 300L);
        // блокировка по почте действует и с другого IP: верный пароль до конца окна не принимается
        assertThat(status(EMAIL, PASSWORD, "10.0.0.2")).isEqualTo(429);
    }

    @Test
    void successfulLoginResetsEmailCounter() throws Exception {
        for (int i = 0; i < 4; i++) {
            status(EMAIL, "wrong", "10.0.0.1");
        }
        assertThat(status(EMAIL, PASSWORD, "10.0.0.1")).isEqualTo(204);

        for (int i = 0; i < 4; i++) {
            assertThat(status(EMAIL, "wrong", "10.0.0.1")).isEqualTo(401);
        }
    }

    @Test
    void ipLimitBlocksManyEmailsFromOneAddress() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThat(status("user" + i + "@example.com", "x", "10.0.0.9")).isEqualTo(401);
        }
        assertThat(status("other@example.com", "x", "10.0.0.9")).isEqualTo(429);
        assertThat(status("other@example.com", "x", "10.0.0.10")).isEqualTo(401);
    }
}
