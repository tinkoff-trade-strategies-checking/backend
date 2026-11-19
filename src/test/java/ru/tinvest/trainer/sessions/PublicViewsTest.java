package ru.tinvest.trainer.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.tinvest.trainer.marketdata.Regime;
import ru.tinvest.trainer.marketdata.StartMode;
import ru.tinvest.trainer.marketdata.Timeframe;

/** Заголовки записей и признак раскрытия — без базы и воркера. */
class PublicViewsTest {

    @Test
    void notFinishedTitleUsesTheCreationDateInMoscow() {
        // 22:30 UTC = 01:30 следующего дня по Москве
        SessionRecord blind = session(true, "running", OffsetDateTime.of(2026, 11, 19, 22, 30, 0, 0, ZoneOffset.UTC));
        SessionRecord open = session(false, "running", OffsetDateTime.of(2026, 11, 19, 20, 59, 0, 0, ZoneOffset.UTC));

        assertThat(PublicViews.notFinishedTitle(blind)).isEqualTo("Слепая сессия от 20.11.2026");
        assertThat(PublicViews.notFinishedTitle(open)).isEqualTo("Сессия от 19.11.2026");
    }

    @Test
    void revealedTitleHasPeriodAndPapersInSessionOrder() {
        SessionRecord done = session(true, "completed", OffsetDateTime.now(ZoneOffset.UTC));

        assertThat(PublicViews.revealedTitle(done)).isEqualTo("26.02.2020 — 21.05.2020 · SBER, GAZP, YDEX");
        assertThat(done.revealed()).isTrue();
    }

    @Test
    void onlyARunningBlindSessionIsNotRevealed() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        SessionRecord blindRunning = session(true, "running", now);

        assertThat(blindRunning.revealed()).isFalse();
        assertThat(session(false, "running", now).revealed()).isTrue();
        assertThat(session(true, "bankrupt", now).revealed()).isTrue();
        assertThatThrownBy(() -> PublicViews.revealedTitle(blindRunning)).isInstanceOf(IllegalStateException.class);
    }

    private static SessionRecord session(boolean blind, String status, OffsetDateTime createdAt) {
        return new SessionRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                blind,
                status,
                20,
                Timeframe.D1,
                Regime.CALM,
                StartMode.CUSTOM,
                UUID.randomUUID(),
                LocalDate.of(2020, 2, 26),
                LocalDate.of(2020, 5, 21),
                20,
                20,
                0.1,
                0.1,
                0.1,
                0.1,
                new BigDecimal("500000.00"),
                createdAt,
                0,
                2,
                Sessions.DEFAULT_COSTS,
                List.of(
                        new PaperRecord(0, UUID.randomUUID(), "SBER", "A", 10, new BigDecimal("0.01")),
                        new PaperRecord(1, UUID.randomUUID(), "GAZP", "B", 10, new BigDecimal("0.01")),
                        new PaperRecord(2, UUID.randomUUID(), "YDEX", "C", 10, new BigDecimal("0.01"))));
    }
}
