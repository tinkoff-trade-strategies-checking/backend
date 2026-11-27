package ru.tinvest.trainer.marketdata;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** Событие календаря рынка MOEX / ЦБ РФ (заседания по ставке, отчётность, дивиденды, макро, backend#95). */
public record MarketEventSpec(
        long id,
        LocalDate eventDate,
        String eventType,
        @Nullable String ticker,
        String title,
        String importance,
        String description) {

    /** Представление по схеме {@code MarketEventSummary} контракта {@code api/openapi.yaml}. */
    public MarketEventSummary toSummary() {
        return new MarketEventSummary(id, eventDate, eventType, ticker, title, importance, description);
    }

    public record MarketEventSummary(
            long id,
            LocalDate eventDate,
            String eventType,
            @Nullable String ticker,
            String title,
            String importance,
            String description) {}
}
