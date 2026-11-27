package ru.tinvest.trainer.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.util.List;
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
 * Интеграционные API-тесты дивидендов T+1, налогового калькулятора (НДФЛ/ИИС) и DCA (TWR/XIRR)
 * (PostgreSQL + Flyway V25 + Spring Security CSRF + MockMvc, backend#93, backend#94).
 */
@AutoConfigureMockMvc
class DividendsTaxApiTest extends PostgresIntegrationTest {

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
        String email = "divtax" + UUID.randomUUID() + "@example.com";
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
    void listDividendsReturns40PlusSeededEventsAndFiltersByTicker() throws Exception {
        MvcResult allRes = mockMvc.perform(get("/api/v1/instruments/dividends")
                        .session(httpSession)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(allRes.getResponse().getStatus()).isEqualTo(200);

        JsonNode allArr = json.readTree(allRes.getResponse().getContentAsString());
        assertThat(allArr.isArray()).isTrue();
        assertThat(allArr.size()).isGreaterThanOrEqualTo(40);

        MvcResult sberRes = mockMvc.perform(get("/api/v1/instruments/dividends")
                        .param("ticker", "sber")
                        .session(httpSession)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(sberRes.getResponse().getStatus()).isEqualTo(200);

        JsonNode sberArr = json.readTree(sberRes.getResponse().getContentAsString());
        assertThat(sberArr.size()).isGreaterThanOrEqualTo(3);
        for (JsonNode item : sberArr) {
            assertThat(item.get("ticker").asString()).isEqualTo("SBER");
            assertThat(item.get("currency").asString()).isEqualTo("RUB");
        }
    }

    @Test
    void calculateDividendPayoutComputesTPlus1AndHandlesErrors() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/v1/instruments/dividends/sber/calc")
                        .param("shares", "200")
                        .param("taxRatePct", "15")
                        .session(httpSession)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);

        JsonNode body = json.readTree(res.getResponse().getContentAsString());
        assertThat(body.get("ticker").asString()).isEqualTo("SBER");
        assertThat(body.get("shares").asInt()).isEqualTo(200);
        assertThat(body.get("taxRatePct").asString()).isEqualTo("15.00");
        assertThat(body.get("grossPayoutRub").asString()).isEqualTo("7300.00");
        assertThat(body.get("taxWithheldRub").asString()).isEqualTo("1095.00");
        assertThat(body.get("netPayoutRub").asString()).isEqualTo("6205.00");
        assertThat(body.get("lastBuyDate").asString()).isEqualTo("2026-07-16");
        assertThat(body.get("exDividendDate").asString()).isEqualTo("2026-07-17");

        MvcResult notFound = mockMvc.perform(get("/api/v1/instruments/dividends/NO_SUCH/calc")
                        .session(httpSession)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(notFound.getResponse().getStatus()).isEqualTo(404);

        MvcResult badShares = mockMvc.perform(get("/api/v1/instruments/dividends/SBER/calc")
                        .param("shares", "0")
                        .session(httpSession)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(badShares.getResponse().getStatus()).isEqualTo(400);

        MvcResult badRate = mockMvc.perform(get("/api/v1/instruments/dividends/SBER/calc")
                        .param("taxRatePct", "20")
                        .session(httpSession)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(badRate.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void calculateTaxPostRequiresCsrfAndComputesNdflAndIis() throws Exception {
        String payload = json.writeValueAsString(Map.of(
                "grossProfitRub", "3000000.00",
                "priorLossesRub", "200000.00",
                "dividendsRub", "100000.00",
                "accountType", "IIS_A",
                "annualContributionsRub", "400000.00"));

        // Без CSRF заголовка -> 403
        MvcResult noCsrf = mockMvc.perform(post("/api/v1/instruments/tax/calc")
                        .session(httpSession)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn();
        assertThat(noCsrf.getResponse().getStatus()).isEqualTo(403);

        MvcResult ok = mockMvc.perform(post("/api/v1/instruments/tax/calc")
                        .session(httpSession)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf))
                        .header("X-XSRF-TOKEN", xsrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);

        JsonNode body = json.readTree(ok.getResponse().getContentAsString());
        assertThat(body.get("accountType").asString()).isEqualTo("IIS_A");
        assertThat(body.get("lossOffsetUsedRub").asString()).isEqualTo("200000.00");
        assertThat(body.get("taxableProfitRub").asString()).isEqualTo("2800000.00");
        // 2 400 000 * 13% + 400 000 * 15% = 312 000 + 60 000 = 372 000
        assertThat(body.get("tradingTaxRub").asString()).isEqualTo("372000.00");
        assertThat(body.get("dividendTaxRub").asString()).isEqualTo("15000.00");
        assertThat(body.get("totalTaxRub").asString()).isEqualTo("387000.00");
        assertThat(body.get("iisDeductionRefundRub").asString()).isEqualTo("60000.00");
    }

    @Test
    void calculateDcaPostComputesXirrAndTwrAndValidatesInput() throws Exception {
        String payload = json.writeValueAsString(Map.of(
                "cashflows",
                List.of(
                        Map.of("date", "2025-01-01", "amountRub", "100000.00"),
                        Map.of("date", "2025-07-02", "amountRub", "100000.00", "portfolioValueBeforeRub", "110000.00")),
                "finalValueRub",
                "252000.00",
                "finalDate",
                "2026-01-01"));

        MvcResult ok = mockMvc.perform(post("/api/v1/instruments/dca/calc")
                        .session(httpSession)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf))
                        .header("X-XSRF-TOKEN", xsrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);

        JsonNode body = json.readTree(ok.getResponse().getContentAsString());
        assertThat(body.get("totalContributedRub").asString()).isEqualTo("200000.00");
        assertThat(body.get("netProfitRub").asString()).isEqualTo("52000.00");
        assertThat(body.get("twrPct").asString()).isEqualTo("32.00");
        assertThat(new BigDecimal(body.get("xirrPct").asString())).isGreaterThan(new BigDecimal("30.00"));
        assertThat(body.get("cashflowsCount").asInt()).isEqualTo(2);

        String badPayload = json.writeValueAsString(
                Map.of("cashflows", List.of(), "finalValueRub", "100000.00", "finalDate", "2026-01-01"));
        MvcResult bad = mockMvc.perform(post("/api/v1/instruments/dca/calc")
                        .session(httpSession)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf))
                        .header("X-XSRF-TOKEN", xsrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badPayload))
                .andReturn();
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void endpointsRequireAuthentication() throws Exception {
        assertThat(mockMvc.perform(get("/api/v1/instruments/dividends"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/v1/instruments/dividends/SBER/calc"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }
}
