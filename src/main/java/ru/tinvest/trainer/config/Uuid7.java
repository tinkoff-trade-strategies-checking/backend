package ru.tinvest.trainer.config;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Генератор UUID версии 7 (RFC 9562, метод 1): время в миллисекундах и 12-битный счётчик в rand_a, который
 * гарантирует монотонность внутри одной миллисекунды (и при откате часов); при переполнении счётчика время
 * сдвигается на миллисекунду вперёд.
 */
public final class Uuid7 {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object LOCK = new Object();
    private static long lastMillis;
    private static int counter;

    private Uuid7() {}

    public static UUID next() {
        long millis;
        int seq;
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (now > lastMillis) {
                lastMillis = now;
                // старт в младшей половине диапазона оставляет запас на всплеск в одну миллисекунду
                counter = RANDOM.nextInt(0x800);
            } else if (++counter > 0xFFF) {
                lastMillis++;
                counter = RANDOM.nextInt(0x800);
            }
            millis = lastMillis;
            seq = counter;
        }
        long msb = (millis << 16) | 0x7000L | seq;
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
