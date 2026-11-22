package ru.tinvest.trainer.workshop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit-тесты вспомогательных функций {@link WorkshopService}. */
class WorkshopServiceUnitTest {

    @Test
    void truncateUtf16SafeNeverSplitsSurrogatePairs() {
        String withRocket = "abcdefghi\uD83D\uDE80z";
        assertThat(WorkshopService.truncateUtf16Safe(withRocket, 10)).isEqualTo("abcdefghi");
        assertThat(WorkshopService.truncateUtf16Safe(withRocket, 11)).isEqualTo("abcdefghi\uD83D\uDE80");
        assertThat(WorkshopService.truncateUtf16Safe(withRocket, 12)).isEqualTo(withRocket);
        assertThat(WorkshopService.truncateUtf16Safe("\uD83D\uDE80", 1)).isEmpty();
        assertThat(WorkshopService.truncateUtf16Safe("abc", 0)).isEmpty();
    }
}
