# backend

Backend-приложение проекта «Т-Инвестиции: тренажёр и лаборатория стратегий» — модульный монолит на Java 25, Spring Boot 4 и Spring Modulith 2.

- **Сборка и проверка:** `./gradlew build` (Spotless + ErrorProne + тесты, нужен Docker для Testcontainers).
- **Форматирование:** `./gradlew spotlessApply` / `./gradlew spotlessCheck`.
- **Локальный запуск:** см. раздел «Команды» в `AGENTS.md`.
- **Архитектура и стандарты:** `AGENTS.md`, `../documentation/architecture.md`, `../home/standards.md`.
