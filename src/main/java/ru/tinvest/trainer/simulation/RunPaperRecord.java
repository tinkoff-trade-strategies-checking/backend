package ru.tinvest.trainer.simulation;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Бумага прогона алгоритма. В слепом режиме до завершения прогона тикер, название и сектор клиенту не отдаются
 * (INV-7); {@code toString} не печатает тикер и сектор.
 */
public record RunPaperRecord(
        int paperIndex, UUID paperId, String secid, String sector, long lotSize, BigDecimal priceStep, String name) {

    /** Название бумаги для раскрытого вида: из каталога или тикер, если название пустое. */
    public String displayName() {
        return name.isBlank() ? secid : name;
    }

    @Override
    public String toString() {
        return "RunPaperRecord[" + paperIndex + "]";
    }
}
