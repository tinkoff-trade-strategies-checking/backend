package ru.tinvest.trainer.marketdata;

import java.math.BigDecimal;
import java.util.UUID;

/** Бумага запуска. {@code secid} (тикер) в слепом режиме клиенту не отдаётся (INV-7), наружу — только {@code sector}. */
public record PlannedPaper(UUID id, String secid, String sector, long lotSize, BigDecimal priceStep, String name) {

    public PlannedPaper(UUID id, String secid, String sector, long lotSize, BigDecimal priceStep) {
        this(id, secid, sector, lotSize, priceStep, "");
    }

    @Override
    public String toString() {
        return "PlannedPaper[" + id + "]";
    }
}
