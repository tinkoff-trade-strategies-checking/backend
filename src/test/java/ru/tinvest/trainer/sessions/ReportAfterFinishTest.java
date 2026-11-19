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
import java.util.UUID;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.tinvest.trainer.PostgresIntegrationTest;
import ru.tinvest.trainer.marketdata.LaunchPlan;
import ru.tinvest.trainer.marketdata.Papers;
import ru.tinvest.trainer.marketdata.PlannedPaper;
import ru.tinvest.trainer.marketdata.Regime;
import ru.tinvest.trainer.marketdata.Snapshot;
import ru.tinvest.trainer.marketdata.StartMode;
import ru.tinvest.trainer.marketdata.Timeframe;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Сквозной путь «пользователь завершил сессию → в фоне строится и сохраняется отчёт → {@code report_ready} в потоке
 * событий после {@code finished}»: настоящие плеер, слушатель и сборщик отчётов, без подмен (воркер — {@link FakeWorker}).
 */
@AutoConfigureMockMvc
@Import(ReportHistoryReplayApiTest.TestWiring.class)
class ReportAfterFinishTest extends PostgresIntegrationTest {

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
    private ObjectMapper json;

    @Test
    void finishBuildsAndStoresTheReportAndPublishesReportReadyAfterFinished() throws Exception {
        UUID userId = UUID.randomUUID();
        String email = "u" + UUID.randomUUID() + "@example.com";
        jdbc.sql("INSERT INTO identity.user_account (id, email, password_hash) VALUES (:id, :e, :h)")
                .param("id", userId)
                .param("e", email)
                .param("h", encoder.encode(PASSWORD))
                .update();
        Cookie anonymous =
                mockMvc.perform(get("/api/v1/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(anonymous)
                        .header("X-XSRF-TOKEN", anonymous.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        MockHttpSession http = (MockHttpSession) login.getRequest().getSession(false);
        String xsrf = login.getResponse().getCookie("XSRF-TOKEN").getValue();
        UUID id = sessions.create(userId, StartMode.CUSTOM, 20, plan());

        MockHttpServletRequestBuilder finish = post("/api/v1/sessions/" + id + "/finish")
                .session(http)
                .cookie(new Cookie("XSRF-TOKEN", xsrf))
                .header("X-XSRF-TOKEN", xsrf)
                .contentType(MediaType.APPLICATION_JSON)
                .content("");
        assertThat(mockMvc.perform(finish).andReturn().getResponse().getStatus())
                .isEqualTo(200);

        List<String> types = List.of();
        for (int i = 0; i < 200 && !types.contains("report_ready"); i++) {
            Thread.sleep(50);
            types = eventTypes(id);
        }
        assertThat(types).contains("finished", "report_ready");
        assertThat(types.indexOf("report_ready")).isGreaterThan(types.indexOf("finished"));
        assertThat(types.stream().filter("report_ready"::equals)).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM analysis.session_report WHERE session_id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        MvcResult report = mockMvc.perform(get("/api/v1/sessions/" + id + "/report")
                        .session(http)
                        .cookie(new Cookie("XSRF-TOKEN", xsrf))
                        .header("X-XSRF-TOKEN", xsrf))
                .andReturn();
        JsonNode body = json.readTree(report.getResponse().getContentAsString());
        assertThat(body.get("status").asString()).isEqualTo("READY");
        assertThat(body.get("report").get("finishedEarly").asBoolean()).isTrue();
    }

    private List<String> eventTypes(UUID id) {
        return new ArrayList<>(
                jdbc.sql("SELECT type FROM sessions.session_event WHERE session_id = :id ORDER BY event_id")
                        .param("id", id)
                        .query(String.class)
                        .list());
    }

    private static LaunchPlan plan() {
        List<PlannedPaper> papers = List.of(
                new PlannedPaper(Papers.id("SBER"), "SBER", "Сектор0", 10, new BigDecimal("0.01")),
                new PlannedPaper(Papers.id("GAZP"), "GAZP", "Сектор1", 11, new BigDecimal("0.01")));
        Snapshot snapshot = registeredSnapshot();
        LocalDate start = LocalDate.of(2022, 3, 14);
        return new LaunchPlan(
                snapshot,
                false,
                start,
                start.plusDays(26),
                20,
                Regime.CALM,
                0.123456,
                0.0777,
                0.15,
                0.35,
                Timeframe.D1,
                20,
                papers,
                new BigDecimal("500000.00"));
    }
}
