package ru.tinvest.trainer.workshop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.tinvest.trainer.PostgresIntegrationTest;
import ru.tinvest.trainer.worker.PeriodClassification;
import ru.tinvest.trainer.worker.SimulationWorkerClient;
import ru.tinvest.trainer.worker.WorkerTypes.CheckLevel;
import ru.tinvest.trainer.worker.WorkerTypes.DiagnosticFix;
import ru.tinvest.trainer.worker.WorkerTypes.EstimateResult;
import ru.tinvest.trainer.worker.WorkerTypes.JournalRecord;
import ru.tinvest.trainer.worker.WorkerTypes.Market;
import ru.tinvest.trainer.worker.WorkerTypes.OrderParams;
import ru.tinvest.trainer.worker.WorkerTypes.ReplayResult;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyCheck;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyDiagnostic;
import ru.tinvest.trainer.worker.WorkerTypes.StrategyParamDecl;
import ru.tinvest.trainer.worker.WorkerTypes.TextEdit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** REST API мастерской: каталог, черновики, проверка DSL, публикация, форк (UC-20) и скрытие (UC-21). */
@AutoConfigureMockMvc
@Import(WorkshopApiTest.TestWiring.class)
class WorkshopApiTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private StrategyCatalog catalog;

    @Autowired
    private StubWorker worker;

    private UserSession authorA;
    private UserSession authorB;

    @BeforeEach
    void setUp() throws Exception {
        worker.reset();
        // Версии и стратегии защищены триггерами INV-5 — создаём свежих пользователей на каждый тест.
        authorA = loginNewUser("authora-" + UUID.randomUUID() + "@example.com");
        authorB = loginNewUser("authorb-" + UUID.randomUUID() + "@example.com");
    }

    private UserSession loginNewUser(String email) throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO identity.user_account (id, email, password_hash) VALUES (:id, :e, :h)")
                .param("id", id)
                .param("e", email)
                .param("h", encoder.encode(PASSWORD))
                .update();
        Cookie anonymous =
                mockMvc.perform(get("/api/v1/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(anonymous)
                        .header("X-XSRF-TOKEN", anonymous.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent())
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        Cookie xsrf = result.getResponse().getCookie("XSRF-TOKEN");
        return new UserSession(id, session, xsrf);
    }

    private MvcResult createDraft(UserSession user, String name, String description, String source) throws Exception {
        String body = json.writeValueAsString(Map.of("name", name, "description", description, "source", source));
        return mockMvc.perform(post("/api/v1/workshop/algorithms")
                        .session(user.session())
                        .cookie(user.xsrf())
                        .header("X-XSRF-TOKEN", user.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult publish(UserSession user, UUID strategyId, String summary) throws Exception {
        return mockMvc.perform(post("/api/v1/workshop/algorithms/{id}/publish", strategyId)
                        .session(user.session())
                        .cookie(user.xsrf())
                        .header("X-XSRF-TOKEN", user.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("summary", summary))))
                .andReturn();
    }

    @Test
    void systemAlgorithmIsPresentInCatalogAndCannotBeHidden() throws Exception {
        mockMvc.perform(get("/api/v1/workshop/algorithms")
                        .session(authorA.session())
                        .param("authorId", StrategyCatalog.SYSTEM_AUTHOR_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(StrategyCatalog.SYSTEM_STRATEGY_ID.toString()))
                .andExpect(jsonPath("$.items[0].name").value("Эталон платформы"))
                .andExpect(jsonPath("$.items[0].isSystem").value(true))
                .andExpect(jsonPath("$.items[0].isMine").value(false))
                .andExpect(jsonPath("$.items[0].latestVersion").value(1))
                .andExpect(jsonPath("$.items[0].versionsCount").value(1))
                .andExpect(jsonPath("$.items[0].hasLinterWarnings").value(false))
                .andExpect(jsonPath("$.items[0].lookaheadLevel").value("OK"));

        mockMvc.perform(get("/api/v1/workshop/algorithms/{id}", StrategyCatalog.SYSTEM_STRATEGY_ID)
                        .session(authorA.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.algorithm.latestVersionId").value(StrategyCatalog.SYSTEM_VERSION_ID.toString()))
                .andExpect(jsonPath("$.algorithm.versions.length()").value(1))
                .andExpect(jsonPath("$.algorithm.versions[0].params.length()").value(6));

        mockMvc.perform(patch("/api/v1/workshop/algorithms/{id}/visibility", StrategyCatalog.SYSTEM_STRATEGY_ID)
                        .session(authorA.session())
                        .cookie(authorA.xsrf())
                        .header("X-XSRF-TOKEN", authorA.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"hidden\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void draftCreationValidationUniquenessAndOwnership() throws Exception {
        // Ошибки валидации длины имени и описания
        mockMvc.perform(post("/api/v1/workshop/algorithms")
                        .session(authorA.session())
                        .cookie(authorA.xsrf())
                        .header("X-XSRF-TOKEN", authorA.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \",\"description\":\"ok\",\"source\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        String tooLongName = "x".repeat(81);
        mockMvc.perform(post("/api/v1/workshop/algorithms")
                        .session(authorA.session())
                        .cookie(authorA.xsrf())
                        .header("X-XSRF-TOKEN", authorA.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", tooLongName))))
                .andExpect(status().isBadRequest());

        String tooLongDesc = "d".repeat(2001);
        mockMvc.perform(post("/api/v1/workshop/algorithms")
                        .session(authorA.session())
                        .cookie(authorA.xsrf())
                        .header("X-XSRF-TOKEN", authorA.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Valid", "description", tooLongDesc))))
                .andExpect(status().isBadRequest());

        MvcResult created =
                createDraft(authorA, "Momentum", "Описание", "rule \"r\" when true then buy 1 lots because \"x\"");
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        UUID id = UUID.fromString(json.readTree(created.getResponse().getContentAsString())
                .get("strategyId")
                .asString());

        // Повтор имени у того же автора (без учёта регистра) -> 409 CONFLICT
        MvcResult dup = createDraft(authorA, "momentum", "Другое", "");
        assertThat(dup.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(dup.getResponse().getContentAsString())
                        .get("code")
                        .asString())
                .isEqualTo("CONFLICT");

        // Другой автор может создать стратегию с тем же именем
        MvcResult otherAuthorSameName = createDraft(authorB, "momentum", "Своя", "");
        assertThat(otherAuthorSameName.getResponse().getStatus()).isEqualTo(201);

        // Чужой черновик не читается и не редактируется (404), владелец обновляет успешно
        mockMvc.perform(get("/api/v1/workshop/algorithms/{id}/draft", id).session(authorB.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(put("/api/v1/workshop/algorithms/{id}/draft", id)
                        .session(authorB.session())
                        .cookie(authorB.xsrf())
                        .header("X-XSRF-TOKEN", authorB.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"hacked\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/v1/workshop/algorithms/{id}/draft", id)
                        .session(authorA.session())
                        .cookie(authorA.xsrf())
                        .header("X-XSRF-TOKEN", authorA.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"updated source\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("updated source"));
    }

    @Test
    void checkAndPublishRejectsErrorsAndPublishesValidOrWarningVersions() throws Exception {
        mockMvc.perform(post("/api/v1/workshop/check")
                        .session(authorA.session())
                        .cookie(authorA.xsrf())
                        .header("X-XSRF-TOKEN", authorA.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("source", "ERROR_LA01"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lookaheadLevel").value("ERROR"))
                .andExpect(jsonPath("$.diagnostics[0].code").value("E-LA01"));

        UUID id = UUID.fromString(json.readTree(createDraft(authorA, "CheckedAlgo", "Тест публикации", "ERROR_LA01")
                        .getResponse()
                        .getContentAsString())
                .get("strategyId")
                .asString());

        // Публикация при ошибке -> 422 STRATEGY_INVALID с диагностиками
        MvcResult failedPublish = publish(authorA, id, "v1");
        assertThat(failedPublish.getResponse().getStatus()).isEqualTo(422);
        JsonNode problem = json.readTree(failedPublish.getResponse().getContentAsString());
        assertThat(problem.get("code").asString()).isEqualTo("STRATEGY_INVALID");
        assertThat(problem.get("lookaheadLevel").asString()).isEqualTo("ERROR");
        assertThat(problem.get("diagnostics").size()).isEqualTo(1);

        // Обновляем черновик на стратегию с предупреждением L001 -> публикация проходит (v1)
        mockMvc.perform(put("/api/v1/workshop/algorithms/{id}/draft", id)
                        .session(authorA.session())
                        .cookie(authorA.xsrf())
                        .header("X-XSRF-TOKEN", authorA.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"WARN_L001\"}"))
                .andExpect(status().isOk());

        MvcResult v1 = publish(authorA, id, "Первая версия с L001");
        assertThat(v1.getResponse().getStatus()).isEqualTo(201);
        assertThat(json.readTree(v1.getResponse().getContentAsString())
                        .get("version")
                        .asInt())
                .isEqualTo(1);

        // Обновляем на чистый код -> публикация v2, старая v1 неизменяема
        mockMvc.perform(put("/api/v1/workshop/algorithms/{id}/draft", id)
                        .session(authorA.session())
                        .cookie(authorA.xsrf())
                        .header("X-XSRF-TOKEN", authorA.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"CLEAN_DSL\"}"))
                .andExpect(status().isOk());

        MvcResult v2 = publish(authorA, id, "Чистая версия");
        assertThat(v2.getResponse().getStatus()).isEqualTo(201);
        assertThat(json.readTree(v2.getResponse().getContentAsString())
                        .get("version")
                        .asInt())
                .isEqualTo(2);

        mockMvc.perform(get("/api/v1/workshop/algorithms/{id}", id).session(authorB.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.algorithm.latestVersion").value(2))
                .andExpect(jsonPath("$.algorithm.versionsCount").value(2))
                .andExpect(jsonPath("$.algorithm.hasLinterWarnings").value(false))
                .andExpect(jsonPath("$.algorithm.versions.length()").value(2))
                .andExpect(jsonPath("$.algorithm.versions[0].version").value(2))
                .andExpect(jsonPath("$.algorithm.versions[1].version").value(1));
    }

    @Test
    void catalogFiltersCleanOnlyMineOnlySearchSortAndPagination() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        UUID cleanId = UUID.fromString(json.readTree(createDraft(authorA, "Alpha-" + tag, "Чистый импульс", "CLEAN_DSL")
                        .getResponse()
                        .getContentAsString())
                .get("strategyId")
                .asString());
        publish(authorA, cleanId, "v1");

        UUID warnId =
                UUID.fromString(json.readTree(createDraft(authorA, "Beta-" + tag, "С предупреждением", "WARN_L001")
                                .getResponse()
                                .getContentAsString())
                        .get("strategyId")
                        .asString());
        publish(authorA, warnId, "v1");

        // Черновик без публикации виден в mineOnly=true и не виден в общем каталоге
        UUID draftOnlyId = UUID.fromString(json.readTree(createDraft(authorA, "Gamma-" + tag, "Черновик", "CLEAN_DSL")
                        .getResponse()
                        .getContentAsString())
                .get("strategyId")
                .asString());

        // search по общему каталогу (только опубликованные)
        mockMvc.perform(get("/api/v1/workshop/algorithms")
                        .session(authorB.session())
                        .param("search", tag)
                        .param("sort", "name_asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].id").value(cleanId.toString()))
                .andExpect(jsonPath("$.items[1].id").value(warnId.toString()));

        // cleanOnly=true отсекает Beta (L001)
        mockMvc.perform(get("/api/v1/workshop/algorithms")
                        .session(authorB.session())
                        .param("search", tag)
                        .param("cleanOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(cleanId.toString()));

        // mineOnly=true у автора A видит все 3 (включая неопубликованный черновик)
        mockMvc.perform(get("/api/v1/workshop/algorithms")
                        .session(authorA.session())
                        .param("search", tag)
                        .param("mineOnly", "true")
                        .param("sort", "name_asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[2].id").value(draftOnlyId.toString()))
                .andExpect(jsonPath("$.items[2].latestVersion").value(0));

        // Пагинация pageSize=1
        mockMvc.perform(get("/api/v1/workshop/algorithms")
                        .session(authorA.session())
                        .param("search", tag)
                        .param("mineOnly", "true")
                        .param("sort", "name_asc")
                        .param("page", "2")
                        .param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(warnId.toString()));

        // Большой номер страницы не переполняет int в отрицательный OFFSET
        mockMvc.perform(get("/api/v1/workshop/algorithms")
                        .session(authorA.session())
                        .param("page", "30000000")
                        .param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(30_000_000))
                .andExpect(jsonPath("$.pageSize").value(100))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void visibilityHiddenIsolatesFromOtherUsersAndForkPreservesLinkUc20Uc21() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        UUID origId =
                UUID.fromString(json.readTree(createDraft(authorA, "Original-" + tag, "Авторский алго", "CLEAN_DSL")
                                .getResponse()
                                .getContentAsString())
                        .get("strategyId")
                        .asString());
        MvcResult pubRes = publish(authorA, origId, "v1");
        UUID origVersionId = UUID.fromString(json.readTree(pubRes.getResponse().getContentAsString())
                .get("id")
                .asString());

        // Author B форкает опубликованную версию (UC-20)
        MvcResult fork1 = mockMvc.perform(post("/api/v1/workshop/algorithms/{id}/fork", origId)
                        .session(authorB.session())
                        .cookie(authorB.xsrf())
                        .header("X-XSRF-TOKEN", authorB.xsrf().getValue()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("CLEAN_DSL"))
                .andExpect(jsonPath("$.check.lookaheadLevel").value("OK"))
                .andExpect(jsonPath("$.forkedFrom.versionId").value(origVersionId.toString()))
                .andExpect(jsonPath("$.forkedFrom.strategyName").value("Original-" + tag))
                .andExpect(jsonPath("$.forkedFrom.version").value(1))
                .andReturn();
        UUID fork1Id = UUID.fromString(json.readTree(fork1.getResponse().getContentAsString())
                .get("strategyId")
                .asString());

        // Повторный форк без имени автоматически подбирает уникальное имя с суффиксом
        mockMvc.perform(post("/api/v1/workshop/algorithms/{id}/fork", origId)
                        .session(authorB.session())
                        .cookie(authorB.xsrf())
                        .header("X-XSRF-TOKEN", authorB.xsrf().getValue()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Original-" + tag + " (копия 2)"));

        // Author B не может скрыть чужой алгоритм (404)
        mockMvc.perform(patch("/api/v1/workshop/algorithms/{id}/visibility", origId)
                        .session(authorB.session())
                        .cookie(authorB.xsrf())
                        .header("X-XSRF-TOKEN", authorB.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"hidden\"}"))
                .andExpect(status().isNotFound());

        // Author A скрывает алгоритм (UC-21) -> возвращает WorkshopAlgorithmSummary
        mockMvc.perform(patch("/api/v1/workshop/algorithms/{id}/visibility", origId)
                        .session(authorA.session())
                        .cookie(authorA.xsrf())
                        .header("X-XSRF-TOKEN", authorA.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"hidden\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(origId.toString()))
                .andExpect(jsonPath("$.visibility").value("hidden"))
                .andExpect(jsonPath("$.latestVersionId").value(origVersionId.toString()))
                .andExpect(jsonPath("$.isMine").value(true));

        // Чужой скрытый не виден в каталоге, карточке (200 NOT_FOUND), форке (404) и StrategyCatalog.canAccess
        assertThat(catalog.canAccess(origVersionId, authorB.id())).isFalse();
        assertThat(catalog.canAccess(origVersionId, authorA.id())).isTrue();
        assertThat(catalog.canAccess(origVersionId, null)).isFalse();

        mockMvc.perform(get("/api/v1/workshop/algorithms")
                        .session(authorB.session())
                        .param("search", "Original-" + tag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(get("/api/v1/workshop/algorithms/{id}", origId).session(authorB.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"))
                .andExpect(jsonPath("$.algorithm").doesNotExist());

        mockMvc.perform(post("/api/v1/workshop/algorithms/{id}/fork", origId)
                        .session(authorB.session())
                        .cookie(authorB.xsrf())
                        .header("X-XSRF-TOKEN", authorB.xsrf().getValue()))
                .andExpect(status().isNotFound());

        // Но владелец по-прежнему видит свой скрытый алгоритм и может вернуть его в каталог
        mockMvc.perform(get("/api/v1/workshop/algorithms/{id}", origId).session(authorA.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.algorithm.visibility").value("hidden"));

        // И существующий форк сохраняет ссылку на исходную версию после скрытия исходника (UC-20)
        mockMvc.perform(get("/api/v1/workshop/algorithms/{id}/draft", fork1Id).session(authorB.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.forkedFrom.versionId").value(origVersionId.toString()));
    }

    @Test
    void forkTruncatesLongNameWithoutSplittingSurrogatePair() throws Exception {
        // MAX_NAME_LENGTH (80) - " (копия)" (8) = 72; суррогатная пара на позициях 71-72 не должна рваться
        String prefix = "a".repeat(71);
        String nameWithBoundarySurrogate = prefix + "\uD83D\uDE80";
        UUID origId =
                UUID.fromString(json.readTree(createDraft(authorA, nameWithBoundarySurrogate, "Описание", "CLEAN_DSL")
                                .getResponse()
                                .getContentAsString())
                        .get("strategyId")
                        .asString());
        assertThat(publish(authorA, origId, "v1").getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(post("/api/v1/workshop/algorithms/{id}/fork", origId)
                        .session(authorB.session())
                        .cookie(authorB.xsrf())
                        .header("X-XSRF-TOKEN", authorB.xsrf().getValue()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(prefix + " (копия)"));
    }

    @Test
    void softDeleteDraftHidesFromCatalogAllowsNameReuseAndRejectsFurtherEdits() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String name = "DraftDelete-" + tag;
        UUID draftId = UUID.fromString(json.readTree(createDraft(authorA, name, "Черновик", "CLEAN_DSL")
                        .getResponse()
                        .getContentAsString())
                .get("strategyId")
                .asString());

        // Чужой пользователь не может удалить (404), системный — 409
        mockMvc.perform(delete("/api/v1/workshop/algorithms/{id}", draftId)
                        .session(authorB.session())
                        .cookie(authorB.xsrf())
                        .header("X-XSRF-TOKEN", authorB.xsrf().getValue()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/workshop/algorithms/{id}", StrategyCatalog.SYSTEM_STRATEGY_ID)
                        .session(authorA.session())
                        .cookie(authorA.xsrf())
                        .header("X-XSRF-TOKEN", authorA.xsrf().getValue()))
                .andExpect(status().isConflict());

        // Владелец удаляет черновик -> 204
        mockMvc.perform(delete("/api/v1/workshop/algorithms/{id}", draftId)
                        .session(authorA.session())
                        .cookie(authorA.xsrf())
                        .header("X-XSRF-TOKEN", authorA.xsrf().getValue()))
                .andExpect(status().isNoContent());

        // Повторное удаление -> 404
        mockMvc.perform(delete("/api/v1/workshop/algorithms/{id}", draftId)
                        .session(authorA.session())
                        .cookie(authorA.xsrf())
                        .header("X-XSRF-TOKEN", authorA.xsrf().getValue()))
                .andExpect(status().isNotFound());

        // Карточка, черновик, правка, публикация, смена видимости больше не доступны
        mockMvc.perform(get("/api/v1/workshop/algorithms/{id}", draftId).session(authorA.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/workshop/algorithms/{id}/draft", draftId).session(authorA.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/workshop/algorithms/{id}/draft", draftId)
                        .session(authorA.session())
                        .cookie(authorA.xsrf())
                        .header("X-XSRF-TOKEN", authorA.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Other\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/workshop/algorithms/{id}/publish", draftId)
                        .session(authorA.session())
                        .cookie(authorA.xsrf())
                        .header("X-XSRF-TOKEN", authorA.xsrf().getValue()))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/workshop/algorithms/{id}/visibility", draftId)
                        .session(authorA.session())
                        .cookie(authorA.xsrf())
                        .header("X-XSRF-TOKEN", authorA.xsrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"hidden\"}"))
                .andExpect(status().isNotFound());

        // Нет в каталоге и фильтре mineOnly
        mockMvc.perform(get("/api/v1/workshop/algorithms")
                        .session(authorA.session())
                        .param("mineOnly", "true")
                        .param("search", name))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        // Можно заново создать стратегию с тем же именем
        MvcResult recreated = createDraft(authorA, name, "Новый черновик", "CLEAN_DSL");
        assertThat(recreated.getResponse().getStatus()).isEqualTo(201);
        assertThat(json.readTree(recreated.getResponse().getContentAsString())
                        .get("strategyId")
                        .asString())
                .isNotEqualTo(draftId.toString());
    }

    @Test
    void softDeletePublishedPreservesImmutableVersionsForInv5ButBlocksNewLaunchesAndCatalog() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String name = "PublishedDelete-" + tag;
        UUID strategyId = UUID.fromString(json.readTree(createDraft(authorA, name, "Публичный", "CLEAN_DSL")
                        .getResponse()
                        .getContentAsString())
                .get("strategyId")
                .asString());
        UUID versionId = UUID.fromString(
                json.readTree(publish(authorA, strategyId, "v1").getResponse().getContentAsString())
                        .get("id")
                        .asString());

        // Автор виден в списке авторов до удаления
        mockMvc.perform(get("/api/v1/workshop/algorithms").session(authorB.session()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.authors[?(@.id=='" + authorA.id() + "')]").isNotEmpty());

        mockMvc.perform(delete("/api/v1/workshop/algorithms/{id}", strategyId)
                        .session(authorA.session())
                        .cookie(authorA.xsrf())
                        .header("X-XSRF-TOKEN", authorA.xsrf().getValue()))
                .andExpect(status().isNoContent());

        // Пропал из каталога и из списка авторов
        mockMvc.perform(get("/api/v1/workshop/algorithms")
                        .session(authorB.session())
                        .param("search", name))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(
                        jsonPath("$.authors[?(@.id=='" + authorA.id() + "')]").isEmpty());

        // Форк удалённой стратегии недоступен даже владельцу
        mockMvc.perform(post("/api/v1/workshop/algorithms/{id}/fork", strategyId)
                        .session(authorA.session())
                        .cookie(authorA.xsrf())
                        .header("X-XSRF-TOKEN", authorA.xsrf().getValue()))
                .andExpect(status().isNotFound());

        // Новые запуски запрещены, но неизменяемая версия (INV-5) остаётся доступной для отчётов и повторов
        assertThat(catalog.canAccess(versionId, authorA.id())).isFalse();
        assertThat(catalog.canAccess(versionId, authorB.id())).isFalse();
        assertThat(catalog.canAccess(versionId, null)).isFalse();
        assertThat(catalog.findLatestVersion(strategyId, authorA.id())).isEmpty();
        assertThat(catalog.findLatestVersion(strategyId, null)).isEmpty();
        assertThat(catalog.findVersion(versionId)).isPresent();
    }

    private record UserSession(UUID id, MockHttpSession session, Cookie xsrf) {}

    static final class StubWorker implements SimulationWorkerClient {

        void reset() {}

        @Override
        public String ping() {
            return "stub";
        }

        @Override
        public PeriodClassification classifyPeriods(
                String snapshotKey, int windowLength, LocalDate from, LocalDate to, LocalDate startDate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReplayResult replaySession(
                Market market, List<JournalRecord> journal, long upToStep, boolean includeHistory, boolean blind) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReplayResult replayWithReport(Market market, List<JournalRecord> journal, long upToStep) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int tradingDaysAt(Market market, long upToStep) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EstimateResult estimateOrder(
                Market market, List<JournalRecord> journal, long upToStep, OrderParams order) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StrategyCheck checkStrategy(String source, Map<String, Double> paramOverrides) {
            List<StrategyParamDecl> params = List.of(new StrategyParamDecl("fast", 20.0, 5.0, 50.0, "5", true));
            if (source.contains("ERROR_LA01")) {
                return new StrategyCheck(
                        CheckLevel.ERROR,
                        List.of(new StrategyDiagnostic(
                                "E-LA01",
                                CheckLevel.ERROR,
                                2,
                                8,
                                2,
                                17,
                                "Отрицательное смещение [-1] читает будущий бар",
                                List.of())),
                        params);
            }
            if (source.contains("WARN_L001")) {
                return new StrategyCheck(
                        CheckLevel.OK,
                        List.of(new StrategyDiagnostic(
                                "L001",
                                CheckLevel.WARNING,
                                4,
                                12,
                                4,
                                14,
                                "Магическая константа 70 — вынесите в параметр",
                                List.of(new DiagnosticFix(
                                        "Вынести в param rsi_level = 70",
                                        List.of(new TextEdit(1, 1, 1, 1, "param rsi_level = 70\n")))))),
                        params);
            }
            return new StrategyCheck(CheckLevel.OK, List.of(), params);
        }
    }

    @TestConfiguration
    static class TestWiring {

        @Bean
        @Primary
        StubWorker stubWorkshopWorker() {
            return new StubWorker();
        }
    }
}
