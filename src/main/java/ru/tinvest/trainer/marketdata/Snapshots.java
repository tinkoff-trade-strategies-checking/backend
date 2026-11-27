package ru.tinvest.trainer.marketdata;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.tinvest.trainer.config.Uuid7;

/** Публичный API модуля: регистрация загруженного снапшота и чтение. Изменить снапшот нельзя (INV-5). */
@Service
public class Snapshots {

    private final JdbcClient jdbc;
    /** Снапшоты неизменяемы (INV-5): найденные записи кэшируются по id, чтобы не ходить в БД на каждую сессию. */
    private final Map<UUID, Snapshot> byId = new ConcurrentHashMap<>();

    Snapshots(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Snapshot register(String snapshotKey, String description) {
        return register(snapshotKey, description, "");
    }

    public Snapshot register(String snapshotKey, String description, String dataHash) {
        UUID id = Uuid7.next();
        jdbc.sql("INSERT INTO marketdata.snapshot (id, snapshot_key, description, data_hash) "
                        + "VALUES (:id, :k, :d, :h)")
                .param("id", id)
                .param("k", snapshotKey)
                .param("d", description)
                .param("h", dataHash)
                .update();
        return find(id).orElseThrow();
    }

    public Optional<Snapshot> find(UUID id) {
        Snapshot cached = byId.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<Snapshot> loaded = jdbc.sql(
                        "SELECT id, snapshot_key, loaded_at, description, data_hash FROM marketdata.snapshot WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new Snapshot(
                        rs.getObject("id", UUID.class),
                        rs.getString("snapshot_key"),
                        rs.getObject("loaded_at", OffsetDateTime.class),
                        rs.getString("description"),
                        rs.getString("data_hash")))
                .optional();
        loaded.ifPresent(s -> byId.put(id, s));
        return loaded;
    }

    public Optional<Snapshot> findByKey(String snapshotKey) {
        return jdbc.sql(
                        "SELECT id, snapshot_key, loaded_at, description, data_hash FROM marketdata.snapshot WHERE snapshot_key = :k")
                .param("k", snapshotKey)
                .query((rs, n) -> new Snapshot(
                        rs.getObject("id", UUID.class),
                        rs.getString("snapshot_key"),
                        rs.getObject("loaded_at", OffsetDateTime.class),
                        rs.getString("description"),
                        rs.getString("data_hash")))
                .optional();
    }

    /** Все снапшоты, новые первыми (по времени загрузки). Готовность к чтению проверяет вызывающий. */
    public List<Snapshot> findAllNewestFirst() {
        return jdbc.sql("SELECT id, snapshot_key, loaded_at, description, data_hash FROM marketdata.snapshot "
                        + "ORDER BY loaded_at DESC, id DESC")
                .query((rs, n) -> new Snapshot(
                        rs.getObject("id", UUID.class),
                        rs.getString("snapshot_key"),
                        rs.getObject("loaded_at", OffsetDateTime.class),
                        rs.getString("description"),
                        rs.getString("data_hash")))
                .list();
    }
}
