package ru.tinvest.trainer.sessions;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Бумага сессии. {@code paperIndex} — индекс в сессии ({@code instrument_index} ядра), в слепом режиме единственный
 * идентификатор бумаги наружу (INV-7). {@code toString} тикер и сектор не печатает.
 */
public record PaperRecord(
        int paperIndex, UUID paperId, String secid, String sector, long lotSize, BigDecimal priceStep) {

    @Override
    public String toString() {
        return "PaperRecord[" + paperIndex + "]";
    }
}
