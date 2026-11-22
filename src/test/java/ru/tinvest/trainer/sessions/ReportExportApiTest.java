package ru.tinvest.trainer.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static ru.tinvest.trainer.PostgresIntegrationTest.registeredSnapshot;

import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Sheet;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.tinvest.trainer.PostgresIntegrationTest;
import ru.tinvest.trainer.marketdata.LaunchPlan;
import ru.tinvest.trainer.marketdata.Papers;
import ru.tinvest.trainer.marketdata.PlannedPaper;
import ru.tinvest.trainer.marketdata.Regime;
import ru.tinvest.trainer.marketdata.Snapshot;
import ru.tinvest.trainer.marketdata.StartMode;
import ru.tinvest.trainer.marketdata.Timeframe;

/**
 * Выгрузка отчёта (ФТ-17): файл строится из сохранённого отчёта, доступ — после завершения (INV-7), чужая сессия — 404,
 * формат — {@code xlsx} или {@code csv}.
 */
@AutoConfigureMockMvc
@Import(ReportHistoryReplayApiTest.TestWiring.class)
class ReportExportApiTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse";
    private static final List<String> SECIDS = List.of("SBER", "GAZP");

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
    private tools.jackson.databind.ObjectMapper json;

    private UUID userId;
    private MockHttpSession httpSession;
    private String xsrf;

    @BeforeEach
    void setUp() throws Exception {
        worker.reset();
        userId = createUser("u" + UUID.randomUUID() + "@example.com");
        login(emailOf(userId));
    }

    @Test
    void xlsxOfAFinishedSessionIsTheStoredReportWithTheDecidedFileName() throws Exception {
        UUID id = finishedSession(false);

        MvcResult result = export(id, "xlsx");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .contains("attachment")
                .contains("report-" + id + ".xlsx");
        try (ReadableWorkbook workbook = new ReadableWorkbook(
                new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            assertThat(workbook.getSheets().map(Sheet::getName).toList())
                    .containsExactly(
                            "Сводка",
                            "Метрики",
                            "Сделки",
                            "Заявки",
                            "Издержки",
                            "Кривая капитала",
                            "Параметры и версии",
                            "Сравнение",
                            "Расхождения");
            try (var rows = workbook.findSheet("Метрики").orElseThrow().openStream()) {
                var metrics = rows.filter(r -> "Итоговый капитал".equals(r.getCellText(0)))
                        .findFirst()
                        .orElseThrow();
                // деньги — числовая ячейка с точным значением отчёта ядра
                assertThat(metrics.getCellText(1)).isEqualTo("500600.00");
                assertThat(metrics.getCellText(2)).isEqualTo("501200.00");
            }
            try (var rows = workbook.findSheet("Сравнение").orElseThrow().openStream()) {
                var comparisonRows = rows.toList();
                assertThat(comparisonRows.get(1).getCellText(0)).isEqualTo("Итоговый капитал");
                assertThat(comparisonRows.get(1).getCellText(3)).isEqualTo("алгоритм");
            }
            try (var rows = workbook.findSheet("Расхождения").orElseThrow().openStream()) {
                var divRows = rows.toList();
                assertThat(divRows).hasSize(2);
                assertThat(divRows.get(1).getCellText(3)).isEqualTo("SBER");
                assertThat(divRows.get(1).getCellText(5)).isEqualTo("упущенное действие");
            }
        }
    }

    @Test
    void csvIsAZipWithABomFilePerSheetAndTheDecidedFileName() throws Exception {
        UUID id = finishedSession(false);

        MvcResult result = export(id, "csv");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).isEqualTo("application/zip");
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("report-" + id + ".zip");
        List<String> names = new ArrayList<>();
        String metrics = null;
        String divergences = null;
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(result.getResponse().getContentAsByteArray()), StandardCharsets.UTF_8)) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                names.add(e.getName());
                String text = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(text).startsWith("﻿").contains("\r\n");
                if (e.getName().equals("Метрики.csv")) {
                    metrics = text;
                } else if (e.getName().equals("Расхождения.csv")) {
                    divergences = text;
                }
            }
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        assertThat(names)
                .containsExactly(
                        "Сводка.csv",
                        "Метрики.csv",
                        "Сделки.csv",
                        "Заявки.csv",
                        "Издержки.csv",
                        "Кривая капитала.csv",
                        "Параметры и версии.csv",
                        "Сравнение.csv",
                        "Расхождения.csv");
        assertThat(metrics)
                .startsWith("﻿Метрика;Человек;Алгоритм\r\n")
                .contains("Итоговый капитал;500600,00;501200,00\r\n");
        assertThat(divergences)
                .contains(";SBER;")
                .contains(";упущенное действие;")
                .contains(";120,00;0,020000;SMA(20) пересекла SMA(50) снизу вверх\r\n");
    }

    @Test
    void finishedBlindSessionIsExportedRevealed() throws Exception {
        UUID id = finishedSession(true);

        MvcResult result = export(id, "csv");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        // раскрытие после завершения: в файле настоящие тикеры и период, а не обезличенные подписи
        String all = String.join("\n", csvFiles(result).values());
        assertThat(all).contains("SBER").contains("GAZP").contains("14.03.2022");
    }

    @Test
    void unfinishedSessionIsRefusedEvenWhenTheWorkerIsDown() throws Exception {
        UUID id = newSession(true);
        setStep(id, 3);
        worker.down = true;

        MvcResult result = export(id, "xlsx");

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"NOT_FINISHED\"");
    }

    @Test
    void ordersSheetShowsRealJournalCommandsWithBothOcoLegsAndRejections() throws Exception {
        UUID id = newSession(false);
        String first = orderId(httpPost(id, "/orders", order("SBER", "buy", "limit", 2, "\"limitPrice\":\"190.00\"")));
        httpPost(id, "/orders", ocoOrder());
        assertThat(httpDelete(id, "/orders/" + first + "?seenStep=0")
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        // повторная отмена — отказ воркера, он тоже в журнале
        httpPost(id, "/orders", order("SBER", "buy", "limit", 1, "\"limitPrice\":\"1.00\""));
        httpDelete(id, "/orders/" + first + "?seenStep=0");
        String second = orderId(httpPost(id, "/orders", order("GAZP", "buy", "limit", 2, "\"limitPrice\":\"150.00\"")));
        httpPut(id, "/orders/" + second, "{\"seenStep\":0,\"lots\":3,\"tif\":\"day\",\"limitPrice\":\"155.00\"}");
        setStep(id, 6);
        finish(id);
        awaitReport(id);

        String orders = csvFiles(export(id, "csv")).get("Заявки.csv");

        assertThat(orders)
                .startsWith("\uFEFFКоманда;Дата;Время;Действие;Заявка ядра;Бумага;Сторона;Тип;Лотов;Цена лимита;"
                        + "Цена стопа;Трейлинг, %;Срок;Результат;Причина отказа\r\n")
                .containsPattern(";подача;;SBER;покупка;лимитная;2;190,00;;;до отмены;принята;\r\n")
                .containsPattern(";подача;;SBER;продажа;стоп;1;;180,00;;до отмены;принята;\r\n")
                .containsPattern(
                        ";подача \\(вторая нога OCO\\);;SBER;продажа;тейк-профит;1;220,00;;;до отмены;принята;\r\n")
                .containsPattern(";отмена;\\d+;{9}принята;\r\n")
                .containsPattern(";отмена;\\d+;{9}отклонена;заявка не активна\r\n")
                .containsPattern(";замена;\\d+;GAZP;покупка;лимитная;3;155,00;;;на день;принята;\r\n");
    }

    @Test
    void unfinishedSessionIsRefusedWithoutAnyDataEspeciallyBlind() throws Exception {
        for (boolean blind : new boolean[] {true, false}) {
            UUID id = newSession(blind);
            setStep(id, 3);

            for (String format : List.of("xlsx", "csv")) {
                MvcResult result = export(id, format);

                assertThat(result.getResponse().getStatus()).isEqualTo(409);
                assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"NOT_FINISHED\"");
                assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
                String text = result.getResponse().getContentAsString();
                for (String secid : SECIDS) {
                    assertThat(text).doesNotContain(secid);
                }
                assertThat(text).doesNotContain("Сектор", "2022-03", "500000");
                assertThat(result.getResponse().getHeader("Content-Disposition"))
                        .isNull();
            }
        }
        assertThat(worker.reportRequests).isEmpty();
    }

    @Test
    void otherUsersAndUnknownSessionsAreNotFound() throws Exception {
        UUID id = finishedSession(false);
        login(emailOf(createUser("stranger" + UUID.randomUUID() + "@example.com")));

        assertThat(export(id, "xlsx").getResponse().getStatus()).isEqualTo(404);
        assertThat(export(UUID.randomUUID(), "csv").getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void unknownFormatIsABadRequestAndLoginIsRequired() throws Exception {
        UUID id = finishedSession(false);

        assertThat(export(id, "pdf").getResponse().getStatus()).isEqualTo(400);
        assertThat(mockMvc.perform(get("/api/v1/sessions/" + id + "/report/export?format=csv"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }

    @Test
    void reportNotBuiltYetIsConflictAndExportWorksOnceItIsStored() throws Exception {
        worker.down = true;
        UUID id = newSession(false);
        setStep(id, 6);
        finish(id);

        MvcResult building = export(id, "xlsx");
        assertThat(building.getResponse().getStatus()).isEqualTo(409);
        assertThat(building.getResponse().getContentAsString()).contains("\"code\":\"REPORT_BUILDING\"");

        worker.down = false;
        MvcResult ready = null;
        for (int i = 0; i < 200; i++) {
            Thread.sleep(50);
            ready = export(id, "xlsx");
            if (ready.getResponse().getStatus() == 200) {
                break;
            }
        }
        assertThat(ready.getResponse().getStatus()).isEqualTo(200);
    }

    // ---------- вспомогательное ----------

    private static String order(String secid, String side, String type, int lots, String price) {
        return "{\"seenStep\":0,\"paperId\":\"" + Papers.id(secid) + "\",\"side\":\"" + side + "\",\"type\":\"" + type
                + "\",\"lots\":" + lots + ",\"tif\":\"gtc\"," + price + "}";
    }

    private static String ocoOrder() {
        return "{\"seenStep\":0,\"paperId\":\"" + Papers.id("SBER") + "\",\"side\":\"sell\",\"type\":\"oco\","
                + "\"lots\":1,\"tif\":\"gtc\",\"stopPrice\":\"180.00\",\"takeProfitPrice\":\"220.00\"}";
    }

    private String orderId(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString())
                .get("orderIds")
                .get(0)
                .asString();
    }

    private MvcResult httpPost(UUID id, String suffix, String body) throws Exception {
        return mockMvc.perform(withAuth(post("/api/v1/sessions/" + id + suffix))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult httpPut(UUID id, String suffix, String body) throws Exception {
        return mockMvc.perform(withAuth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/v1/sessions/" + id + suffix))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult httpDelete(UUID id, String suffix) throws Exception {
        return mockMvc.perform(withAuth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v1/sessions/" + id + suffix)))
                .andReturn();
    }

    private void awaitReport(UUID id) throws Exception {
        for (int i = 0; i < 200; i++) {
            MvcResult report = mockMvc.perform(withAuth(get("/api/v1/sessions/" + id + "/report")))
                    .andReturn();
            if (report.getResponse().getContentAsString().contains("\"READY\"")) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("отчёт не построен");
    }

    /** Файлы CSV-архива выгрузки: имя листа с расширением → текст. */
    private static java.util.Map<String, String> csvFiles(MvcResult result) throws IOException {
        java.util.Map<String, String> files = new java.util.LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(result.getResponse().getContentAsByteArray()), StandardCharsets.UTF_8)) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                files.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    private UUID finishedSession(boolean blind) throws Exception {
        UUID id = newSession(blind);
        setStep(id, 6);
        finish(id);
        for (int i = 0; i < 200; i++) {
            MvcResult report = mockMvc.perform(withAuth(get("/api/v1/sessions/" + id + "/report")))
                    .andReturn();
            if (report.getResponse().getContentAsString().contains("\"READY\"")) {
                return id;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("отчёт не построен");
    }

    private MvcResult export(UUID id, String format) throws Exception {
        return mockMvc.perform(withAuth(get("/api/v1/sessions/" + id + "/report/export?format=" + format)))
                .andReturn();
    }

    private void finish(UUID id) {
        jdbc.sql("UPDATE sessions.session SET status = 'completed' WHERE id = :id")
                .param("id", id)
                .update();
    }

    private void setStep(UUID id, long step) {
        jdbc.sql("UPDATE sessions.session SET current_step = :s WHERE id = :id")
                .param("s", step)
                .param("id", id)
                .update();
    }

    private UUID newSession(boolean blind) {
        LocalDate start = LocalDate.of(2022, 3, 14);
        List<PlannedPaper> list = new ArrayList<>();
        for (int i = 0; i < SECIDS.size(); i++) {
            list.add(new PlannedPaper(
                    Papers.id(SECIDS.get(i)), SECIDS.get(i), "Сектор" + i, 10 + i, new BigDecimal("0.01")));
        }
        Snapshot snapshot = registeredSnapshot();
        LaunchPlan plan = new LaunchPlan(
                snapshot,
                blind,
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
                list,
                new BigDecimal("500000.00"));
        return sessions.create(userId, blind ? StartMode.RANDOM : StartMode.CUSTOM, 20, plan);
    }

    private UUID createUser(String address) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO identity.user_account (id, email, password_hash) VALUES (:id, :e, :h)")
                .param("id", id)
                .param("e", address)
                .param("h", encoder.encode(PASSWORD))
                .update();
        return id;
    }

    private String emailOf(UUID id) {
        return jdbc.sql("SELECT email FROM identity.user_account WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private void login(String address) throws Exception {
        Cookie anonymous =
                mockMvc.perform(get("/api/v1/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(anonymous)
                        .header("X-XSRF-TOKEN", anonymous.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + address + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        httpSession = (MockHttpSession) result.getRequest().getSession(false);
        xsrf = result.getResponse().getCookie("XSRF-TOKEN").getValue();
    }

    private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder) {
        return builder.session(httpSession)
                .cookie(new Cookie("XSRF-TOKEN", xsrf))
                .header("X-XSRF-TOKEN", xsrf);
    }
}
