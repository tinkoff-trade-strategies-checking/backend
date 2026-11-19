package ru.tinvest.trainer.marketdata;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.tinvest.trainer.config.Uuid7;

/** Публичный API модуля: регистрация загруженного снапшота и чтение. Изменить снапшот нельзя (INV-5). */
@Service
public class Snapshots {

    private final JdbcClient jdbc;

    Snapshots(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Snapshot register(String snapshotKey, String description) {
        UUID id = Uuid7.next();
        jdbc.sql("INSERT INTO marketdata.snapshot (id, snapshot_key, description) VALUES (:id, :k, :d)")
                .param("id", id)
                .param("k", snapshotKey)
                .param("d", description)
                .update();
        return find(id).orElseThrow();
    }

    public Optional<Snapshot> find(UUID id) {
        return jdbc.sql("SELECT id, snapshot_key, loaded_at, description FROM marketdata.snapshot WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new Snapshot(
                        rs.getObject("id", UUID.class),
                        rs.getString("snapshot_key"),
                        rs.getObject("loaded_at", OffsetDateTime.class),
                        rs.getString("description")))
                .optional();
    }
}
