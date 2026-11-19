package ru.tinvest.trainer.worker;

import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import ru.tinvest.trainer.worker.WorkerTypes.EstimateResult;
import ru.tinvest.trainer.worker.WorkerTypes.JournalRecord;
import ru.tinvest.trainer.worker.WorkerTypes.Market;
import ru.tinvest.trainer.worker.WorkerTypes.OrderParams;
import ru.tinvest.trainer.worker.WorkerTypes.ReplayResult;
import ru.tinvest.trainer.worker.WorkerTypes.StepReplay;

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
     * @param startDate «своя дата»: вернуть одно окно с этим началом; {@code null} — все окна. Окна с таким началом
     *     нет (core: {@code INVALID_ARGUMENT}) — пустой список
     * @throws ru.tinvest.trainer.api.DependencyUnavailableException воркер недоступен, не ответил в срок или у него
     *     нет данных снапшота ({@code FAILED_PRECONDITION}, {@code NOT_FOUND})
     */
    PeriodClassification classifyPeriods(
            String snapshotKey, int windowLength, LocalDate from, LocalDate to, @Nullable LocalDate startDate);

    /**
     * Воспроизводит журнал команд до курсора (INV-8): воркер без состояния, журнал передаётся целиком на каждый вызов.
     *
     * @param includeHistory вернуть все бары шагов от прогрева до {@code upToStep}
     * @param blind добавить слепой вид ({@code blindView}); рублёвые поля остаются в ответе и клиенту не уходят
     * @throws ru.tinvest.trainer.api.DependencyUnavailableException воркер недоступен или не ответил в срок
     */
    ReplayResult replaySession(
            Market market, List<JournalRecord> journal, long upToStep, boolean includeHistory, boolean blind);

    /**
     * Воспроизводит журнал до курсора без истории и отдаёт свечи шагов {@code (fromStep; upToStep]} из событий шагов
     * ядра: такт плеера не тянет всю историю на каждый шаг.
     *
     * @throws ru.tinvest.trainer.api.DependencyUnavailableException воркер недоступен или не ответил в срок
     */
    default StepReplay replaySteps(
            Market market, List<JournalRecord> journal, long upToStep, long fromStep, boolean blind) {
        throw new UnsupportedOperationException("replaySteps");
    }

    /**
     * Воспроизводит журнал до курсора с отчётом ядра ({@code include_report}) и историей баров: отчёт завершённой
     * сессии и итог для истории (INV-8). Слепой вид не запрашивается — отчёт только раскрытый.
     *
     * @return результат, у которого {@link ReplayResult#report()} заполнен
     * @throws ru.tinvest.trainer.api.DependencyUnavailableException воркер недоступен или не ответил в срок
     */
    ReplayResult replayWithReport(Market market, List<JournalRecord> journal, long upToStep);

    /**
     * Номер торгового дня на курсоре ({@code ReplaySessionResponse.current_day}) без журнала, истории баров и отчёта:
     * лёгкий вызов для номера дня в истории и ответах об идущей сессии.
     *
     * @throws ru.tinvest.trainer.api.DependencyUnavailableException воркер недоступен или не ответил в срок
     */
    int tradingDaysAt(Market market, long upToStep);

    /**
     * Оценивает заявку по {@code close} последнего бара до курсора, журнал не меняет (INV-8). Для слепых цен
     * ({@code OrderParams.blindPrice}) возвращает рублёвую заявку {@code resolvedOrder}.
     *
     * @throws ru.tinvest.trainer.api.DependencyUnavailableException воркер недоступен или не ответил в срок
     */
    EstimateResult estimateOrder(Market market, List<JournalRecord> journal, long upToStep, OrderParams order);
}
