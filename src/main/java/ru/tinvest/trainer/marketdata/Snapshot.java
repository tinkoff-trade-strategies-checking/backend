package ru.tinvest.trainer.marketdata;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Загруженный неизменяемый снапшот данных; {@code snapshotKey} — snapshot_id в ClickHouse. {@code dataHash} —
 * SHA-256 данных в hex (пусто у снапшотов без хэша): по нему сессию можно воспроизвести и сверить (INV-3).
 */
public record Snapshot(UUID id, String snapshotKey, OffsetDateTime loadedAt, String description, String dataHash) {

    public Snapshot(UUID id, String snapshotKey, OffsetDateTime loadedAt, String description) {
        this(id, snapshotKey, loadedAt, description, "");
    }
}
