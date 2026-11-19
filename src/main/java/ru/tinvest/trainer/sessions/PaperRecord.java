package ru.tinvest.trainer.sessions;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Бумага сессии. {@code paperIndex} — индекс в сессии ({@code instrument_index} ядра), в слепом режиме единственный
 * идентификатор бумаги наружу (INV-7). {@code toString} тикер и сектор не печатает.
 */
public record PaperRecord(
        int paperIndex, UUID paperId, String secid, String sector, long lotSize, BigDecimal priceStep, String name) {

    public PaperRecord(int paperIndex, UUID paperId, String secid, String sector, long lotSize, BigDecimal priceStep) {
        this(paperIndex, paperId, secid, sector, lotSize, priceStep, "");
    }

    /** Название для раскрытого вида: из каталога, нет — тикер. */
    public String displayName() {
        return name.isBlank() ? secid : name;
    }

    @Override
    public String toString() {
        return "PaperRecord[" + paperIndex + "]";
    }
}
