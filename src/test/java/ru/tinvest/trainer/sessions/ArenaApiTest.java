package ru.tinvest.trainer.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
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
 * Арена через HTTP: настоящие PostgreSQL, Spring Security и миграции, воркер — {@link FakeWorker}. Проверяют снимок
 * (раскрытый и слепой), подачу, отмену и замену заявок, запись журнала, оценку, идемпотентность и доступ по владельцу.
 */
@AutoConfigureMockMvc
@Import(ArenaApiTest.TestWiring.class)
class ArenaApiTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse";
    private static final List<String> SECIDS = List.of("SBER", "GAZP", "LKOH");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern UUID_TEXT =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

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
    private ObjectMapper json;

    private UUID userId;
    private MockHttpSession httpSession;
    private String xsrf;

    @BeforeEach
    void setUp() throws Exception {
        worker.reset();
        userId = createUser("u" + UUID.randomUUID() + "@example.com");
        login();
    }

    // ---------- снимок ----------

    @Test
    void openSnapshotShowsPapersCandlesAndPortfolioWithoutMovingTheCursor() throws Exception {
        UUID id = newSession(false);
        setStep(id, 3);

        JsonNode body = body(httpGet(id, ""));

        assertThat(body.get("mode").asString()).isEqualTo("OPEN");
        assertThat(body.get("sessionId").asString()).isEqualTo(id.toString());
        assertThat(body.get("regime").asString()).isEqualTo("calm");
        assertThat(body.get("step").asInt()).isEqualTo(3);
        assertThat(body.get("totalSteps").asInt()).isEqualTo((int) FakeWorker.TOTAL_STEPS);
        assertThat(body.get("warmupSteps").asInt()).isEqualTo((int) FakeWorker.WARMUP);
        assertThat(body.get("finished").asBoolean()).isFalse();
        JsonNode papers = body.get("papers");
        assertThat(papers).hasSize(3);
        Set<String> tickers = new HashSet<>();
        for (JsonNode paper : papers) {
            tickers.add(paper.get("ticker").asString());
            assertThat(paper.get("id").asString())
                    .isEqualTo(Papers.id(paper.get("ticker").asString()).toString());
            // прогрев (2 шага) и шаги 0..3
            assertThat(paper.get("candles")).hasSize(6);
            assertThat(paper.get("candles").get(0).get("step").asInt()).isEqualTo(-2);
            assertThat(paper.get("candles").get(5).get("openedAt").asString()).endsWith("Z");
            assertThat(paper.get("stat").get("lastPrice").asString())
                    .isEqualTo(paper.get("candles").get(5).get("close").asString());
        }
        assertThat(tickers).containsExactlyInAnyOrderElementsOf(SECIDS);
        assertThat(body.get("portfolio").get("cash").asString()).isEqualTo("500000.00");
        assertThat(body.get("portfolio").get("mode").asString()).isEqualTo("OPEN");
        // чтение курсор не двигает и журнал не пишет
        assertThat(step(id)).isEqualTo(3);
        assertThat(journalRows(id)).isEmpty();
        // воркеру ушёл журнал целиком (пустой) и шаг курсора — состояние без отдельного хранилища
        assertThat(worker.replayJournals).hasSize(1);
    }

    private static final Set<String> BLIND_KEYS = Set.of(
            "sessionId",
            "regime",
            "step",
            "totalSteps",
            "warmupSteps",
            "day",
            "totalDays",
            "finished",
            "playing",
            "speed",
            "mode",
            "papers",
            "portfolio",
            "fills",
            "orders",
            "paperIndex",
            "label",
            "sector",
            "lotValueAtStart",
            "candles",
            "stat",
            "changePct",
            "lastPrice",
            "candleInDay",
            "open",
            "high",
            "low",
            "close",
            "volume",
            "afterHalt",
            "equityPct",
            "cashPct",
            "investedPct",
            "pnlPct",
            "costsPct",
            "positions",
            "lots",
            "avgPrice",
            "valuePct",
            "id",
            "orderId",
            "side",
            "type",
            "price",
            "amountPct",
            "costPct",
            "stopAndTakeSameBar",
            "filledLots",
            "tif",
            "status",
            "seenStep",
            "closedStep",
            "ocoSiblingId",
            "limitPrice",
            "stopPrice",
            "takeProfitPrice",
            "trailingPct",
            "trailingStopLevel",
            "reason",
            "rejectField",
            "accepted",
            "field",
            "orderIds",
            "order",
            "canPlace",
            "commissionPct",
            "exchangeFeePct",
            "requiredCashPct");

    @Test
    void blindResponsesCarryNoTickersNamesDatesOrRubles() throws Exception {
        UUID id = newSession(true);
        // заявки: лимитная и рыночная (исполнится на шаге 1), затем курсор уходит вперёд
        body(httpPost(id, "/orders", blindOrder(0, "buy", "limit", 2, "100.50"), null));
        body(httpPost(id, "/orders", blindOrder(0, "buy", "market", 1, null, 1), null));
        setStep(id, 4);

        List<JsonNode> responses = new ArrayList<>();
        JsonNode snapshot = body(httpGet(id, ""));
        responses.add(snapshot);
        responses.add(body(httpPost(id, "/orders", blindOrder(2, "sell", "limit", 1, "110.00"), null)));
        responses.add(body(httpPost(id, "/order-estimate", blindOrder(null, "buy", "limit", 1, "100.00"), null)));

        assertThat(snapshot.get("mode").asString()).isEqualTo("BLIND");
        assertThat(snapshot.get("fills")).hasSize(1);
        assertThat(snapshot.get("orders")).hasSize(2);
        for (JsonNode response : responses) {
            assertBlind(response);
        }
        // ни тикеров, ни рублёвой суммы капитала в сыром тексте
        String raw = httpGet(id, "").getResponse().getContentAsString();
        for (String secid : SECIDS) {
            assertThat(raw).doesNotContain(secid);
        }
    }

    private void assertBlind(JsonNode response) {
        Set<String> keys = new HashSet<>();
        collectKeys(response, keys);
        assertThat(keys).isSubsetOf(BLIND_KEYS);
        List<String> texts = new ArrayList<>();
        collectTexts(response, texts);
        for (String text : texts) {
            if (UUID_TEXT.matcher(text).matches()) {
                continue;
            }
            assertThat(text).doesNotContain("SBER", "GAZP", "LKOH", "500000", "Z");
            assertThat(DATE.matcher(text).find()).as("дата в «%s»", text).isFalse();
        }
    }

    private static void collectKeys(JsonNode node, Set<String> keys) {
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                keys.add(name);
                collectKeys(node.get(name), keys);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collectKeys(child, keys));
        }
    }

    private static void collectTexts(JsonNode node, List<String> texts) {
        if (node.isString()) {
            texts.add(node.asString());
        } else if (node.isObject()) {
            node.propertyNames().forEach(name -> collectTexts(node.get(name), texts));
        } else if (node.isArray()) {
            node.forEach(child -> collectTexts(child, texts));
        }
    }

    @Test
    void blindSnapshotUsesOnlyIndexesAndSectors() throws Exception {
        UUID id = newSession(true);
        JsonNode snapshot = body(httpGet(id, ""));

        assertThat(snapshot.get("papers")).hasSize(3);
        Set<Integer> indexes = new HashSet<>();
        for (JsonNode paper : snapshot.get("papers")) {
            indexes.add(paper.get("paperIndex").asInt());
            assertThat(paper.get("label").asString()).startsWith("Бумага ");
            assertThat(paper.get("sector").asString()).startsWith("Сектор");
            assertThat(paper.get("lotValueAtStart").asString()).isEqualTo("5.00");
            assertThat(paper.get("candles").get(0).get("volume").asString()).isEqualTo("100.00");
        }
        assertThat(indexes).containsExactlyInAnyOrder(0, 1, 2);
        assertThat(snapshot.get("portfolio").get("cashPct").asString()).isEqualTo("100.00");
    }

    // ---------- подача ----------

    @Test
    void openOrderIsJournaledWithRublePricesAndShownInTheSnapshot() throws Exception {
        UUID id = newSession(false);

        JsonNode result = body(httpPost(id, "/orders", openOrder("SBER", 0, "buy", "limit", 2, "190.00"), null));

        assertThat(result.get("accepted").asBoolean()).isTrue();
        assertThat(result.get("reason").isNull()).isTrue();
        assertThat(result.get("field").isNull()).isTrue();
        assertThat(result.get("orderIds")).hasSize(1);
        JsonNode order = result.get("order");
        assertThat(order.get("id").asString())
                .isEqualTo(result.get("orderIds").get(0).asString());
        assertThat(order.get("mode").asString()).isEqualTo("OPEN");
        assertThat(order.get("paperId").asString()).isEqualTo(Papers.id("SBER").toString());
        assertThat(order.get("status").asString()).isEqualTo("active");
        assertThat(order.get("limitPrice").asString()).isEqualTo("190.00");
        assertThat(order.get("stopPrice").isNull()).isTrue();
        assertThat(order.get("seenStep").asInt()).isZero();
        assertThat(result.get("portfolio").get("cash").asString()).isEqualTo("500000.00");

        List<Map<String, Object>> rows = journalRows(id);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("seq")).isEqualTo(0L);
        assertThat(rows.get(0).get("step")).isEqualTo(0L);
        assertThat(rows.get(0).get("seen_step")).isEqualTo(0L);
        assertThat(rows.get(0).get("command_type")).isEqualTo("place");
        assertThat(rows.get(0).get("accepted")).isEqualTo(true);
        JsonNode command = json.readTree(rows.get(0).get("command").toString());
        assertThat(command.get("order").get("limitPrice").asString()).isEqualTo("190.00");
        assertThat(command.get("order").get("paperIndex").asInt()).isEqualTo(index(id, "SBER"));

        JsonNode snapshot = body(httpGet(id, ""));
        assertThat(snapshot.get("orders")).hasSize(1);
        assertThat(snapshot.get("orders").get(0).get("id").asString())
                .isEqualTo(order.get("id").asString());
    }

    @Test
    void blindOrderIsJournaledFromResolvedOrderInRublesOnly() throws Exception {
        UUID id = newSession(true);
        int index = index(id, "GAZP");

        JsonNode result = body(httpPost(id, "/orders", blindOrder(0, "buy", "limit", 2, "100.50", index), null));

        assertThat(result.get("accepted").asBoolean()).isTrue();
        assertThat(result.get("order").get("mode").asString()).isEqualTo("BLIND");
        assertThat(result.get("order").get("paperIndex").asInt()).isEqualTo(index);
        // клиент видит цену экрана, а не рубли
        assertThat(result.get("order").get("limitPrice").asString()).isEqualTo("100.50");
        assertThat(result.get("order").has("paperId")).isFalse();

        // перевод — через EstimateOrder со слепой ценой; в журнал ушла рублёвая заявка
        assertThat(worker.estimated).hasSize(1);
        assertThat(worker.estimated.get(0).blindPrice()).isTrue();
        assertThat(worker.estimated.get(0).limitPrice()).isEqualByComparingTo("100.50");
        List<Map<String, Object>> rows = journalRows(id);
        assertThat(rows).hasSize(1);
        JsonNode command = json.readTree(rows.get(0).get("command").toString());
        assertThat(command.get("order").get("limitPrice").asString()).isEqualTo("201.00");
        assertThat(rows.get(0).get("command").toString())
                .doesNotContain("100.5")
                .doesNotContain("blind");
        assertThat(worker.replayJournals.get(worker.replayJournals.size() - 1))
                .allSatisfy(e -> assertThat(((ru.tinvest.trainer.worker.WorkerTypes.Command.Place) e.command())
                                .order()
                                .blindPrice())
                        .isFalse());
    }

    @Test
    void ocoCreatesTwoLegsLinkedBySibling() throws Exception {
        UUID id = newSession(false);

        JsonNode result = body(httpPost(id, "/orders", """
                {"seenStep":0,"paperId":"%s","side":"sell","type":"oco","lots":1,"tif":"gtc",\
                "stopPrice":"180.00","takeProfitPrice":"220.00"}""".formatted(Papers.id("SBER")), null));

        assertThat(result.get("accepted").asBoolean()).isTrue();
        assertThat(result.get("orderIds")).hasSize(2);
        JsonNode stop = result.get("orders").get(0);
        JsonNode take = result.get("orders").get(1);
        assertThat(result.get("order").get("id").asString())
                .isEqualTo(stop.get("id").asString());
        assertThat(stop.get("type").asString()).isEqualTo("stop");
        assertThat(stop.get("stopPrice").asString()).isEqualTo("180.00");
        assertThat(take.get("type").asString()).isEqualTo("take_profit");
        assertThat(take.get("takeProfitPrice").asString()).isEqualTo("220.00");
        assertThat(take.get("limitPrice").isNull()).isTrue();
        assertThat(stop.get("ocoSiblingId").asString()).isEqualTo(take.get("id").asString());
        assertThat(take.get("ocoSiblingId").asString()).isEqualTo(stop.get("id").asString());
        JsonNode command = json.readTree(journalRows(id).get(0).get("command").toString());
        assertThat(command.get("ocoWith").get("kind").asString()).isEqualTo("TAKE_PROFIT");
        assertThat(command.get("ocoWith").get("limitPrice").asString()).isEqualTo("220.00");
    }

    @Test
    void workerRejectionIsReturnedAndRecordedInTheJournal() throws Exception {
        UUID id = newSession(false);

        JsonNode result = body(httpPost(id, "/orders", openOrder("SBER", 0, "buy", "limit", 5000, "190.00"), null));

        assertThat(result.get("accepted").asBoolean()).isFalse();
        assertThat(result.get("reason").asString()).isEqualTo("INSUFFICIENT_CASH");
        assertThat(result.get("order").isNull()).isTrue();
        assertThat(result.get("orders")).isEmpty();
        assertThat(result.get("orderIds")).isEmpty();
        List<Map<String, Object>> rows = journalRows(id);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("accepted")).isEqualTo(false);
        assertThat(rows.get(0).get("reject_reason")).isEqualTo("INSUFFICIENT_CASH");
    }

    @Test
    void rejectionsBeforeTheWorkerAreNotJournaled() throws Exception {
        UUID id = newSession(false);
        setStep(id, 2);

        // seenStep больше курсора
        JsonNode future = body(httpPost(id, "/orders", openOrder("SBER", 3, "buy", "limit", 1, "190.00"), null));
        assertThat(future.get("accepted").asBoolean()).isFalse();
        assertThat(future.get("reason").asString()).isEqualTo("INVALID_PARAMS");
        assertThat(future.get("field").asString()).isEqualTo("seenStep");
        // нет обязательной цены типа
        JsonNode noPrice = body(httpPost(id, "/orders", openOrder("SBER", 2, "buy", "limit", 1, null), null));
        assertThat(noPrice.get("reason").asString()).isEqualTo("INVALID_PARAMS");
        assertThat(noPrice.get("field").asString()).isEqualTo("limitPrice");
        JsonNode noTake = body(httpPost(id, "/orders", """
                {"seenStep":2,"paperId":"%s","side":"sell","type":"oco","lots":1,"tif":"gtc","stopPrice":"180.00"}""".formatted(Papers.id("SBER")), null));
        assertThat(noTake.get("field").asString()).isEqualTo("takeProfitPrice");
        // бумаги нет в сессии
        JsonNode stranger = body(httpPost(id, "/orders", """
                {"seenStep":2,"paperId":"%s","side":"buy","type":"market","lots":1,"tif":"day"}""".formatted(UUID.randomUUID()), null));
        assertThat(stranger.get("field").asString()).isEqualTo("paperId");
        assertThat(journalRows(id)).isEmpty();
        assertThat(worker.estimated).isEmpty();
    }

    @Test
    void blindPriceThatCannotBeConvertedIsRejectedWithoutJournal() throws Exception {
        UUID id = newSession(true);

        JsonNode result = body(httpPost(id, "/orders", blindOrder(0, "buy", "limit", 1, "999"), null));

        assertThat(result.get("accepted").asBoolean()).isFalse();
        assertThat(result.get("reason").asString()).isEqualTo("INVALID_PARAMS");
        assertThat(result.get("field").asString()).isEqualTo("limitPrice");
        assertThat(journalRows(id)).isEmpty();
    }

    @Test
    void finishedSessionRejectsOrdersWithoutJournalAndEstimateSaysSo() throws Exception {
        UUID id = newSession(false);
        jdbc.sql("UPDATE sessions.session SET status = 'completed' WHERE id = :id")
                .param("id", id)
                .update();

        JsonNode result = body(httpPost(id, "/orders", openOrder("SBER", 0, "buy", "limit", 1, "190.00"), null));
        assertThat(result.get("accepted").asBoolean()).isFalse();
        assertThat(result.get("reason").asString()).isEqualTo("SESSION_NOT_ACTIVE");
        assertThat(journalRows(id)).isEmpty();

        JsonNode estimate =
                body(httpPost(id, "/order-estimate", openOrder("SBER", null, "buy", "limit", 1, "190.00"), null));
        assertThat(estimate.get("canPlace").asBoolean()).isFalse();
        assertThat(estimate.get("reason").asString()).isEqualTo("SESSION_NOT_ACTIVE");
        assertThat(body(httpGet(id, "")).get("finished").asBoolean()).isTrue();
    }

    // ---------- отмена и замена ----------

    @Test
    void cancelAndReplaceGoThroughTheJournal() throws Exception {
        UUID id = newSession(false);
        String first = body(httpPost(id, "/orders", openOrder("SBER", 0, "buy", "limit", 2, "190.00"), null))
                .get("orderIds")
                .get(0)
                .asString();

        JsonNode cancelled = body(httpDelete(id, "/orders/" + first + "?seenStep=0", null));
        assertThat(cancelled.get("accepted").asBoolean()).isTrue();
        assertThat(cancelled.get("orderIds")).isEmpty();
        assertThat(cancelled.get("order").get("id").asString()).isEqualTo(first);
        assertThat(cancelled.get("order").get("status").asString()).isEqualTo("cancelled");
        assertThat(cancelled.get("order").get("reason").asString()).isEqualTo("CANCELLED_BY_USER");

        // повторная отмена — отказ воркера, он тоже в журнале
        JsonNode again = body(httpDelete(id, "/orders/" + first + "?seenStep=0", null));
        assertThat(again.get("accepted").asBoolean()).isFalse();
        assertThat(again.get("reason").asString()).isEqualTo("ORDER_NOT_ACTIVE");

        String second = body(httpPost(id, "/orders", openOrder("GAZP", 0, "buy", "limit", 2, "150.00"), null))
                .get("orderIds")
                .get(0)
                .asString();
        JsonNode replaced = body(httpPut(
                id,
                "/orders/" + second,
                "{\"seenStep\":0,\"lots\":3,\"tif\":\"day\",\"limitPrice\":\"155.00\"}",
                null));
        assertThat(replaced.get("accepted").asBoolean()).isTrue();
        assertThat(replaced.get("orderIds")).hasSize(1);
        assertThat(replaced.get("orderIds").get(0).asString()).isNotEqualTo(second);
        assertThat(replaced.get("order").get("lots").asInt()).isEqualTo(3);
        assertThat(replaced.get("order").get("limitPrice").asString()).isEqualTo("155.00");
        assertThat(replaced.get("order").get("paperId").asString())
                .isEqualTo(Papers.id("GAZP").toString());

        List<Map<String, Object>> rows = journalRows(id);
        assertThat(rows)
                .extracting(r -> r.get("command_type"))
                .containsExactly("place", "cancel", "cancel", "place", "replace");
        assertThat(rows).extracting(r -> r.get("accepted")).containsExactly(true, true, false, true, true);
        assertThat(rows.get(2).get("reject_reason")).isEqualTo("ORDER_NOT_ACTIVE");
        JsonNode replaceCommand = json.readTree(rows.get(4).get("command").toString());
        assertThat(replaceCommand.get("replacement").get("order").get("lots").asInt())
                .isEqualTo(3);

        JsonNode snapshot = body(httpGet(id, ""));
        assertThat(snapshot.get("orders")).hasSize(3);
        Map<String, String> statuses = new java.util.HashMap<>();
        for (JsonNode order : snapshot.get("orders")) {
            statuses.put(
                    order.get("id").asString(),
                    order.get("status").asString() + "/" + order.get("reason").asString());
        }
        assertThat(statuses.get(second)).isEqualTo("cancelled/REPLACED");

        // неизвестная заявка — 404
        assertThat(httpDelete(id, "/orders/" + UUID.randomUUID() + "?seenStep=0", null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(httpPut(id, "/orders/" + UUID.randomUUID(), "{\"seenStep\":0,\"lots\":1,\"tif\":\"day\"}", null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
    }

    @Test
    void blindReplaceConvertsNewPricesToRubles() throws Exception {
        UUID id = newSession(true);
        String order = body(httpPost(id, "/orders", blindOrder(0, "buy", "limit", 2, "100.50"), null))
                .get("orderIds")
                .get(0)
                .asString();

        JsonNode replaced = body(httpPut(
                id, "/orders/" + order, "{\"seenStep\":0,\"lots\":4,\"tif\":\"gtc\",\"limitPrice\":\"101.25\"}", null));

        assertThat(replaced.get("accepted").asBoolean()).isTrue();
        assertThat(replaced.get("order").get("limitPrice").asString()).isEqualTo("101.25");
        List<Map<String, Object>> rows = journalRows(id);
        JsonNode command = json.readTree(rows.get(1).get("command").toString());
        assertThat(command.get("replacement").get("order").get("limitPrice").asString())
                .isEqualTo("202.50");
        assertBlind(replaced);
    }

    // ---------- оценка ----------

    @Test
    void estimateNeverWritesTheJournal() throws Exception {
        UUID open = newSession(false);
        UUID blind = newSession(true);

        JsonNode openEstimate =
                body(httpPost(open, "/order-estimate", openOrder("SBER", null, "buy", "limit", 1, "190.00"), null));
        JsonNode blindEstimate =
                body(httpPost(blind, "/order-estimate", blindOrder(null, "buy", "limit", 1, "100.00"), null));
        JsonNode tooMuch =
                body(httpPost(open, "/order-estimate", openOrder("SBER", null, "buy", "limit", 5000, "190.00"), null));

        assertThat(openEstimate.get("mode").asString()).isEqualTo("OPEN");
        assertThat(openEstimate.get("canPlace").asBoolean()).isTrue();
        assertThat(openEstimate.get("amount").asString()).isEqualTo("2000.00");
        assertThat(openEstimate.get("referencePrice").asString()).isEqualTo("200.00");
        assertThat(openEstimate.get("amountPct").asString()).isEqualTo("0.40");
        assertThat(blindEstimate.get("mode").asString()).isEqualTo("BLIND");
        assertThat(blindEstimate.propertyNames())
                .containsExactlyInAnyOrder(
                        "mode",
                        "canPlace",
                        "reason",
                        "field",
                        "amountPct",
                        "commissionPct",
                        "exchangeFeePct",
                        "requiredCashPct");
        assertThat(tooMuch.get("canPlace").asBoolean()).isFalse();
        assertThat(tooMuch.get("reason").asString()).isEqualTo("INSUFFICIENT_CASH");
        assertThat(journalRows(open)).isEmpty();
        assertThat(journalRows(blind)).isEmpty();
        assertThat(orderRefCount(open)).isZero();
    }

    private long orderRefCount(UUID id) {
        return jdbc.sql("SELECT count(*) FROM sessions.order_ref WHERE session_id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    // ---------- идемпотентность ----------

    @Test
    void sameKeyAndBodyReturnsThePreviousResultWithOneJournalEntry() throws Exception {
        UUID id = newSession(false);
        UUID key = UUID.randomUUID();
        String request = openOrder("SBER", 0, "buy", "limit", 2, "190.00");

        MvcResult first = httpPost(id, "/orders", request, key);
        int replaysAfterFirst = worker.replayJournals.size();
        MvcResult second = httpPost(id, "/orders", request, key);

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(second.getResponse().getContentAsString()))
                .isEqualTo(json.readTree(first.getResponse().getContentAsString()));
        assertThat(journalRows(id)).hasSize(1);
        assertThat(worker.replayJournals).hasSize(replaysAfterFirst);
        // тот же ключ в другой сессии — отдельная область
        UUID other = newSession(false);
        assertThat(httpPost(other, "/orders", openOrder("SBER", 0, "buy", "limit", 2, "190.00"), key)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(journalRows(other)).hasSize(1);
    }

    @Test
    void sameKeyWithAnotherBodyOrOperationIs422() throws Exception {
        UUID id = newSession(false);
        UUID key = UUID.randomUUID();
        String created = body(httpPost(id, "/orders", openOrder("SBER", 0, "buy", "limit", 2, "190.00"), key))
                .get("orderIds")
                .get(0)
                .asString();

        MvcResult changed = httpPost(id, "/orders", openOrder("SBER", 0, "buy", "limit", 3, "190.00"), key);
        MvcResult otherOperation = httpDelete(id, "/orders/" + created + "?seenStep=0", key);

        for (MvcResult result : List.of(changed, otherOperation)) {
            assertThat(result.getResponse().getStatus()).isEqualTo(422);
            assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
            JsonNode problem = json.readTree(result.getResponse().getContentAsString());
            assertThat(problem.get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
            assertThat(problem.get("status").asInt()).isEqualTo(422);
        }
        assertThat(journalRows(id)).hasSize(1);
    }

    @Test
    void requestWhileTheFirstOneIsInProgressIs409AndStaleKeyIsTakenOver() throws Exception {
        UUID id = newSession(false);
        UUID key = UUID.randomUUID();
        String request = openOrder("SBER", 0, "buy", "limit", 2, "190.00");
        OrderRequest parsed = json.readValue(request, OrderRequest.class);
        jdbc.sql("INSERT INTO sessions.idempotency_key (session_id, key, operation, request_hash, status) "
                        + "VALUES (:s, :k, 'place', :h, 'in_progress')")
                .param("s", id)
                .param("k", key)
                .param("h", parsed.fingerprint("place"))
                .update();

        MvcResult busy = httpPost(id, "/orders", request, key);

        assertThat(busy.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(busy.getResponse().getContentAsString())
                        .get("code")
                        .asString())
                .isEqualTo("IDEMPOTENCY_KEY_IN_PROGRESS");
        assertThat(journalRows(id)).isEmpty();

        // первый запрос завис давно: журнал не записан (запись и ответ — одна транзакция), ключ занимается заново
        jdbc.sql("UPDATE sessions.idempotency_key SET created_at = :t WHERE session_id = :s AND key = :k")
                .param("t", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5))
                .param("s", id)
                .param("k", key)
                .update();
        assertThat(httpPost(id, "/orders", request, key).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(journalRows(id)).hasSize(1);
    }

    @Test
    void failedCommandReleasesTheKeySoTheRetryRunsAgain() throws Exception {
        UUID id = newSession(false);
        UUID key = UUID.randomUUID();
        String request = openOrder("SBER", 0, "buy", "limit", 2, "190.00");
        worker.down = true;

        assertThat(httpPost(id, "/orders", request, key).getResponse().getStatus())
                .isEqualTo(503);
        assertThat(journalRows(id)).isEmpty();
        worker.down = false;

        assertThat(httpPost(id, "/orders", request, key).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(journalRows(id)).hasSize(1);
    }

    // ---------- доступ, ошибки, восстановление ----------

    @Test
    void foreignSessionIs404ForEveryOperationAndLeavesNoTrace() throws Exception {
        UUID id = newSession(false);
        UUID orderId =
                UUID.fromString(body(httpPost(id, "/orders", openOrder("SBER", 0, "buy", "limit", 2, "190.00"), null))
                        .get("orderIds")
                        .get(0)
                        .asString());
        String strangerEmail = "s" + UUID.randomUUID() + "@example.com";
        createUser(strangerEmail);
        login(strangerEmail);
        UUID key = UUID.randomUUID();
        String request = openOrder("SBER", 0, "buy", "limit", 1, "190.00");

        assertThat(httpGet(id, "").getResponse().getStatus()).isEqualTo(404);
        assertThat(httpPost(id, "/orders", request, key).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(httpPost(id, "/order-estimate", request, null).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(httpDelete(id, "/orders/" + orderId + "?seenStep=0", key)
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(httpPut(id, "/orders/" + orderId, "{\"seenStep\":0,\"lots\":1,\"tif\":\"day\"}", key)
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(httpGet(UUID.randomUUID(), "").getResponse().getStatus()).isEqualTo(404);
        assertThat(journalRows(id)).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM sessions.idempotency_key WHERE session_id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void stateIsRestoredFromTheJournalAlone() throws Exception {
        UUID id = newSession(false);
        body(httpPost(id, "/orders", openOrder("SBER", 0, "buy", "market", 1, null), null));
        body(httpPost(id, "/orders", openOrder("GAZP", 0, "buy", "limit", 2, "150.00"), null));
        setStep(id, 3);
        String before = httpGet(id, "").getResponse().getContentAsString();
        assertThat(json.readTree(before).get("fills")).hasSize(1);

        // «перезапуск»: у воркера и у backend нет ничего, кроме журнала и курсора в БД
        worker.reset();
        String after = httpGet(id, "").getResponse().getContentAsString();

        assertThat(json.readTree(after)).isEqualTo(json.readTree(before));
        assertThat(worker.replayJournals).hasSize(1);
        assertThat(worker.replayJournals.get(0)).hasSize(2);
        assertThat(journalRows(id)).hasSize(2);
    }

    @Test
    void unavailableWorkerIs503AndLeavesTheJournalUntouched() throws Exception {
        UUID id = newSession(false);
        worker.down = true;

        assertThat(httpGet(id, "").getResponse().getStatus()).isEqualTo(503);
        assertThat(httpPost(id, "/orders", openOrder("SBER", 0, "buy", "limit", 1, "190.00"), null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(503);
        assertThat(httpPost(id, "/order-estimate", openOrder("SBER", null, "buy", "limit", 1, "190.00"), null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(503);
        assertThat(journalRows(id)).isEmpty();
    }

    @Test
    void malformedRequestsAre400() throws Exception {
        UUID open = newSession(false);
        UUID blind = newSession(true);
        String paperId = Papers.id("SBER").toString();

        assertThat(httpPost(open, "/orders", openOrder("SBER", 0, "hold", "limit", 1, "190.00"), null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(httpPost(open, "/orders", openOrder("SBER", 0, "buy", "iceberg", 1, "190.00"), null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(httpPost(open, "/orders", openOrder("SBER", 0, "buy", "limit", 0, "190.00"), null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(httpPost(open, "/orders", openOrder("SBER", 0, "buy", "limit", 1, "1e3"), null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        // нет seenStep
        assertThat(httpPost(
                                open,
                                "/orders",
                                "{\"paperId\":\"%s\",\"side\":\"buy\",\"type\":\"market\",\"lots\":1,\"tif\":\"day\"}"
                                        .formatted(paperId),
                                null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        // paperId в слепой сессии, paperIndex в раскрытой
        assertThat(httpPost(
                                blind,
                                "/orders",
                                "{\"seenStep\":0,\"paperId\":\"%s\",\"side\":\"buy\",\"type\":\"market\",\"lots\":1,\"tif\":\"day\"}"
                                        .formatted(paperId),
                                null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(httpPost(
                                open,
                                "/orders",
                                "{\"seenStep\":0,\"paperIndex\":0,\"side\":\"buy\",\"type\":\"market\",\"lots\":1,\"tif\":\"day\"}",
                                null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(httpPost(open, "/orders", "{", null).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(httpDelete(open, "/orders/" + UUID.randomUUID(), null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        // ключ не UUID
        MvcResult badKey = mockMvc.perform(withAuth(post("/api/v1/sessions/" + open + "/orders"))
                        .header("Idempotency-Key", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(openOrder("SBER", 0, "buy", "limit", 1, "190.00")))
                .andReturn();
        assertThat(badKey.getResponse().getStatus()).isEqualTo(400);
        assertThat(journalRows(open)).isEmpty();
    }

    @Test
    void cursorMovesForwardOnlyAndTheSnapshotFollowsIt() throws Exception {
        UUID id = newSession(false);
        setStep(id, 3);
        assertThat(body(httpGet(id, "")).get("step").asInt()).isEqualTo(3);
        assertThatThrownBy(() -> setStep(id, 2)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(step(id)).isEqualTo(3);
        // остальной план по-прежнему неизменяем
        assertThatThrownBy(() -> jdbc.sql("UPDATE sessions.session SET bars_count = 1 WHERE id = :id")
                        .param("id", id)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        // журнал и ключи заявок — только дописываются
        newOrderRow(id);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM sessions.order_ref WHERE session_id = :id")
                        .param("id", id)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void newOrderRow(UUID id) {
        jdbc.sql("INSERT INTO sessions.order_ref (session_id, core_order_id, id) VALUES (:s, 0, :id)")
                .param("s", id)
                .param("id", UUID.randomUUID())
                .update();
    }

    // ---------- вспомогательное ----------

    private UUID createUser(String address) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO identity.user_account (id, email, password_hash) VALUES (:id, :e, :h)")
                .param("id", id)
                .param("e", address)
                .param("h", encoder.encode(PASSWORD))
                .update();
        return id;
    }

    private void login() throws Exception {
        login(jdbc.sql("SELECT email FROM identity.user_account WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .single());
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

    private MvcResult httpGet(UUID id, String suffix) throws Exception {
        return mockMvc.perform(withAuth(get("/api/v1/sessions/" + id + suffix))).andReturn();
    }

    private MvcResult httpPost(UUID id, String suffix, String body, UUID key) throws Exception {
        MockHttpServletRequestBuilder builder = withAuth(post("/api/v1/sessions/" + id + suffix))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return mockMvc.perform(key == null ? builder : builder.header("Idempotency-Key", key.toString()))
                .andReturn();
    }

    private MvcResult httpPut(UUID id, String suffix, String body, UUID key) throws Exception {
        MockHttpServletRequestBuilder builder = withAuth(put("/api/v1/sessions/" + id + suffix))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return mockMvc.perform(key == null ? builder : builder.header("Idempotency-Key", key.toString()))
                .andReturn();
    }

    private MvcResult httpDelete(UUID id, String suffix, UUID key) throws Exception {
        MockHttpServletRequestBuilder builder = withAuth(delete("/api/v1/sessions/" + id + suffix));
        return mockMvc.perform(key == null ? builder : builder.header("Idempotency-Key", key.toString()))
                .andReturn();
    }

    /** Тело ответа 200 как JSON. */
    private JsonNode body(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String openOrder(String secid, Integer seenStep, String side, String type, int lots, String limit) {
        return order("\"paperId\":\"" + Papers.id(secid) + "\"", seenStep, side, type, lots, limit);
    }

    private String blindOrder(Integer seenStep, String side, String type, int lots, String limit) {
        return blindOrder(seenStep, side, type, lots, limit, 0);
    }

    private String blindOrder(Integer seenStep, String side, String type, int lots, String limit, int index) {
        return order("\"paperIndex\":" + index, seenStep, side, type, lots, limit);
    }

    private static String order(String paper, Integer seenStep, String side, String type, int lots, String limit) {
        String seen = seenStep == null ? "" : "\"seenStep\":" + seenStep + ",";
        String price = limit == null ? "" : ",\"limitPrice\":\"" + limit + "\"";
        return "{" + seen + paper + ",\"side\":\"" + side + "\",\"type\":\"" + type + "\",\"lots\":" + lots
                + ",\"tif\":\"gtc\"" + price + "}";
    }

    private int index(UUID id, String secid) {
        return jdbc.sql("SELECT paper_index FROM sessions.session_paper WHERE session_id = :id AND secid = :s")
                .param("id", id)
                .param("s", secid)
                .query(Integer.class)
                .single();
    }

    private List<Map<String, Object>> journalRows(UUID id) {
        return jdbc.sql("SELECT * FROM sessions.command_journal WHERE session_id = :id ORDER BY seq")
                .param("id", id)
                .query()
                .listOfRows();
    }

    private void setStep(UUID id, long step) {
        jdbc.sql("UPDATE sessions.session SET current_step = :s WHERE id = :id")
                .param("s", step)
                .param("id", id)
                .update();
    }

    private long step(UUID id) {
        return jdbc.sql("SELECT current_step FROM sessions.session WHERE id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private UUID newSession(boolean blind) {
        List<PlannedPaper> list = new ArrayList<>();
        for (int i = 0; i < SECIDS.size(); i++) {
            list.add(new PlannedPaper(
                    Papers.id(SECIDS.get(i)), SECIDS.get(i), "Сектор" + i, 10 + i, new BigDecimal("0.01")));
        }
        Snapshot snapshot =
                new Snapshot(UUID.randomUUID(), UUID.randomUUID().toString(), OffsetDateTime.now(ZoneOffset.UTC), "t");
        LaunchPlan plan = new LaunchPlan(
                snapshot,
                blind,
                LocalDate.of(2022, 3, 14),
                LocalDate.of(2022, 4, 8),
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

    @TestConfiguration
    static class TestWiring {

        @Bean
        @Primary
        FakeWorker fakeWorker() {
            return new FakeWorker();
        }
    }
}
