package ru.tinvest.trainer.marketdata.importer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Разбор ответов ISS (JSON-блоки {@code columns}/{@code data}) в типы импортёра. Чистые функции, без сети. */
final class IssParsers {

    /** Цены и суммы в ClickHouse — {@code Decimal(18, 6)}. */
    static final int SCALE = 6;

    /** МСК = UTC+3 без перехода на летнее время (с ноября 2014; нижняя граница архива). */
    static final ZoneOffset MSK = ZoneOffset.ofHours(3);

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private IssParsers() {}

    /** Блок {@code block} из ответа; нет блока или он не по формату — {@link ImportException} (вызывающий повторит). */
    static IssTable table(String body, String block) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (RuntimeException e) {
            throw new ImportException("Ответ ISS не JSON", e);
        }
        JsonNode node = root.get(block);
        if (node == null || !node.has("columns") || !node.has("data")) {
            throw new ImportException("В ответе ISS нет блока " + block);
        }
        List<String> columns = new ArrayList<>();
        node.get("columns").forEach(c -> columns.add(c.asString()));
        List<List<JsonNode>> rows = new ArrayList<>();
        node.get("data").forEach(r -> {
            List<JsonNode> row = new ArrayList<>();
            r.forEach(row::add);
            rows.add(row);
        });
        Long total = null;
        String prevDate = null;
        JsonNode cursor = root.get(block + ".cursor");
        if (cursor != null && cursor.has("columns") && cursor.get("data").size() > 0) {
            List<String> cc = new ArrayList<>();
            cursor.get("columns").forEach(c -> cc.add(c.asString()));
            JsonNode row = cursor.get("data").get(0);
            int t = cc.indexOf("TOTAL");
            if (t >= 0 && row.get(t).isNumber()) {
                total = row.get(t).asLong();
            }
            int p = cc.indexOf("PREV_DATE");
            if (p >= 0 && !row.get(p).isNull()) {
                prevDate = row.get(p).asString();
            }
        }
        return new IssTable(columns, rows, total, prevDate);
    }

    /** Свечи {@code candles}: {@code begin} — время МСК, переводится в UTC. */
    static List<Candle> candles(IssTable t) {
        List<Candle> out = new ArrayList<>(t.size());
        for (List<JsonNode> r : t.rows()) {
            LocalDateTime begin = LocalDateTime.parse(t.get(r, "begin").asString(), TIMESTAMP);
            out.add(new Candle(
                    begin.toInstant(MSK),
                    price(t.get(r, "open")),
                    price(t.get(r, "high")),
                    price(t.get(r, "low")),
                    price(t.get(r, "close")),
                    t.get(r, "volume").decimalValue().longValueExact(),
                    turnover(t.get(r, "value"))));
        }
        return out;
    }

    /**
     * Цена или шаг цены: масштаб 6 без округления (INV-6, {@code decisions.md}, «Деньги и округление», п. 12). Больше
     * шести знаков — ошибка: значение не молча искажается, а импорт останавливается.
     */
    static BigDecimal price(JsonNode n) {
        BigDecimal v = number(n);
        try {
            return v.setScale(SCALE);
        } catch (ArithmeticException e) {
            throw new ImportException("Цена " + v.toPlainString() + " имеет больше " + SCALE + " знаков", e);
        }
    }

    /**
     * Оборот свечи: ISS отдаёт его как double с артефактами ({@code 40851055.69999998}), значит, округление
     * неизбежно — HALF_UP до масштаба 6. На цены не распространяется.
     */
    static BigDecimal turnover(JsonNode n) {
        return number(n).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal number(JsonNode n) {
        if (n == null || !n.isNumber()) {
            throw new ImportException("Ожидалось число, пришло " + n);
        }
        return n.decimalValue();
    }

    /** Дата МСК, которой принадлежит свеча. */
    static LocalDate mskDate(Candle c) {
        return c.tsOpen().atOffset(MSK).toLocalDate();
    }
}
