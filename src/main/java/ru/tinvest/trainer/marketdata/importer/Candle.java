package ru.tinvest.trainer.marketdata.importer;

import java.math.BigDecimal;
import java.time.Instant;

/** Свеча как в таблице ClickHouse {@code candles}: время начала — UTC, цены — масштаб 6 (INV-6). */
record Candle(
        Instant tsOpen,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume,
        BigDecimal value) {}
