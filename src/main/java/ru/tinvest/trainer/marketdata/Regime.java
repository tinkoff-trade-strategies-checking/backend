package ru.tinvest.trainer.marketdata;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Тип исторического периода, который выбирает пользователь (контракт: {@code Regime}). */
public enum Regime {
    CALM,
    MEDIUM,
    STRESS;

    /** Значение в контракте API: {@code calm}, {@code medium}, {@code stress}. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Regime> fromCode(String code) {
        return Arrays.stream(values()).filter(r -> r.code().equals(code)).findFirst();
    }
}
