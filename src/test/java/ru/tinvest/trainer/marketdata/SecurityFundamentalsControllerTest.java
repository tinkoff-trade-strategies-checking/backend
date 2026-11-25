package ru.tinvest.trainer.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import ru.tinvest.trainer.api.InvalidRequestException;

/**
 * Модульные тесты {@link SecurityFundamentalsController}, нормализации тикеров и корректности миграции
 * {@code V20__security_fundamentals.sql} (работают без Docker).
 */
class SecurityFundamentalsControllerTest {

    @Test
    void returnsAvailableTrueAndSnapshotForKnownTicker() {
        SecurityFundamentals service = mock(SecurityFundamentals.class);
        SecurityFundamental snap = new SecurityFundamental(
                "SBER",
                LocalDate.of(2025, 12, 31),
                LocalDate.of(2026, 2, 26),
                "FY",
                "RUB",
                new BigDecimal("7150000000000.00"),
                new BigDecimal("4.25"),
                new BigDecimal("0.92"),
                new BigDecimal("2.10"),
                null,
                new BigDecimal("23.50"),
                new BigDecimal("2.85"),
                new BigDecimal("58.20"),
                new BigDecimal("46.10"),
                new BigDecimal("38.50"),
                new BigDecimal("11.80"),
                new BigDecimal("6.40"),
                null,
                null,
                new BigDecimal("74.50"),
                new BigDecimal("344.00"),
                new BigDecimal("11.80"),
                new BigDecimal("50.00"),
                new BigDecimal("48.00"),
                new BigDecimal("1.04"));
        when(service.findLatest("SBER")).thenReturn(Optional.of(snap));

        SecurityFundamentalsController controller = new SecurityFundamentalsController(service);
        Map<String, Object> body = controller.paperFundamentals(" sber ");

        assertThat(body).containsEntry("ticker", "SBER").containsEntry("available", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) body.get("snapshot");
        assertThat(snapshot)
                .containsEntry("reportDate", "2025-12-31")
                .containsEntry("availableAt", "2026-02-26")
                .containsEntry("marketCapRub", "7150000000000.00")
                .containsEntry("peRatio", "4.25")
                .containsEntry("roe", "23.50");
        assertThat(snapshot.get("evEbitda")).isNull();
    }

    @Test
    void returnsAvailableFalseWhenTickerHasNoFundamentals() {
        SecurityFundamentals service = mock(SecurityFundamentals.class);
        when(service.findLatest("UNKNOWN")).thenReturn(Optional.empty());

        SecurityFundamentalsController controller = new SecurityFundamentalsController(service);
        Map<String, Object> body = controller.paperFundamentals("unknown");

        assertThat(body).containsEntry("ticker", "UNKNOWN").containsEntry("available", false);
        assertThat(body.get("snapshot")).isNull();
    }

    @Test
    void rejectsBlankOrTooLongTicker() {
        SecurityFundamentalsController controller =
                new SecurityFundamentalsController(mock(SecurityFundamentals.class));
        assertThatThrownBy(() -> controller.paperFundamentals("   ")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> controller.paperFundamentals("A".repeat(33)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void migrationV20SeedsAtLeast40BlueChipsWithValidPointInTimeDates() throws IOException {
        String sql = Files.readString(
                Path.of("src/main/resources/db/migration/V20__security_fundamentals.sql"), StandardCharsets.UTF_8);
        Pattern rowPattern = Pattern.compile(
                "\\('([A-Z0-9]+)',\\s*'(\\d{4}-\\d{2}-\\d{2})',\\s*'(\\d{4}-\\d{2}-\\d{2})',\\s*'(FY|Q[1-4]|TTM)'");
        Matcher m = rowPattern.matcher(sql);
        Set<String> tickers = new HashSet<>();
        int rowCount = 0;
        while (m.find()) {
            rowCount++;
            String ticker = m.group(1);
            LocalDate reportDate = LocalDate.parse(m.group(2));
            LocalDate availableAt = LocalDate.parse(m.group(3));
            tickers.add(ticker);
            // INV-1: дата публикации всегда >= отчётной даты
            assertThat(availableAt)
                    .as("available_at >= report_date for %s %s", ticker, reportDate)
                    .isAfterOrEqualTo(reportDate);
        }
        assertThat(tickers).hasSizeGreaterThanOrEqualTo(40);
        assertThat(tickers).contains("SBER", "GAZP", "LKOH", "YDEX", "ROSN", "NVTK", "T", "PLZL", "MOEX", "VTBR");
        assertThat(rowCount).isGreaterThanOrEqualTo(150);
    }
}
