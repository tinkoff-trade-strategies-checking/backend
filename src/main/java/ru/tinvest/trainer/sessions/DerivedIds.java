package ru.tinvest.trainer.sessions;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Детерминированные идентификаторы UUID v5 (RFC 9562) для сущностей, которые пересчитываются из журнала: исполнения,
 * сделки. Идентификатор — от (сессия, вид, номер в ядре), поэтому одинаков в снимке, SSE и отчёте (INV-8).
 */
public final class DerivedIds {

    private DerivedIds() {}

    /** Исполнение: номер в ядре — {@code Fill.seq} (порядковый номер в журнале сессии, стабилен при перепроигрывании). */
    public static UUID fill(UUID sessionId, long seq) {
        return v5(sessionId, "fill:" + seq);
    }

    /** Расхождение: номер в ядре — {@code Divergence.seq} (0-based индекс в ответе {@code AnalyzeDivergences}). */
    public static UUID divergence(UUID sessionId, long seq) {
        return v5(sessionId, "divergence:" + seq);
    }

    /**
     * Сделка обратной проверки (ФТ-20): номер в ядре — {@code MissedProfitableTrade.seq}.
     */
    public static UUID reverseCheckTrade(UUID sessionId, long seq) {
        return v5(sessionId, "reverse:" + seq);
    }

    static UUID v5(UUID namespace, String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(ByteBuffer.allocate(16)
                    .putLong(namespace.getMostSignificantBits())
                    .putLong(namespace.getLeastSignificantBits())
                    .array());
            sha1.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] hash = sha1.digest();
            hash[6] = (byte) ((hash[6] & 0x0F) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3F) | 0x80);
            ByteBuffer buffer = ByteBuffer.wrap(hash, 0, 16);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 недоступен", e);
        }
    }
}
