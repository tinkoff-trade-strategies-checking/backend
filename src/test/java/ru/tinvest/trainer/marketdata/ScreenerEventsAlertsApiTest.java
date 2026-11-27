package ru.tinvest.trainer.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.tinvest.trainer.PostgresIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Интеграционные API-тесты серверного скринера MOEX, календаря рыночных событий и пользовательских алертов
 * (PostgreSQL + Flyway V26 + Spring Security + CSRF + MockMvc, backend#95).
 */
@AutoConfigureMockMvc
class ScreenerEventsAlertsApiTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private ObjectMapper json;

    private UserSession userA;
    private UserSession userB;

    private record UserSession(MockHttpSession session, String xsrf) {}

    @BeforeEach
    void setUp() throws Exception {
        userA = loginNewUser();
        userB = loginNewUser();
    }

    private UserSession loginNewUser() throws Exception {
        String email = "alerts" + UUID.randomUUID() + "@example.com";
        jdbc.sql("INSERT INTO identity.user_account (id, email, password_hash) VALUES (:id, :email, :hash)")
                .param("id", UUID.randomUUID())
                .param("email", email)
                .param("hash", encoder.encode(PASSWORD))
                .update();

        MvcResult me = mockMvc.perform(get("/api/v1/me")).andReturn();
        Cookie xsrfCookie = me.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrfCookie).isNotNull();

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(xsrfCookie)
                        .header("X-XSRF-TOKEN", xsrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(204);
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        Cookie newXsrf = login.getResponse().getCookie("XSRF-TOKEN");
        String xsrf = newXsrf != null ? newXsrf.getValue() : xsrfCookie.getValue();
        return new UserSession(session, xsrf);
    }

    @Test
    void screenerReturnsPapersWithDecimalStringFundamentals() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/v1/instruments/screener")
                        .session(userA.session())
                        .cookie(new Cookie("XSRF-TOKEN", userA.xsrf())))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);

        JsonNode arr = json.readTree(res.getResponse().getContentAsString());
        assertThat(arr.isArray()).isTrue();
        assertThat(arr.size()).isGreaterThanOrEqualTo(40);

        JsonNode sber = null;
        for (JsonNode item : arr) {
            if ("SBER".equals(item.get("ticker").asString())) {
                sber = item;
                break;
            }
        }
        assertThat(sber).isNotNull();
        assertThat(sber.get("hasFundamentals").asBoolean()).isTrue();
        assertThat(sber.get("marketCapRub").isString()).isTrue();
        assertThat(sber.get("peRatio").isString()).isTrue();
        assertThat(sber.get("roe").isString()).isTrue();
        assertThat(sber.get("dividendYield").isString()).isTrue();
        assertThat(sber.get("beta").isString()).isTrue();
    }

    @Test
    void marketEventsReturnSeededCalendarAndSupportTypeAndTickerFilters() throws Exception {
        MvcResult allRes = mockMvc.perform(get("/api/v1/instruments/events")
                        .session(userA.session())
                        .cookie(new Cookie("XSRF-TOKEN", userA.xsrf())))
                .andReturn();
        assertThat(allRes.getResponse().getStatus()).isEqualTo(200);

        JsonNode all = json.readTree(allRes.getResponse().getContentAsString());
        assertThat(all.isArray()).isTrue();
        assertThat(all.size()).isGreaterThanOrEqualTo(30);

        Set<String> types = new HashSet<>();
        for (JsonNode event : all) {
            types.add(event.get("eventType").asString());
            assertThat(event.get("eventDate").asString()).matches("\\d{4}-\\d{2}-\\d{2}");
            assertThat(event.get("importance").asString()).isIn("HIGH", "MEDIUM", "LOW");
        }
        assertThat(types).containsExactlyInAnyOrder("CBR_RATE", "EARNINGS", "DIVIDEND", "MACRO");

        MvcResult cbrRes = mockMvc.perform(get("/api/v1/instruments/events")
                        .param("type", "CBR_RATE")
                        .session(userA.session())
                        .cookie(new Cookie("XSRF-TOKEN", userA.xsrf())))
                .andReturn();
        assertThat(cbrRes.getResponse().getStatus()).isEqualTo(200);
        JsonNode cbrEvents = json.readTree(cbrRes.getResponse().getContentAsString());
        assertThat(cbrEvents.size()).isGreaterThanOrEqualTo(12);
        for (JsonNode event : cbrEvents) {
            assertThat(event.get("eventType").asString()).isEqualTo("CBR_RATE");
        }

        MvcResult sberEarningsRes = mockMvc.perform(get("/api/v1/instruments/events")
                        .param("type", "EARNINGS")
                        .param("ticker", "sber")
                        .session(userA.session())
                        .cookie(new Cookie("XSRF-TOKEN", userA.xsrf())))
                .andReturn();
        assertThat(sberEarningsRes.getResponse().getStatus()).isEqualTo(200);
        JsonNode sberEarnings = json.readTree(sberEarningsRes.getResponse().getContentAsString());
        assertThat(sberEarnings.size()).isGreaterThanOrEqualTo(2);
        for (JsonNode event : sberEarnings) {
            assertThat(event.get("eventType").asString()).isEqualTo("EARNINGS");
            assertThat(event.get("ticker").asString()).isEqualTo("SBER");
        }

        MvcResult badType = mockMvc.perform(get("/api/v1/instruments/events")
                        .param("type", "UNKNOWN")
                        .session(userA.session())
                        .cookie(new Cookie("XSRF-TOKEN", userA.xsrf())))
                .andReturn();
        assertThat(badType.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void alertsCrudEnforcesOwnershipCsrfValidationAndMax50Limit() throws Exception {
        // 1. Create alert for user A
        MvcResult createRes = mockMvc.perform(post("/api/v1/alerts")
                        .session(userA.session())
                        .cookie(new Cookie("XSRF-TOKEN", userA.xsrf()))
                        .header("X-XSRF-TOKEN", userA.xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "ticker", "sber",
                                "conditionType", "PRICE_ABOVE",
                                "thresholdValue", "325.5",
                                "note", "Пробой сопротивления"))))
                .andReturn();
        assertThat(createRes.getResponse().getStatus()).isEqualTo(201);
        JsonNode created = json.readTree(createRes.getResponse().getContentAsString());
        String alertId = created.get("id").asString();
        assertThat(UUID.fromString(alertId)).isNotNull();
        assertThat(created.get("ticker").asString()).isEqualTo("SBER");
        assertThat(created.get("conditionType").asString()).isEqualTo("PRICE_ABOVE");
        assertThat(created.get("thresholdValue").asString()).isEqualTo("325.50");
        assertThat(created.get("active").asBoolean()).isTrue();
        assertThat(created.get("note").asString()).isEqualTo("Пробой сопротивления");
        assertThat(created.get("createdAt").asString()).isNotBlank();

        // 2. User A sees alert, User B sees empty list
        MvcResult listA = mockMvc.perform(
                        get("/api/v1/alerts").session(userA.session()).cookie(new Cookie("XSRF-TOKEN", userA.xsrf())))
                .andReturn();
        assertThat(listA.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(listA.getResponse().getContentAsString()).size())
                .isEqualTo(1);

        MvcResult listB = mockMvc.perform(
                        get("/api/v1/alerts").session(userB.session()).cookie(new Cookie("XSRF-TOKEN", userB.xsrf())))
                .andReturn();
        assertThat(listB.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(listB.getResponse().getContentAsString()).size())
                .isZero();

        // 3. User B cannot delete User A's alert (404)
        MvcResult deleteByOther = mockMvc.perform(delete("/api/v1/alerts/" + alertId)
                        .session(userB.session())
                        .cookie(new Cookie("XSRF-TOKEN", userB.xsrf()))
                        .header("X-XSRF-TOKEN", userB.xsrf()))
                .andReturn();
        assertThat(deleteByOther.getResponse().getStatus()).isEqualTo(404);

        // 4. CSRF required on POST and DELETE
        MvcResult noCsrf = mockMvc.perform(post("/api/v1/alerts")
                        .session(userA.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "ticker", "LKOH",
                                "conditionType", "PRICE_BELOW",
                                "thresholdValue", "6500"))))
                .andReturn();
        assertThat(noCsrf.getResponse().getStatus()).isEqualTo(403);

        // 5. Validation errors -> 400
        MvcResult badThreshold = mockMvc.perform(post("/api/v1/alerts")
                        .session(userA.session())
                        .cookie(new Cookie("XSRF-TOKEN", userA.xsrf()))
                        .header("X-XSRF-TOKEN", userA.xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "ticker", "SBER",
                                "conditionType", "PRICE_ABOVE",
                                "thresholdValue", "0"))))
                .andReturn();
        assertThat(badThreshold.getResponse().getStatus()).isEqualTo(400);

        MvcResult badRsi = mockMvc.perform(post("/api/v1/alerts")
                        .session(userA.session())
                        .cookie(new Cookie("XSRF-TOKEN", userA.xsrf()))
                        .header("X-XSRF-TOKEN", userA.xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "ticker", "SBER",
                                "conditionType", "RSI_ABOVE",
                                "thresholdValue", "105"))))
                .andReturn();
        assertThat(badRsi.getResponse().getStatus()).isEqualTo(400);

        // 6. User A deletes own alert -> 204, second delete -> 404
        MvcResult deleteOwned = mockMvc.perform(delete("/api/v1/alerts/" + alertId)
                        .session(userA.session())
                        .cookie(new Cookie("XSRF-TOKEN", userA.xsrf()))
                        .header("X-XSRF-TOKEN", userA.xsrf()))
                .andReturn();
        assertThat(deleteOwned.getResponse().getStatus()).isEqualTo(204);

        MvcResult deleteAgain = mockMvc.perform(delete("/api/v1/alerts/" + alertId)
                        .session(userA.session())
                        .cookie(new Cookie("XSRF-TOKEN", userA.xsrf()))
                        .header("X-XSRF-TOKEN", userA.xsrf()))
                .andReturn();
        assertThat(deleteAgain.getResponse().getStatus()).isEqualTo(404);

        // 7. Max 50 active alerts per user -> 51st returns 409 CONFLICT
        for (int i = 1; i <= 50; i++) {
            MvcResult r = mockMvc.perform(post("/api/v1/alerts")
                            .session(userB.session())
                            .cookie(new Cookie("XSRF-TOKEN", userB.xsrf()))
                            .header("X-XSRF-TOKEN", userB.xsrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "ticker", "SBER",
                                    "conditionType", "RSI_BELOW",
                                    "thresholdValue", String.valueOf(i)))))
                    .andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(201);
        }

        MvcResult overLimit = mockMvc.perform(post("/api/v1/alerts")
                        .session(userB.session())
                        .cookie(new Cookie("XSRF-TOKEN", userB.xsrf()))
                        .header("X-XSRF-TOKEN", userB.xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "ticker", "SBER",
                                "conditionType", "RSI_BELOW",
                                "thresholdValue", "25"))))
                .andReturn();
        assertThat(overLimit.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(overLimit.getResponse().getContentAsString())
                        .get("code")
                        .asString())
                .isEqualTo("CONFLICT");
    }

    @Test
    void endpointsRequireAuthentication() throws Exception {
        assertThat(mockMvc.perform(get("/api/v1/instruments/screener"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/v1/instruments/events"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/v1/alerts"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }
}
