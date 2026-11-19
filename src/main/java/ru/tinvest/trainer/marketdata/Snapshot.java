package ru.tinvest.trainer.marketdata;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Загруженный неизменяемый снапшот данных; {@code snapshotKey} — snapshot_id в ClickHouse. */
public record Snapshot(UUID id, String snapshotKey, OffsetDateTime loadedAt, String description) {}
