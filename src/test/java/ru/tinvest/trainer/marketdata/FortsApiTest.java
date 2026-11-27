package ru.tinvest.trainer.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.util.Map;
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
 * Интеграционные API-тесты срочного рынка ФОРТС (PostgreSQL + Flyway V24 + Spring Security + MockMvc):
 * {@code GET /api/v1/instruments/futures}, {@code GET /api/v1/instruments/futures/{ticker}/calc},
 * {@code GET /api/v1/instruments/options} и {@code GET /api/v1/instruments/options/{ticker}/calc}.
 */
@AutoConfigureMockMvc
class FortsApiTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private ObjectMapper json;

    private MockHttpSession httpSession;
    private String xsrf;

    @BeforeEach
    void setUp() throws Exception {
        String email = "forts" + UUID.randomUUID() + "@example.com";
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
        httpSession = (MockHttpSession) login.getRequest().getSession(false);
        Cookie newXsrf = login.getResponse().getCookie("XSRF-TOKEN");
        xsrf = newXsrf != null ? newXsrf.getValue() : xsrfCookie.getValue();
    }

    @Test
    void listFuturesReturns12SeededMoexContracts() throws Exception {
        MvcResult res = mockMvc.perform(
                        get("/api/v1/instruments/futures").session(httpSession).cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);

        JsonNode arr = json.readTree(res.getResponse().getContentAsString());
        assertThat(arr.isArray()).isTrue();
        assertThat(arr.size()).isEqualTo(12);

        boolean foundSi = false;
        boolean foundDelivery = false;
        for (JsonNode item : arr) {
            if ("SiH7".equals(item.get("ticker").asString())) {
                foundSi = true;
                assertThat(item.get("underlying").asString()).isEqualTo("USD/RUB");
                assertThat(item.get("settlementType").asString()).isEqualTo("CASH");
                assertThat(item.get("initialMarginRub").asString()).isEqualTo("14500.00");
            }
            if ("DELIVERY".equals(item.get("settlementType").asString())) {
                foundDelivery = true;
            }
        }
        assertThat(foundSi).isTrue();
        assertThat(foundDelivery).isTrue();
    }

    @Test
    void calculateFuturesComputesVariationMarginAndHandlesErrors() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/v1/instruments/futures/sih7/calc")
                        .param("entryPrice", "95000")
                        .param("currentPrice", "96450")
                        .param("contracts", "2")
                        .param("asOfDate", "2027-02-16")
                        .session(httpSession)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);

        JsonNode body = json.readTree(res.getResponse().getContentAsString());
        assertThat(body.get("ticker").asString()).isEqualTo("SiH7");
        assertThat(body.get("variationMarginRub").asString()).isEqualTo("2900.00");
        assertThat(body.get("initialMarginTotalRub").asString()).isEqualTo("29000.00");
        assertThat(body.get("roeOnMarginPct").asString()).isEqualTo("10.00");
        assertThat(body.get("daysToExpiration").asInt()).isEqualTo(30);
        assertThat(body.get("expired").asBoolean()).isFalse();

        MvcResult notFound = mockMvc.perform(get("/api/v1/instruments/futures/NO_SUCH/calc")
                        .session(httpSession)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(notFound.getResponse().getStatus()).isEqualTo(404);

        MvcResult zeroContracts = mockMvc.perform(get("/api/v1/instruments/futures/SiH7/calc")
                        .param("contracts", "0")
                        .session(httpSession)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(zeroContracts.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void listOptionsAndCalculateOptionGreeksWorkAndHandleErrors() throws Exception {
        MvcResult listRes = mockMvc.perform(
                        get("/api/v1/instruments/options").session(httpSession).cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(listRes.getResponse().getStatus()).isEqualTo(200);

        JsonNode arr = json.readTree(listRes.getResponse().getContentAsString());
        assertThat(arr.isArray()).isTrue();
        assertThat(arr.size()).isEqualTo(12);

        MvcResult calcRes = mockMvc.perform(get("/api/v1/instruments/options/si96000bc7/calc")
                        .param("spot", "95400")
                        .param("volatilityPct", "20.0")
                        .param("riskFreeRatePct", "18.0")
                        .param("asOfDate", "2027-01-17")
                        .session(httpSession)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(calcRes.getResponse().getStatus()).isEqualTo(200);

        JsonNode body = json.readTree(calcRes.getResponse().getContentAsString());
        assertThat(body.get("ticker").asString()).isEqualTo("Si96000BC7");
        assertThat(body.get("optionType").asString()).isEqualTo("CALL");
        assertThat(new BigDecimal(body.get("premium").asString())).isPositive();
        assertThat(new BigDecimal(body.get("delta").asString())).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        assertThat(new BigDecimal(body.get("gamma").asString())).isPositive();
        assertThat(new BigDecimal(body.get("vega").asString())).isPositive();
        assertThat(body.get("daysToExpiration").asInt()).isEqualTo(60);

        MvcResult notFound = mockMvc.perform(get("/api/v1/instruments/options/NO_SUCH/calc")
                        .session(httpSession)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(notFound.getResponse().getStatus()).isEqualTo(404);

        MvcResult badVol = mockMvc.perform(get("/api/v1/instruments/options/Si96000BC7/calc")
                        .param("volatilityPct", "0")
                        .session(httpSession)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(badVol.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void fortsEndpointsRequireAuthentication() throws Exception {
        assertThat(mockMvc.perform(get("/api/v1/instruments/futures"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/v1/instruments/futures/SiH7/calc"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/v1/instruments/options"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/v1/instruments/options/Si96000BC7/calc"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }
}
