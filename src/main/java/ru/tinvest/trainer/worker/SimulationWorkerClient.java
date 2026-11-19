package ru.tinvest.trainer.worker;

/** Клиент Simulation Worker по gRPC. Единственная точка обращения других модулей к воркеру. */
public interface SimulationWorkerClient {

    /**
     * Проверяет доступность воркера.
     *
     * @return версия Simulation Core, с которой собран воркер
     * @throws ru.tinvest.trainer.api.DependencyUnavailableException если воркер недоступен или не ответил в срок
     */
    String ping();
}
