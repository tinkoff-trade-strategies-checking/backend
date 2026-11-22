package ru.tinvest.trainer.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Sheet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Выгрузка отчёта прогона алгоритма (ФТ-17): листы, правила DSL и диагностика. */
class AlgoRunExportFilesTest {

    private static final String SNAPSHOT = "11111111-2222-3333-4444-555555555555";

    private static final String ALGO_REPORT = """
            {
              "runId": "bbbbbbbb-0000-0000-0000-000000000001",
              "strategyVersionId": "10000000-0000-4000-8000-000000000002",
              "algoName": "Эталон платформы",
              "blind": false,
              "status": "completed",
              "playedDays": 5,
              "totalDays": 20,
              "timeframe": "1d",
              "regime": "medium",
              "createdAt": "2024-03-14T10:00:00Z",
              "repeatOfRunId": "bbbbbbbb-0000-0000-0000-000000000000",
              "repeatMatch": true,
              "level": "WARNING",
              "findings": [{"code": "W-RT01", "level": "WARNING", "message": "Предупреждение", "step": 2}],
              "diagnostics": [],
              "period": {"startDate": "2024-01-10", "endDate": "2024-02-09"},
              "papers": [{"id": "p1", "blindLabel": "Бумага A", "ticker": "SBER", "name": "Сбербанк",
                          "sector": "Финансы", "startPrice": "270.50", "lotSize": 10}],
              "metrics": {
                "initialCapital": "1000000.00", "finalCapital": "1050000.00",
                "totalReturnPct": "5.00", "cagrPct": null, "grossReturnPct": "5.20", "netReturnPct": "5.00",
                "maxDrawdownPct": "1.50", "maxDrawdownDurationDays": 2, "maxDrawdownDurationSteps": 2,
                "volatilityPct": null, "sharpe": "1.20", "sortino": null, "turnover": "0.50",
                "tradesCount": 1, "winRatePct": "100.00", "rejectedOrdersCount": 0, "partialFillsCount": 0,
                "stopTakeSameBarCount": 0, "hhi": "1.000000", "largestPositionPct": "50.00", "bankrupt": false,
                "costs": {"total": "200.00", "commission": "150.00", "exchangeFee": "10.00", "spread": "30.00", "slippage": "10.00"},
                "benchmarkImoexPct": "3.00"
              },
              "equityCurve": [
                {"step": 0, "date": "2024-01-10T07:00:00Z", "capital": "1000000.00", "benchmarkCapital": "1000000.00"},
                {"step": 1, "date": "2024-01-11T07:00:00Z", "capital": "1050000.00", "benchmarkCapital": "1030000.00"}
              ],
              "underwaterCurve": [
                {"step": 0, "date": "2024-01-10T07:00:00Z", "drawdownPct": "0.00"}
              ],
              "roundTrips": [
                {"paperTicker": "SBER", "entryStep": 0, "exitStep": 1, "entryDate": "2024-01-10T07:00:00Z",
                 "exitDate": "2024-01-11T07:00:00Z", "lots": 5, "entryPrice": "270.00", "exitPrice": "280.00",
                 "grossPnl": "500.00", "costs": "20.00", "netPnl": "480.00"}
              ],
              "fills": [],
              "orders": [
                {"id": 1, "paperTicker": "SBER", "side": "buy", "orderType": "market", "lots": 5,
                 "status": "filled", "placedStep": 0, "closedStep": 0, "limitPrice": null,
                 "stopPrice": null, "trailingPct": null, "ruleName": "вход", "because": "SMA пересекла"}
              ]
            }
            """;

    @Test
    void exportsAlgoRunSheetsToXlsxAndCsvZip() throws Exception {
        JsonNode report = JsonMapper.builder().build().readTree(ALGO_REPORT);
        List<ReportSheets.Sheet> sheets = ReportSheets.ofAlgoRun(report, SNAPSHOT);

        byte[] xlsx = ReportFiles.xlsx(sheets);
        try (ReadableWorkbook wb = new ReadableWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(wb.getSheets().map(Sheet::getName).toList())
                    .containsExactly(
                            "Сводка",
                            "Метрики",
                            "Сделки",
                            "Заявки",
                            "Издержки",
                            "Кривая капитала",
                            "Параметры и версии",
                            "Диагностика");
            try (var rows = wb.findSheet("Заявки").orElseThrow().openStream()) {
                var orderRow = rows.skip(1).findFirst().orElseThrow();
                assertThat(orderRow.getCellText(11)).isEqualTo("вход");
                assertThat(orderRow.getCellText(12)).isEqualTo("SMA пересекла");
            }
        }

        byte[] zip = ReportFiles.csvZip(sheets);
        List<String> files = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                files.add(entry.getName());
            }
        }
        assertThat(files).contains("Сводка.csv", "Заявки.csv", "Диагностика.csv");
    }
}
