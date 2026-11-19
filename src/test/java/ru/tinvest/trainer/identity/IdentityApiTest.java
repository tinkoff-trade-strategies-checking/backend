package ru.tinvest.trainer.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.tinvest.trainer.PostgresIntegrationTest;

@AutoConfigureMockMvc
@Import(IdentityApiTest.WhoAmIController.class)
class IdentityApiTest extends PostgresIntegrationTest {

    private static final String EMAIL = "Trader@Example.com";
    private static final String PASSWORD = "correct horse";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Accounts accounts;

    @Autowired
    private CurrentUser currentUser;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private LoginRateLimiter limiter;

    private UUID userId;

    @BeforeEach
    void createAccount() {
        limiter.reset();
        jdbc.sql("DELETE FROM identity.user_account").update();
        userId = accounts.create(EMAIL, PASSWORD);
    }

    private static String loginBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private String csrfToken() throws Exception {
        MvcResult me = mockMvc.perform(get("/api/v1/me")).andReturn();
        Cookie cookie = me.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

    private MvcResult login(String token, String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(new Cookie("XSRF-TOKEN", token))
                        .header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, password)))
                .andReturn();
    }

    @Test
    void meWithoutSessionIs401AndIssuesCsrfCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andReturn();

        Cookie xsrf = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        assertThat(xsrf.getValue()).isNotBlank();
        assertThat(xsrf.isHttpOnly()).isFalse();
        assertThat(xsrf.getSecure()).isTrue();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void loginWithoutCsrfTokenIs403() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EMAIL, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void loginWithWrongCsrfTokenIs403() throws Exception {
        String token = csrfToken();
        mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(new Cookie("XSRF-TOKEN", token))
                        .header("X-XSRF-TOKEN", "other")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EMAIL, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveSame401() throws Exception {
        String token = csrfToken();

        MvcResult wrongPassword = login(token, EMAIL, "nope");
        MvcResult unknownEmail = login(token, "nobody@example.com", PASSWORD);

        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknownEmail.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrongPassword.getResponse().getContentAsString())
                .isEqualTo(unknownEmail.getResponse().getContentAsString());
        assertThat(wrongPassword.getRequest().getSession(false)).isNull();
        assertThat(wrongPassword.getResponse().getContentAsString()).contains("\"code\":\"INVALID_CREDENTIALS\"");
    }

    @Test
    void passwordLongerThan72BytesIs400() throws Exception {
        String token = csrfToken();
        // 37 кириллических символов = 74 байта в UTF-8
        MvcResult result = login(token, EMAIL, "я".repeat(37));
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void loginCreatesSessionAndMeReturnsUser() throws Exception {
        String token = csrfToken();

        MvcResult login = login(token, "  trader@example.com ", PASSWORD);

        assertThat(login.getResponse().getStatus()).isEqualTo(204);
        Cookie newToken = login.getResponse().getCookie("XSRF-TOKEN");
        assertThat(newToken).isNotNull();
        assertThat(newToken.getValue()).isNotEqualTo(token);
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mockMvc.perform(get("/api/v1/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.email").value("trader@example.com"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void currentUserApiReturnsIdOfLoggedInUserInsideRequest() throws Exception {
        assertThat(currentUser.findId()).isEmpty();
        String token = csrfToken();
        MockHttpSession session =
                (MockHttpSession) login(token, EMAIL, PASSWORD).getRequest().getSession(false);

        mockMvc.perform(get("/api/v1/test/whoami").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(userId.toString()));
    }

    @Test
    void loginChangesIdOfExistingSession() throws Exception {
        String token = csrfToken();
        MockHttpSession existing = new MockHttpSession();
        String before = existing.getId();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .session(existing)
                        .cookie(new Cookie("XSRF-TOKEN", token))
                        .header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EMAIL, PASSWORD)))
                .andExpect(status().isNoContent())
                .andReturn();

        MockHttpSession after = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(after).isNotNull();
        assertThat(after.getId()).isNotEqualTo(before);
        mockMvc.perform(get("/api/v1/me").session(after)).andExpect(status().isOk());
    }

    @Test
    void logoutWithoutCsrfIs403AndWithCsrfInvalidatesSession() throws Exception {
        MvcResult login = login(csrfToken(), EMAIL, PASSWORD);
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        // токен после входа новый, из ответа входа
        String token = login.getResponse().getCookie("XSRF-TOKEN").getValue();
        Cookie current = new Cookie("XSRF-TOKEN", token);

        mockMvc.perform(post("/api/v1/auth/logout").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/me").session(session)).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .session(session)
                        .cookie(current)
                        .header("X-XSRF-TOKEN", token))
                .andExpect(status().isNoContent())
                .andExpect(result -> {
                    assertThat(result.getResponse().getHeaders("Set-Cookie"))
                            .anySatisfy(
                                    c -> assertThat(c).startsWith("SESSION=;").contains("Max-Age=0"));
                    assertThat(result.getResponse().getCookie("XSRF-TOKEN").getMaxAge())
                            .isZero();
                });

        assertThat(session.isInvalid()).isTrue();
        mockMvc.perform(get("/api/v1/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointsWithoutSessionAre401WithProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/launch/options"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Unauthorized"));
        mockMvc.perform(get("/api/v1/sessions")).andExpect(status().isUnauthorized());
    }

    @Test
    void healthStaysPublic() throws Exception {
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void emailIsUniqueIgnoringCase() {
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DuplicateKeyException.class, () -> accounts.create("TRADER@example.com", "x"));
    }

    @RestController
    static class WhoAmIController {

        private final CurrentUser currentUser;

        WhoAmIController(CurrentUser currentUser) {
            this.currentUser = currentUser;
        }

        @GetMapping("/api/v1/test/whoami")
        String whoAmI() {
            return currentUser.requireId().toString();
        }
    }
}
