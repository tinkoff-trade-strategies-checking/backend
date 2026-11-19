package ru.tinvest.trainer.sessions;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Ответы о сессии, общие для воспроизведения ({@code sessions}) и отчёта ({@code analysis}): «нет такой» и «не
 * завершена». Единственное место, где решается, что видно об идущей сессии, — она ничего не раскрывает: ни бумаг, ни
 * периода, ни итога, а слепая — ещё и названия (INV-1, INV-7).
 */
public final class PublicViews {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter TITLE_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private PublicViews() {}

    /** Сессии нет или она чужая: {@code NotFound}. */
    public static Map<String, Object> notFound(UUID sessionId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "NOT_FOUND");
        out.put("sessionId", sessionId);
        return out;
    }

    /** Идущая сессия: {@code NotFinished}. */
    public static Map<String, Object> notFinished(SessionLookup.Unfinished lookup) {
        SessionRecord session = lookup.session();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "NOT_FINISHED");
        out.put("sessionId", session.id());
        out.put("blind", session.blind());
        out.put("title", notFinishedTitle(session));
        out.put("createdAt", ViewFormat.instant(session.createdAt().toInstant().toEpochMilli()));
        out.put("playedDays", lookup.playedDays());
        out.put("totalDays", session.tradingDays());
        out.put("regime", session.regime().code());
        return out;
    }

    /**
     * Заголовок идущей сессии: слепой — «Слепая сессия от ДД.ММ.ГГГГ» (дата создания по Москве, {@code decisions.md},
     * «Отчёт и история: реализация»), открытой — «Сессия от ДД.ММ.ГГГГ». Бумаг и периода в нём нет.
     */
    public static String notFinishedTitle(SessionRecord session) {
        String date = TITLE_DATE.format(session.createdAt().atZoneSameInstant(MOSCOW));
        return (session.blind() ? "Слепая сессия от " : "Сессия от ") + date;
    }

    /**
     * Заголовок раскрытой записи: период и бумаги, «26.02.2020 — 21.05.2020 · SBER, GAZP, YDEX» (решение владельца
     * продукта). Только для раскрытой сессии ({@link SessionRecord#revealed()}).
     */
    public static String revealedTitle(SessionRecord session) {
        if (!session.revealed()) {
            throw new IllegalStateException("Заголовок с бумагами и периодом — только у раскрытой сессии");
        }
        StringBuilder papers = new StringBuilder();
        for (PaperRecord paper : session.papers()) {
            papers.append(papers.isEmpty() ? "" : ", ").append(paper.secid());
        }
        return TITLE_DATE.format(session.periodStart()) + " — " + TITLE_DATE.format(session.periodEnd()) + " · "
                + papers;
    }
}
