package ru.tinvest.trainer.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static ru.tinvest.trainer.PostgresIntegrationTest.registeredSnapshot;

import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.tinvest.trainer.PostgresIntegrationTest;
import ru.tinvest.trainer.marketdata.LaunchPlan;
import ru.tinvest.trainer.marketdata.Papers;
import ru.tinvest.trainer.marketdata.PlannedPaper;
import ru.tinvest.trainer.marketdata.Regime;
import ru.tinvest.trainer.marketdata.SecurityFundamental;
import ru.tinvest.trainer.marketdata.SecurityFundamentals;
import ru.tinvest.trainer.marketdata.Snapshot;
import ru.tinvest.trainer.marketdata.StartMode;
import ru.tinvest.trainer.marketdata.Timeframe;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Интеграционные API-тесты фундаментальных показателей (PostgreSQL + Flyway V20 + Spring Security + MockMvc):
 * {@code GET /api/v1/papers/{ticker}/fundamentals} и {@code GET /api/v1/sessions/{sessionId}/fundamentals},
 * проверка инвариантов INV-1 (point-in-time без заглядывания в будущее) и INV-7 (полное скрытие в слепом режиме).
 */
@AutoConfigureMockMvc
@Import(ArenaApiTest.TestWiring.class)
class FundamentalsApiTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private Sessions sessions;

    @Autowired
    private FakeWorker worker;

    @Autowired
    private SecurityFundamentals fundamentals;

    @Autowired
    private ObjectMapper json;

    private UUID userId;
    private MockHttpSession httpSession;
    private String xsrf;

    @BeforeEach
    void setUp() throws Exception {
        worker.reset();
        userId = createUser("f" + UUID.randomUUID() + "@example.com");
        login();
    }

    @Test
    void paperFundamentalsReturnsLatestSnapshotForSeededBlueChipAndHandlesUnknownTicker() throws Exception {
        MvcResult sberRes = mockMvc.perform(get("/api/v1/papers/sber/fundamentals")
                        .session(httpSession)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(sberRes.getResponse().getStatus()).isEqualTo(200);
        JsonNode sber = json.readTree(sberRes.getResponse().getContentAsString());
        assertThat(sber.get("ticker").asString()).isEqualTo("SBER");
        assertThat(sber.get("available").asBoolean()).isTrue();
        JsonNode snap = sber.get("snapshot");
        assertThat(snap.get("reportDate").asString()).isEqualTo("2025-12-31");
        assertThat(snap.get("availableAt").asString()).isEqualTo("2026-02-26");
        assertThat(snap.get("currency").asString()).isEqualTo("RUB");
        assertThat(snap.get("peRatio").asString()).isEqualTo("4.25");
        assertThat(snap.get("evEbitda").isNull()).isTrue();

        MvcResult unknownRes = mockMvc.perform(get("/api/v1/papers/NO_SUCH_TICKER/fundamentals")
                        .session(httpSession)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(unknownRes.getResponse().getStatus()).isEqualTo(200);
        JsonNode unknown = json.readTree(unknownRes.getResponse().getContentAsString());
        assertThat(unknown.get("ticker").asString()).isEqualTo("NO_SUCH_TICKER");
        assertThat(unknown.get("available").asBoolean()).isFalse();
        assertThat(unknown.get("snapshot").isNull()).isTrue();
    }

    @Test
    void activeBlindSessionHidesFundamentalsCompletely() throws Exception {
        UUID id = newSession(true, List.of("SBER", "GAZP", "LKOH"), LocalDate.of(2024, 6, 1));

        MvcResult res = mockMvc.perform(get("/api/v1/sessions/" + id + "/fundamentals")
                        .session(httpSession)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        String raw = res.getResponse().getContentAsString();
        JsonNode body = json.readTree(raw);

        assertThat(body.get("hiddenByBlindMode").asBoolean()).isTrue();
        assertThat(body.get("asOfDate").isNull()).isTrue();
        assertThat(body.get("items")).isEmpty();
        // INV-7: ни тикеров, ни дат, ни метрик в ответе; воркер вообще не вызывался
        assertThat(raw).doesNotContain("SBER", "GAZP", "LKOH", "2024", "2023", "peRatio", "marketCapRub");
        assertThat(worker.replayJournals).isEmpty();
    }

    @Test
    void finishedBlindSessionRevealsFundamentalsFilteredByCursorDate() throws Exception {
        UUID id = newSession(true, List.of("SBER", "LKOH"), LocalDate.of(2022, 3, 14));
        jdbc.sql("UPDATE sessions.session SET status = 'completed' WHERE id = :id")
                .param("id", id)
                .update();

        MvcResult res = mockMvc.perform(get("/api/v1/sessions/" + id + "/fundamentals")
                        .session(httpSession)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(res.getResponse().getContentAsString());

        assertThat(body.get("hiddenByBlindMode").asBoolean()).isFalse();
        assertThat(body.get("asOfDate").asString()).isEqualTo("2022-03-14");
        assertThat(body.get("items")).hasSize(2);
        for (JsonNode item : body.get("items")) {
            assertThat(item.get("available").asBoolean()).isTrue();
            LocalDate availableAt =
                    LocalDate.parse(item.get("snapshot").get("availableAt").asString());
            assertThat(availableAt).isBeforeOrEqualTo(LocalDate.of(2022, 3, 14));
        }
    }

    @Test
    void openSessionEnforcesPointInTimeWithoutLookahead() throws Exception {
        // FakeWorker.BASE_TIME_MS = 2022-03-14T07:00:00Z -> на шаге 0 дата курсора 2022-03-14.
        // SBER имеет срез available_at = 2022-03-02 (<= 2022-03-14), а более новые — 2023..2026.
        // GAZP имеет самый ранний срез available_at = 2022-04-28 (> 2022-03-14) -> не должен утечь!
        UUID id = newSession(false, List.of("SBER", "GAZP"), LocalDate.of(2022, 3, 14));

        MvcResult res = mockMvc.perform(get("/api/v1/sessions/" + id + "/fundamentals")
                        .session(httpSession)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf)))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(res.getResponse().getContentAsString());

        assertThat(body.get("hiddenByBlindMode").asBoolean()).isFalse();
        assertThat(body.get("asOfDate").asString()).isEqualTo("2022-03-14");

        JsonNode sberItem = findItem(body.get("items"), "SBER");
        assertThat(sberItem.get("available").asBoolean()).isTrue();
        assertThat(sberItem.get("snapshot").get("reportDate").asString()).isEqualTo("2021-12-31");
        assertThat(sberItem.get("snapshot").get("availableAt").asString()).isEqualTo("2022-03-02");

        JsonNode gazpItem = findItem(body.get("items"), "GAZP");
        assertThat(gazpItem.get("available").asBoolean()).isFalse();
        assertThat(gazpItem.get("snapshot").isNull()).isTrue();
    }

    @Test
    void pointInTimeQueryAcrossHistoricalYearsNeverLeaksFutureReports() {
        List<String> tickers = List.of("SBER", "GAZP", "LKOH", "YDEX", "ROSN", "NVTK", "T", "PLZL", "MOEX");
        for (LocalDate asOf : List.of(
                LocalDate.of(2022, 6, 1),
                LocalDate.of(2023, 6, 1),
                LocalDate.of(2024, 6, 1),
                LocalDate.of(2025, 6, 1),
                LocalDate.of(2026, 6, 1))) {
            Map<String, SecurityFundamental> snap = fundamentals.findLatestAsOf(tickers, asOf);
            assertThat(snap).hasSize(tickers.size());
            for (SecurityFundamental f : snap.values()) {
                assertThat(f.availableAt()).isBeforeOrEqualTo(asOf);
                assertThat(f.reportDate().getYear()).isEqualTo(asOf.getYear() - 1);
            }
        }
        int distinctTickers = jdbc.sql("SELECT COUNT(DISTINCT ticker) FROM marketdata.security_fundamentals")
                .query(Integer.class)
                .single();
        assertThat(distinctTickers).isGreaterThanOrEqualTo(40);
    }

    private static JsonNode findItem(JsonNode items, String ticker) {
        for (JsonNode item : items) {
            if (ticker.equals(item.get("ticker").asString())) {
                return item;
            }
        }
        throw new AssertionError("Ticker not found in items: " + ticker);
    }

    private UUID newSession(boolean blind, List<String> secids, LocalDate start) {
        List<PlannedPaper> list = new ArrayList<>();
        for (int i = 0; i < secids.size(); i++) {
            list.add(new PlannedPaper(
                    Papers.id(secids.get(i)),
                    secids.get(i),
                    "Сектор" + i,
                    10,
                    new BigDecimal("0.01"),
                    "Бумага " + secids.get(i)));
        }
        Snapshot snapshot = registeredSnapshot();
        LaunchPlan plan = new LaunchPlan(
                snapshot,
                blind,
                start,
                start.plusDays(26),
                20,
                Regime.CALM,
                0.12,
                0.07,
                0.15,
                0.35,
                Timeframe.D1,
                20,
                list,
                new BigDecimal("500000.00"));
        return sessions.create(userId, blind ? StartMode.RANDOM : StartMode.CUSTOM, 20, plan);
    }

    private UUID createUser(String email) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO identity.user_account (id, email, password_hash) VALUES (:id, :e, :h)")
                .param("id", id)
                .param("e", email)
                .param("h", encoder.encode(PASSWORD))
                .update();
        return id;
    }

    private void login() throws Exception {
        String email = jdbc.sql("SELECT email FROM identity.user_account WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .single();
        MvcResult me = mockMvc.perform(get("/api/v1/me")).andReturn();
        String initialXsrf = me.getResponse().getCookie("XSRF-TOKEN").getValue();
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(new Cookie("XSRF-TOKEN", initialXsrf))
                        .header("X-XSRF-TOKEN", initialXsrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(204);
        httpSession = (MockHttpSession) login.getRequest().getSession(false);
        xsrf = login.getResponse().getCookie("XSRF-TOKEN").getValue();
    }
}
