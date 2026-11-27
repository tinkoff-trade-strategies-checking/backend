package ru.tinvest.trainer.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.tinvest.trainer.PostgresIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Интеграционные API-тесты гостевого/демо-входа ({@code POST /api/v1/auth/guest}) и сохранения режима интерфейса
 * ({@code PATCH /api/v1/me/preferences}, backend#88).
 */
@AutoConfigureMockMvc
class GuestAuthAndPreferencesApiTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void guestLoginCreatesSessionAndPreferencesPersistAcrossMeCalls() throws Exception {
        // Without CSRF -> 403
        MvcResult noCsrf = mockMvc.perform(post("/api/v1/auth/guest")).andReturn();
        assertThat(noCsrf.getResponse().getStatus()).isEqualTo(403);

        // Fetch initial XSRF-TOKEN via GET /api/v1/me
        MvcResult anonMe = mockMvc.perform(get("/api/v1/me")).andReturn();
        assertThat(anonMe.getResponse().getStatus()).isEqualTo(401);
        Cookie xsrfCookie = anonMe.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrfCookie).isNotNull();

        // Guest login -> 200 with CurrentUser(guest=true, uiMode=beginner)
        MvcResult guestLogin = mockMvc.perform(
                        post("/api/v1/auth/guest").cookie(xsrfCookie).header("X-XSRF-TOKEN", xsrfCookie.getValue()))
                .andReturn();
        assertThat(guestLogin.getResponse().getStatus()).isEqualTo(200);
        JsonNode guestUser = json.readTree(guestLogin.getResponse().getContentAsString());
        assertThat(guestUser.get("guest").asBoolean()).isTrue();
        assertThat(guestUser.get("uiMode").asString()).isEqualTo("beginner");
        assertThat(guestUser.get("role").asString()).isEqualTo("USER");
        assertThat(guestUser.get("email").asString()).endsWith("@demo.local");

        MockHttpSession session = (MockHttpSession) guestLogin.getRequest().getSession(false);
        assertThat(session).isNotNull();
        Cookie newXsrf = guestLogin.getResponse().getCookie("XSRF-TOKEN");
        String xsrf = newXsrf != null ? newXsrf.getValue() : xsrfCookie.getValue();

        // Update uiMode to pro
        MvcResult patchPro = mockMvc.perform(patch("/api/v1/me/preferences")
                        .session(session)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf))
                        .header("X-XSRF-TOKEN", xsrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("uiMode", "pro"))))
                .andReturn();
        assertThat(patchPro.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(patchPro.getResponse().getContentAsString())
                        .get("uiMode")
                        .asString())
                .isEqualTo("pro");

        // Verify persistence via GET /api/v1/me
        MvcResult meAfter = mockMvc.perform(get("/api/v1/me").session(session).cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(meAfter.getResponse().getStatus()).isEqualTo(200);
        JsonNode meBody = json.readTree(meAfter.getResponse().getContentAsString());
        assertThat(meBody.get("guest").asBoolean()).isTrue();
        assertThat(meBody.get("uiMode").asString()).isEqualTo("pro");

        // Invalid uiMode -> 400
        MvcResult badMode = mockMvc.perform(patch("/api/v1/me/preferences")
                        .session(session)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf))
                        .header("X-XSRF-TOKEN", xsrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("uiMode", "invalid"))))
                .andReturn();
        assertThat(badMode.getResponse().getStatus()).isEqualTo(400);

        // Unauthenticated PATCH -> 401
        MvcResult unauthPatch = mockMvc.perform(patch("/api/v1/me/preferences")
                        .cookie(xsrfCookie)
                        .header("X-XSRF-TOKEN", xsrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("uiMode", "pro"))))
                .andReturn();
        assertThat(unauthPatch.getResponse().getStatus()).isEqualTo(401);
    }
}
