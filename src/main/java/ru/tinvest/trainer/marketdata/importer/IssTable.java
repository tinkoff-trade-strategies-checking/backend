package ru.tinvest.trainer.marketdata.importer;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** Один блок ответа ISS: имена столбцов и строки; {@code total} — TOTAL из {@code <блок>.cursor}, если он есть. */
record IssTable(List<String> columns, List<List<JsonNode>> rows, Long total, String prevDate) {

    static IssTable empty() {
        return new IssTable(List.of(), List.of(), null, null);
    }

    int size() {
        return rows.size();
    }

    boolean isEmpty() {
        return rows.isEmpty();
    }

    JsonNode get(List<JsonNode> row, String column) {
        int i = columns.indexOf(column);
        if (i < 0) {
            throw new ImportException("В ответе ISS нет столбца " + column + ", есть " + columns);
        }
        return row.get(i);
    }
}
