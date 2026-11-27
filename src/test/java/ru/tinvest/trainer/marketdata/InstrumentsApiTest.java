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
 * Интеграционные API-тесты справочников облигаций и БПИФ/ETF, валют и драгметаллов (PostgreSQL + Flyway V23 +
 * Spring Security + MockMvc): {@code GET /api/v1/instruments/bonds}, {@code GET /api/v1/instruments/bonds/{ticker}/calc}
 * и {@code GET /api/v1/instruments/etfs}.
 */
@AutoConfigureMockMvc
class InstrumentsApiTest extends PostgresIntegrationTest {

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
        String email = "inst" + UUID.randomUUID() + "@example.com";
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
    void listBondsReturnsSeededOfzAndCorporateBonds() throws Exception {
        MvcResult res = mockMvc.perform(
                        get("/api/v1/instruments/bonds").session(httpSession).cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);

        JsonNode arr = json.readTree(res.getResponse().getContentAsString());
        assertThat(arr.isArray()).isTrue();
        assertThat(arr.size()).isGreaterThanOrEqualTo(15);

        boolean foundOfz = false;
        boolean foundCorp = false;
        boolean foundAmortized = false;
        for (JsonNode item : arr) {
            if ("SU26238RMFS4".equals(item.get("ticker").asString())) {
                foundOfz = true;
                assertThat(item.get("bondType").asString()).isEqualTo("OFZ");
                assertThat(item.get("faceValueRub").asString()).isEqualTo("1000.00");
                assertThat(item.get("currency").asString()).isEqualTo("RUB");
            }
            if ("CORPORATE".equals(item.get("bondType").asString())) {
                foundCorp = true;
            }
            if (item.get("amortized").asBoolean()) {
                foundAmortized = true;
            }
        }
        assertThat(foundOfz).isTrue();
        assertThat(foundCorp).isTrue();
        assertThat(foundAmortized).isTrue();
    }

    @Test
    void calculateBondComputesCleanDirtyNkdAndYtmAndHandlesErrors() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/v1/instruments/bonds/SU26248RMFS3/calc")
                        .param("pricePct", "80.50")
                        .param("asOfDate", "2027-03-01")
                        .session(httpSession)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);

        JsonNode body = json.readTree(res.getResponse().getContentAsString());
        assertThat(body.get("ticker").asString()).isEqualTo("SU26248RMFS3");
        assertThat(body.get("cleanPriceRub").asString()).isEqualTo("805.00");
        BigDecimal nkd = new BigDecimal(body.get("accruedInterestRub").asString());
        BigDecimal dirty = new BigDecimal(body.get("dirtyPriceRub").asString());
        assertThat(nkd).isPositive();
        assertThat(dirty).isEqualByComparingTo(new BigDecimal("805.00").add(nkd));
        assertThat(new BigDecimal(body.get("ytmPct").asString())).isGreaterThan(new BigDecimal("14.00"));
        assertThat(body.get("remainingCouponsCount").asInt()).isGreaterThan(10);

        MvcResult notFound = mockMvc.perform(get("/api/v1/instruments/bonds/NO_SUCH_BOND/calc")
                        .session(httpSession)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(notFound.getResponse().getStatus()).isEqualTo(404);

        MvcResult badPrice = mockMvc.perform(get("/api/v1/instruments/bonds/SU26248RMFS3/calc")
                        .param("pricePct", "0")
                        .session(httpSession)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(badPrice.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void listEtfsReturnsLiquidityFundsCurrencyAndMetal() throws Exception {
        MvcResult res = mockMvc.perform(
                        get("/api/v1/instruments/etfs").session(httpSession).cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);

        JsonNode arr = json.readTree(res.getResponse().getContentAsString());
        assertThat(arr.isArray()).isTrue();
        assertThat(arr.size()).isGreaterThanOrEqualTo(12);

        boolean foundLqdt = false;
        boolean foundMetal = false;
        boolean foundCurrency = false;
        for (JsonNode item : arr) {
            String ticker = item.get("ticker").asString();
            if ("LQDT".equals(ticker)) {
                foundLqdt = true;
                assertThat(item.get("assetClass").asString()).isEqualTo("ETF");
                assertThat(item.get("category").asString()).isEqualTo("LIQUIDITY");
                assertThat(item.get("tracksRuonia").asBoolean()).isTrue();
            } else if ("GLDRUB_TOM".equals(ticker)) {
                foundMetal = true;
                assertThat(item.get("assetClass").asString()).isEqualTo("METAL");
            } else if ("CNYRUB_TOM".equals(ticker)) {
                foundCurrency = true;
                assertThat(item.get("assetClass").asString()).isEqualTo("CURRENCY");
            }
        }
        assertThat(foundLqdt).isTrue();
        assertThat(foundMetal).isTrue();
        assertThat(foundCurrency).isTrue();
    }

    @Test
    void instrumentsEndpointsRequireAuthentication() throws Exception {
        assertThat(mockMvc.perform(get("/api/v1/instruments/bonds"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/v1/instruments/etfs"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }
}
