package ru.tinvest.trainer.config;

import java.security.SecureRandom;
import java.util.UUID;

/** Генератор UUID версии 7 (время в миллисекундах + случайные биты). */
public final class Uuid7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Uuid7() {}

    public static UUID next() {
        long millis = System.currentTimeMillis();
        long msb = (millis << 16) | 0x7000L | (RANDOM.nextLong() & 0x0FFFL);
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
