package ru.tinvest.trainer.api;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Ответы {@code application/problem+json} из фильтров безопасности (до MVC). Тексты — константы, без данных запроса. */
final class ProblemResponses {

    private ProblemResponses() {}

    static void write(HttpServletResponse response, int status, String title, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter()
                .write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status + ",\"detail\":\""
                        + detail + "\"}");
    }
}
