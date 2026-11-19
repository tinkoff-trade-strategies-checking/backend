package ru.tinvest.trainer.marketdata;

/** Как выбирается начало периода: {@code RANDOM} — слепая сессия, {@code CUSTOM} — своя дата, сессия неслепая. */
public enum StartMode {
    RANDOM,
    CUSTOM
}
