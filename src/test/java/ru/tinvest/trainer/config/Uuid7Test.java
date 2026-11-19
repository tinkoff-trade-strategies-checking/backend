package ru.tinvest.trainer.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class Uuid7Test {

    @Test
    void idsAreVersion7AndStrictlyIncreasing() {
        UUID prev = Uuid7.next();
        assertThat(prev.version()).isEqualTo(7);
        for (int i = 0; i < 20_000; i++) {
            UUID next = Uuid7.next();
            assertThat(next.version()).isEqualTo(7);
            assertThat(next.getMostSignificantBits()).isGreaterThan(prev.getMostSignificantBits());
            prev = next;
        }
    }
}
