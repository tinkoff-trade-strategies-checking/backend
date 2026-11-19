package ru.tinvest.trainer.marketdata;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Идентификатор бумаги в API: UUID по имени от тикера, одинаков во всех снапшотах (в ClickHouse ключ — {@code secid}). */
public final class Papers {

    private Papers() {}

    public static UUID id(String secid) {
        return UUID.nameUUIDFromBytes(("paper:" + secid).getBytes(StandardCharsets.UTF_8));
    }
}
