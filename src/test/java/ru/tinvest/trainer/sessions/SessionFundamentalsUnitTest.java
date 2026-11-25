package ru.tinvest.trainer.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.tinvest.trainer.marketdata.Papers;
import ru.tinvest.trainer.marketdata.Regime;
import ru.tinvest.trainer.marketdata.SecurityFundamental;
import ru.tinvest.trainer.marketdata.SecurityFundamentals;
import ru.tinvest.trainer.marketdata.StartMode;
import ru.tinvest.trainer.marketdata.Timeframe;

/**
 * Модульные тесты маскирования и форматирования фундаментальных показателей (INV-1, INV-6, INV-7).
 */
class SessionFundamentalsUnitTest {

    private final BlindMasking masking = new BlindMasking();

    @Test
    void activeBlindSessionOmitsAllFundamentalsAndDate() {
        SessionRecord blindRunning = session(true, "running");
        Map<String, SecurityFundamental> byTicker = Map.of("SBER", sample("SBER"));

        Map<String, Object> response = masking.sessionFundamentals(blindRunning, LocalDate.of(2024, 6, 15), byTicker);

        assertThat(response).containsEntry("hiddenByBlindMode", true);
        assertThat(response).containsKey("asOfDate");
        assertThat(response.get("asOfDate")).isNull();
        assertThat(response.get("items")).isEqualTo(List.of());
    }

    @Test
    void finishedBlindSessionRevealsFundamentals() {
        SessionRecord blindCompleted = session(true, "completed");
        Map<String, SecurityFundamental> byTicker = Map.of("SBER", sample("SBER"));

        Map<String, Object> response = masking.sessionFundamentals(blindCompleted, LocalDate.of(2024, 6, 15), byTicker);

        assertThat(response).containsEntry("hiddenByBlindMode", false);
        assertThat(response).containsEntry("asOfDate", "2024-06-15");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("ticker", "SBER").containsEntry("available", true);
        assertThat(items.get(0).get("snapshot")).isNotNull();
        assertThat(items.get(1)).containsEntry("ticker", "UNKNOWN").containsEntry("available", false);
        assertThat(items.get(1).get("snapshot")).isNull();
    }

    @Test
    void openSessionReturnsFormattedSnapshotWithNullableFields() {
        SessionRecord openRunning = session(false, "running");
        SecurityFundamental sber = sample("SBER");

        Map<String, Object> response =
                masking.sessionFundamentals(openRunning, LocalDate.of(2024, 6, 15), Map.of("SBER", sber));

        assertThat(response).containsEntry("hiddenByBlindMode", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("items");
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) items.get(0).get("snapshot");
        assertThat(snapshot)
                .containsEntry("ticker", "SBER")
                .containsEntry("reportDate", "2023-12-31")
                .containsEntry("availableAt", "2024-02-28")
                .containsEntry("periodType", "FY")
                .containsEntry("currency", "RUB")
                .containsEntry("marketCapRub", "6540000000000.00")
                .containsEntry("peRatio", "4.34")
                .containsEntry("roe", "25.30")
                .containsEntry("eps", "66.80")
                .containsEntry("dividendYield", "11.40");
        assertThat(snapshot).containsKey("evEbitda");
        assertThat(snapshot.get("evEbitda")).isNull();
    }

    @Test
    void arenaFundamentalsSkipsWorkerAndDbForActiveBlindSession() {
        SessionRecord blindRunning = session(true, "running");
        Sessions sessions = mock(Sessions.class);
        when(sessions.requireOwned(blindRunning.id())).thenReturn(blindRunning);
        FakeWorker worker = new FakeWorker();
        SecurityFundamentals fundamentals = mock(SecurityFundamentals.class);
        Arena arena = new Arena(
                sessions,
                mock(ArenaJournal.class),
                mock(OrderRefs.class),
                mock(CommandRunner.class),
                mock(SessionEvents.class),
                worker,
                masking,
                mock(PlayerStates.class),
                fundamentals);

        Map<String, Object> response = arena.fundamentals(blindRunning.id());

        assertThat(response).containsEntry("hiddenByBlindMode", true);
        assertThat(response.get("asOfDate")).isNull();
        assertThat(response.get("items")).isEqualTo(List.of());
        assertThat(worker.replayJournals).isEmpty();
        verifyNoInteractions(fundamentals);
    }

    @Test
    void arenaFundamentalsQueriesPointInTimeAtCursorDateForOpenSession() {
        SessionRecord openRunning = session(false, "running", LocalDate.of(2022, 3, 14), LocalDate.of(2022, 4, 15), 0);
        Sessions sessions = mock(Sessions.class);
        when(sessions.requireOwned(openRunning.id())).thenReturn(openRunning);
        FakeWorker worker = new FakeWorker();
        SecurityFundamentals fundamentals = mock(SecurityFundamentals.class);
        LocalDate expectedDate = LocalDate.of(2022, 3, 14);
        when(fundamentals.findLatestAsOf(List.of("SBER", "UNKNOWN"), expectedDate))
                .thenReturn(Map.of("SBER", sample("SBER")));
        Arena arena = new Arena(
                sessions,
                mock(ArenaJournal.class),
                mock(OrderRefs.class),
                mock(CommandRunner.class),
                mock(SessionEvents.class),
                worker,
                masking,
                mock(PlayerStates.class),
                fundamentals);

        Map<String, Object> response = arena.fundamentals(openRunning.id());

        assertThat(response).containsEntry("hiddenByBlindMode", false);
        assertThat(response).containsEntry("asOfDate", "2022-03-14");
        verify(fundamentals).findLatestAsOf(eq(List.of("SBER", "UNKNOWN")), eq(expectedDate));
    }

    @Test
    void arenaFundamentalsRevealsCompletedBlindSession() {
        SessionRecord blindDone = session(true, "completed", LocalDate.of(2022, 3, 14), LocalDate.of(2022, 4, 15), 0);
        Sessions sessions = mock(Sessions.class);
        when(sessions.requireOwned(blindDone.id())).thenReturn(blindDone);
        FakeWorker worker = new FakeWorker();
        SecurityFundamentals fundamentals = mock(SecurityFundamentals.class);
        when(fundamentals.findLatestAsOf(any(), eq(LocalDate.of(2022, 3, 14))))
                .thenReturn(Map.of("SBER", sample("SBER")));
        Arena arena = new Arena(
                sessions,
                mock(ArenaJournal.class),
                mock(OrderRefs.class),
                mock(CommandRunner.class),
                mock(SessionEvents.class),
                worker,
                masking,
                mock(PlayerStates.class),
                fundamentals);

        Map<String, Object> response = arena.fundamentals(blindDone.id());

        assertThat(response).containsEntry("hiddenByBlindMode", false);
        assertThat(response).containsEntry("asOfDate", "2022-03-14");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("ticker", "SBER").containsEntry("available", true);
    }

    private static SessionRecord session(boolean blind, String status) {
        return session(blind, status, LocalDate.of(2024, 5, 1), LocalDate.of(2024, 6, 20), 5);
    }

    private static SessionRecord session(
            boolean blind, String status, LocalDate periodStart, LocalDate periodEnd, long currentStep) {
        List<PaperRecord> papers = List.of(
                new PaperRecord(0, Papers.id("SBER"), "SBER", "Финансы", 10, new BigDecimal("0.01"), "Сбербанк"),
                new PaperRecord(1, Papers.id("UNKNOWN"), "UNKNOWN", "Прочее", 1, new BigDecimal("0.01"), ""));
        return new SessionRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                blind,
                status,
                20,
                Timeframe.D1,
                Regime.CALM,
                blind ? StartMode.RANDOM : StartMode.CUSTOM,
                UUID.randomUUID(),
                "snap-1",
                periodStart,
                periodEnd,
                20,
                20,
                0.1,
                0.05,
                0.1,
                0.2,
                new BigDecimal("500000.00"),
                OffsetDateTime.now(ZoneOffset.UTC),
                currentStep,
                200,
                Sessions.DEFAULT_COSTS,
                papers);
    }

    private static SecurityFundamental sample(String ticker) {
        return new SecurityFundamental(
                ticker,
                LocalDate.of(2023, 12, 31),
                LocalDate.of(2024, 2, 28),
                "FY",
                "RUB",
                new BigDecimal("6540000000000.00"),
                new BigDecimal("4.34"),
                new BigDecimal("1.01"),
                new BigDecimal("2.18"),
                null,
                new BigDecimal("25.30"),
                new BigDecimal("3.05"),
                new BigDecimal("59.40"),
                new BigDecimal("47.60"),
                new BigDecimal("40.80"),
                new BigDecimal("26.80"),
                new BigDecimal("458.00"),
                null,
                null,
                new BigDecimal("66.80"),
                new BigDecimal("287.50"),
                new BigDecimal("11.40"),
                new BigDecimal("50.00"),
                new BigDecimal("48.00"),
                new BigDecimal("1.06"));
    }
}
