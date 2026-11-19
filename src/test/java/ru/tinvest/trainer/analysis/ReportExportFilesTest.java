package ru.tinvest.trainer.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.junit.jupiter.api.Test;
import ru.tinvest.trainer.sessions.CommandLogEntry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Формат выгрузки отчёта (ФТ-17): листы, заголовки, числа строками, МСК, CSV в ZIP и открытый XLSX. */
class ReportExportFilesTest {

    private static final String SNAPSHOT = "11111111-2222-3333-4444-555555555555";

    private static final String REPORT = """
            {
              "sessionId": "aaaaaaaa-0000-0000-0000-000000000001",
              "blind": true, "finishedEarly": false, "replayAvailable": true,
              "playedDays": 3, "totalDays": 20, "timeframe": "1d", "regime": "calm",
              "createdAt": "2022-03-14T21:30:05Z",
              "backgroundAlgorithmName": null,
              "period": {"startDate": "2022-03-14", "endDate": "2022-04-09"},
              "papers": [{"id": "p1", "blindLabel": "Бумага A", "ticker": "SBER", "name": "SBER",
                          "sector": "Фин;ансы \\"X\\"", "startPrice": "270.123456789", "lotSize": 10}],
              "humanMetrics": {
                "initialCapital": "500000.00", "finalCapital": "123456789012.34",
                "totalReturnPct": "1.23", "cagrPct": null, "grossReturnPct": "1.25", "netReturnPct": "1.23",
                "maxDrawdownPct": "0.40", "maxDrawdownDurationDays": 4, "maxDrawdownDurationSteps": 3,
                "volatilityPct": null, "sharpe": "1.5", "sortino": null, "turnover": "0.2",
                "tradesCount": 1, "winRatePct": "100.00", "rejectedOrdersCount": 0, "partialFillsCount": 1,
                "stopTakeSameBarCount": 0, "hhi": "0.333333", "largestPositionPct": "33.333333", "bankrupt": false,
                "costs": {"total": "13.55", "commission": "10.50", "exchangeFee": "1.00", "spread": "0.00", "slippage": "-2.05"},
                "benchmarkImoexPct": null
              },
              "algoMetrics": null, "comparison": null,
              "equityCurve": [
                {"step": 0, "date": "2022-03-14T07:00:00Z", "humanCapital": "500000.00", "algoCapital": null, "benchmarkCapital": "500000.00"},
                {"step": 1, "date": "2022-03-14T21:30:00Z", "humanCapital": "123456789012.34", "algoCapital": null, "benchmarkCapital": null}
              ],
              "underwaterCurve": null, "divergenceSummary": null, "divergences": null, "reverseCheckTrades": null,
              "roundTrips": [
                {"paperTicker": "SBER", "entryStep": 0, "exitStep": 1, "entryDate": "2022-03-14T07:00:00Z",
                 "exitDate": "2022-03-15T15:59:59Z", "lots": 2, "entryPrice": "270.1", "exitPrice": "272.123456789",
                 "grossPnl": "40.00", "costs": "5.00", "netPnl": "35.00"}
              ],
              "fills": [
                {"id": "f1", "day": 1, "date": "2022-03-14T07:00:00Z", "paperTicker": "SBER", "side": "buy",
                 "orderType": "market", "lots": 2, "price": "270.1", "cost": null, "partial": true}
              ]
            }
            """;

    private static final List<CommandLogEntry> COMMANDS = List.of(
            new CommandLogEntry(
                    0,
                    0,
                    "place",
                    null,
                    "SBER",
                    "BUY",
                    "LIMIT",
                    2L,
                    new BigDecimal("270.10"),
                    null,
                    null,
                    "GTC",
                    false,
                    true,
                    null),
            new CommandLogEntry(
                    1,
                    0,
                    "cancel",
                    0L,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    "ORDER_NOT_ACTIVE"));

    private static List<String> texts(List<ReportSheets.Cell> row) {
        return row.stream().map(ReportSheets.Cell::text).toList();
    }

    private final JsonMapper json = JsonMapper.builder().build();

    private List<ReportSheets.Sheet> sheets() {
        return ReportSheets.of(json.readTree(REPORT), SNAPSHOT, COMMANDS);
    }

    @Test
    void sheetsFollowTheDecisionAndSkipAbsentComparison() {
        assertThat(sheets().stream().map(ReportSheets.Sheet::name))
                .containsExactly(
                        "Сводка", "Метрики", "Сделки", "Заявки", "Издержки", "Кривая капитала", "Параметры и версии");
    }

    @Test
    void comparisonAndDivergencesAppearWhenPresent() {
        JsonNode report = json.readTree(REPORT.replace(
                        "\"comparison\": null",
                        "\"comparison\": {\"finalCapital\": {\"human\": \"1.00\","
                                + " \"algo\": \"2.00\", \"winner\": \"algo\", \"diff\": \"-1.00\"},"
                                + " \"netReturn\": {\"humanPct\": \"1.5\", \"algoPct\": \"2\", \"winner\": \"algo\","
                                + " \"diffPctPoints\": \"-0.5\"}, \"maxDrawdown\": {\"humanPct\": \"1\","
                                + " \"algoPct\": \"1\", \"winner\": \"tie\", \"diffPctPoints\": \"0\"}}")
                .replace(
                        "\"divergences\": null",
                        "\"divergences\": [{\"day\": 2, \"date\": \"2022-03-15T07:00:00Z\","
                                + " \"paperTicker\": \"SBER\", \"sector\": \"S\", \"type\": \"extra\","
                                + " \"humanAction\": {\"kind\": \"trade\", \"side\": \"buy\", \"lots\": 1,"
                                + " \"orderType\": \"market\"}, \"algoAction\": {\"kind\": \"none\"},"
                                + " \"errorCost\": \"-5.00\", \"errorCostPct\": \"0.1\", \"because\": null}]"));

        List<ReportSheets.Sheet> sheets = ReportSheets.of(report, SNAPSHOT, COMMANDS);

        assertThat(sheets.stream().map(ReportSheets.Sheet::name)).endsWith("Сравнение", "Расхождения");
        assertThat(texts(sheets.get(7).rows().get(1)))
                .containsExactly("Доходность, %", "1,500000", "2,000000", "алгоритм", "-0,500000");
        assertThat(texts(sheets.get(8).rows().get(0)))
                .containsExactly(
                        "2",
                        "15.03.2022",
                        "10:00:00",
                        "SBER",
                        "S",
                        "лишнее действие",
                        "покупка 1 рыночная",
                        "нет действия",
                        "-5,00",
                        "0,100000",
                        "");
    }

    @Test
    void moneyIsExactAndDatesAreMoscowTimeWithDecimalComma() {
        ReportSheets.Sheet equity = sheets().get(5);
        assertThat(equity.header())
                .containsExactly("Шаг", "Дата", "Время", "Капитал человека", "Капитал алгоритма", "Капитал бенчмарка");
        // 07:00Z = 10:00 МСК; 21:30Z — уже следующие сутки по Москве
        assertThat(texts(equity.rows().get(0)))
                .containsExactly("0", "14.03.2022", "10:00:00", "500000,00", "", "500000,00");
        assertThat(texts(equity.rows().get(1)))
                .containsExactly("1", "15.03.2022", "00:30:00", "123456789012,34", "", "");
        ReportSheets.Sheet trades = sheets().get(2);
        assertThat(texts(trades.rows().get(0)))
                .containsExactly(
                        "SBER",
                        "14.03.2022",
                        "10:00:00",
                        "15.03.2022",
                        "18:59:59",
                        "2",
                        "270,1",
                        "272,123456789",
                        "40,00",
                        "5,00",
                        "35,00");
        ReportSheets.Sheet costs = sheets().get(4);
        assertThat(costs.rows().stream().map(ReportExportFilesTest::texts).toList())
                .containsExactly(
                        List.of("Комиссия", "10,50"),
                        List.of("Биржевой сбор", "1,00"),
                        List.of("Спред", "0,00"),
                        List.of("Проскальзывание", "-2,05"),
                        List.of("Итого", "13,55"));
    }

    @Test
    void metricsAreSixDigitsAndMissingValuesAreEmpty() {
        Map<String, String> metrics = new LinkedHashMap<>();
        sheets().get(1)
                .rows()
                .forEach(row -> metrics.put(row.get(0).text(), row.get(1).text()));
        assertThat(metrics)
                .containsEntry("Итоговый капитал", "123456789012,34")
                .containsEntry("Полная доходность, %", "1,230000")
                .containsEntry("Максимальная просадка, %", "0,400000")
                .containsEntry("Sharpe", "1,500000")
                .containsEntry("HHI", "0,333333")
                .containsEntry("Длительность просадки, дней", "4")
                .containsEntry("Sortino", "")
                .containsEntry("CAGR, %", "")
                .containsEntry("Закрытых сделок", "1");
    }

    @Test
    void csvZipHasOneBomFilePerSheetWithSemicolonsAndRfc4180Quoting() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zip =
                new ZipInputStream(new ByteArrayInputStream(ReportFiles.csvZip(sheets())), StandardCharsets.UTF_8)) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                files.put(e.getName(), zip.readAllBytes());
            }
        }

        assertThat(files.keySet())
                .containsExactly(
                        "Сводка.csv",
                        "Метрики.csv",
                        "Сделки.csv",
                        "Заявки.csv",
                        "Издержки.csv",
                        "Кривая капитала.csv",
                        "Параметры и версии.csv");
        byte[] equity = files.get("Кривая капитала.csv");
        assertThat(equity[0]).isEqualTo((byte) 0xEF);
        assertThat(equity[1]).isEqualTo((byte) 0xBB);
        assertThat(equity[2]).isEqualTo((byte) 0xBF);
        assertThat(new String(equity, StandardCharsets.UTF_8))
                .isEqualTo("﻿Шаг;Дата;Время;Капитал человека;Капитал алгоритма;Капитал бенчмарка\r\n"
                        + "0;14.03.2022;10:00:00;500000,00;;500000,00\r\n"
                        + "1;15.03.2022;00:30:00;123456789012,34;;\r\n");
        // разделитель и кавычки внутри ячейки: ячейка в кавычках, кавычка удваивается
        assertThat(new String(files.get("Параметры и версии.csv"), StandardCharsets.UTF_8))
                .contains("Версия данных (снапшот);" + SNAPSHOT + "\r\n")
                .contains("Бумага A;\"SBER; сектор: Фин;ансы \"\"X\"\"; лот: 10; цена на старте: 270,123456789\"");
    }

    @Test
    void xlsxOpensWithTheSameSheetsHeadersAndExactCellText() throws IOException {
        byte[] file = ReportFiles.xlsx(sheets());

        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(file))) {
            List<String> names = workbook.getSheets().map(Sheet::getName).toList();
            assertThat(names)
                    .containsExactly(
                            "Сводка",
                            "Метрики",
                            "Сделки",
                            "Заявки",
                            "Издержки",
                            "Кривая капитала",
                            "Параметры и версии");
            Sheet equity = workbook.findSheet("Кривая капитала").orElseThrow();
            List<Row> rows;
            try (var stream = equity.openStream()) {
                rows = stream.toList();
            }
            assertThat(rows).hasSize(3);
            assertThat(cells(rows.get(0), 6))
                    .containsExactly(
                            "Шаг", "Дата", "Время", "Капитал человека", "Капитал алгоритма", "Капитал бенчмарка");
            Sheet orders = workbook.findSheet("Заявки").orElseThrow();
            try (var stream = orders.openStream()) {
                List<Row> orderRows = stream.toList();
                assertThat(orderRows).hasSize(3);
                assertThat(cells(orderRows.get(1), 14))
                        .containsExactly(
                                "0",
                                "14.03.2022",
                                "10:00:00",
                                "подача",
                                "",
                                "SBER",
                                "покупка",
                                "лимитная",
                                "2",
                                "270.10",
                                "",
                                "",
                                "до отмены",
                                "принята");
            }
            assertThat(cells(rows.get(2), 6)).containsExactly("1", "15.03.2022", "00:30:00", "123456789012.34", "", "");
            Sheet summary = workbook.findSheet("Сводка").orElseThrow();
            try (var stream = summary.openStream()) {
                Map<String, String> values = new LinkedHashMap<>();
                stream.skip(1).forEach(r -> values.put(r.getCellText(0), r.getCellText(1)));
                assertThat(values)
                        .containsEntry("Создана", "15.03.2022 00:30:05")
                        .containsEntry("Период с", "14.03.2022")
                        .containsEntry("Слепая сессия", "Да");
            }
        }
    }

    @Test
    void textStartingWithFormulaCharactersIsPrefixedButNumbersAreNot() throws IOException {
        ReportSheets.Sheet sheet = new ReportSheets.Sheet(
                "Лист",
                List.of("A", "B"),
                List.of(
                        List.of(ReportSheets.Cell.of("=1+1"), ReportSheets.Cell.of("обычный")),
                        List.of(ReportSheets.Cell.of("+7"), ReportSheets.Cell.of("@x")),
                        List.of(ReportSheets.Cell.of("-1"), ReportSheets.Cell.of("\tтаб")),
                        List.of(
                                new ReportSheets.Cell("-2,05", new BigDecimal("-2.05"), "0.00"),
                                ReportSheets.Cell.of("\rcr"))));

        String csv = ReportFiles.csv(sheet);

        assertThat(csv)
                .contains("'=1+1;обычный\r\n")
                .contains("'+7;'@x\r\n")
                .contains("'-1;'\tтаб\r\n")
                .contains("-2,05;\"'\rcr\"\r\n");
        try (ReadableWorkbook workbook =
                new ReadableWorkbook(new ByteArrayInputStream(ReportFiles.xlsx(List.of(sheet))))) {
            try (var rows = workbook.getFirstSheet().openStream()) {
                List<Row> list = rows.toList();
                assertThat(list.get(1).getCellText(0)).isEqualTo("'=1+1");
                assertThat(list.get(2).getCellText(1)).isEqualTo("'@x");
            }
        }
    }

    @Test
    void xlsxMoneyAndMetricsAreNumericCellsWithExactDecimalsAndFormats() throws IOException {
        byte[] file = ReportFiles.xlsx(sheets());
        Map<String, String> xml = new LinkedHashMap<>();
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("report", ".xlsx");
        try {
            java.nio.file.Files.write(tmp, file);
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(tmp.toFile())) {
                for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                    ZipEntry e = entries.nextElement();
                    xml.put(e.getName(), new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } finally {
            java.nio.file.Files.delete(tmp);
        }
        String equity = xml.get("xl/worksheets/sheet6.xml");
        // деньги записаны числом из BigDecimal без потерь double: 123456789012.34 не стало 1.2345678901234E11
        assertThat(equity)
                .contains("<v>123456789012.34</v>")
                .contains("<v>500000.00</v>")
                .doesNotContain("E11");
        assertThat(xml.get("xl/styles.xml")).contains("#,##0.00").contains("0.000000");
        String metrics = xml.get("xl/worksheets/sheet2.xml");
        assertThat(metrics).contains("<v>1.500000</v>").contains("<v>0.333333</v>");
    }

    private static List<String> cells(Row row, int count) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(row.getCellText(i));
        }
        return out;
    }
}
