package ru.tinvest.trainer.worker;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** Клиент Simulation Worker по gRPC. Единственная точка обращения других модулей к воркеру. */
public interface SimulationWorkerClient {

    /**
     * Проверяет доступность воркера.
     *
     * @return версия Simulation Core, с которой собран воркер
     * @throws ru.tinvest.trainer.api.DependencyUnavailableException если воркер недоступен или не ответил в срок
     */
    String ping();

    /**
     * Классифицирует окна длиной {@code windowLength} торговых дней по дневным close IMOEX (decisions.md, «Критерии
     * категорий»).
     *
     * @param snapshotKey ключ снапшота в ClickHouse ({@code snapshot_id})
     * @param from начало фильтра окон в ответе, включительно; пороги всегда по всему снапшоту
     * @param to конец фильтра окон в ответе, включительно
     * @param startDate «своя дата»: вернуть одно окно с этим началом; {@code null} — все окна
     * @throws ru.tinvest.trainer.api.DependencyUnavailableException если воркер недоступен или не ответил в срок
     */
    PeriodClassification classifyPeriods(
            String snapshotKey, int windowLength, LocalDate from, LocalDate to, @Nullable LocalDate startDate);
}
