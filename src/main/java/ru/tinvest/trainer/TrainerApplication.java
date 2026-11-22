package ru.tinvest.trainer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Точка входа backend-приложения «Т-Инвестиции: тренажёр и лаборатория стратегий». */
@SpringBootApplication
public class TrainerApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(TrainerApplication.class, args);
        // разовая команда (веб-сервера нет): после работы процесс завершается кодом Spring, иначе его держат
        // не демонические потоки (пулы)
        if (shouldExit(context.getEnvironment())) {
            System.exit(SpringApplication.exit(context));
        }
    }

    static boolean shouldExit(Environment environment) {
        return environment.acceptsProfiles(Profiles.of("create-user", "import"));
    }
}
