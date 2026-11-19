package ru.tinvest.trainer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Точка входа backend-приложения «Т-Инвестиции: тренажёр и лаборатория стратегий». */
@SpringBootApplication
public class TrainerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrainerApplication.class, args);
    }
}
