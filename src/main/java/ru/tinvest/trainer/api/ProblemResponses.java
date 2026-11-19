package ru.tinvest.trainer.api;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Ответы {@code application/problem+json} из фильтров безопасности (до MVC). Тексты — константы, без данных запроса. */
final class ProblemResponses {

    private final ObjectMapper mapper;

    ProblemResponses(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    void write(HttpServletResponse response, int status, String title, String detail) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", title);
        body.put("status", status);
        body.put("detail", detail);
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(mapper.writeValueAsString(body));
    }
}
