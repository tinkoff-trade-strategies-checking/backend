package ru.tinvest.trainer.marketdata;

/** Результат подбора запуска: {@link LaunchPlan} или {@link LaunchRejection}. */
public sealed interface LaunchResult permits LaunchPlan, LaunchRejection {}
