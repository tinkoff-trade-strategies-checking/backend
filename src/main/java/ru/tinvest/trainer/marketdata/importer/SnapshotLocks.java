package ru.tinvest.trainer.marketdata.importer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Блокировка импорта по ключу снапшота: advisory lock PostgreSQL на отдельном соединении держится до закрытия.
 * Второй одновременный запуск с теми же параметрами сразу получает ошибку, а не задваивает куски в ClickHouse.
 */
@FunctionalInterface
interface SnapshotLocks {

    /** Освобождает блокировку. */
    interface Held extends AutoCloseable {
        @Override
        void close();
    }

    Held acquire(String snapshotKey);

    static SnapshotLocks postgres(DataSource dataSource) {
        return key -> {
            String lockName = "marketdata.import:" + key;
            Connection c = null;
            try {
                c = dataSource.getConnection();
                boolean ok;
                try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))")) {
                    ps.setString(1, lockName);
                    try (ResultSet rs = ps.executeQuery()) {
                        ok = rs.next() && rs.getBoolean(1);
                    }
                }
                if (!ok) {
                    c.close();
                    throw new ImportException("Импорт снапшота " + key + " уже запущен в другом процессе");
                }
                Connection held = c;
                return () -> {
                    try (held) {
                        try (PreparedStatement ps =
                                held.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))")) {
                            ps.setString(1, lockName);
                            ps.execute();
                        }
                    } catch (SQLException e) {
                        throw new ImportException("Не удалось снять блокировку импорта", e);
                    }
                };
            } catch (SQLException e) {
                if (c != null) {
                    try {
                        c.close();
                    } catch (SQLException suppressed) {
                        e.addSuppressed(suppressed);
                    }
                }
                throw new ImportException("PostgreSQL недоступен для блокировки импорта", e);
            }
        };
    }
}
