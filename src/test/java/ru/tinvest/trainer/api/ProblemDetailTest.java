package ru.tinvest.trainer.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Защита проверяется в IdentityApiTest; здесь — только формат ошибок.
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(controllers = ProblemDetailTest.FailingController.class)
@Import({ApiExceptionHandler.class, ProblemDetailTest.FailingController.class})
class ProblemDetailTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void invalidRequestReturns400WithSafeMessage() throws Exception {
        mockMvc.perform(get("/api/test/bad-request"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").value("invalid lot count"))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void notFoundReturns404WithSafeMessage() throws Exception {
        mockMvc.perform(get("/api/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("session not found"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void jdkExceptionsAreNotEchoedAndReturn500() throws Exception {
        for (String path : new String[] {"/api/test/illegal-argument", "/api/test/no-such-element"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.detail").value("Internal server error"))
                    .andExpect(content().string(not(containsString("SBER"))));
        }
    }

    @Test
    void dependencyUnavailableReturns503WithoutInternalText() throws Exception {
        for (String path : new String[] {"/api/test/unavailable", "/api/test/db-down", "/api/test/db-no-connection"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(503))
                    .andExpect(jsonPath("$.title").value("Service Unavailable"))
                    .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                    .andExpect(jsonPath("$.detail").value("Service temporarily unavailable"))
                    .andExpect(content().string(not(containsString("secret"))));
        }
    }

    @Test
    void unexpectedExceptionReturns500WithoutLeakingInternalMessage() throws Exception {
        mockMvc.perform(get("/api/test/internal"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Internal server error"))
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void frameworkErrorsGetCodeByStatus() throws Exception {
        mockMvc.perform(get("/api/test/no-such-route"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(post("/api/test/bad-request"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mockMvc.perform(post("/api/test/body").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mockMvc.perform(post("/api/test/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void clientDisconnectOnEventStreamIsNotAnErrorAndWritesNoProblem() throws Exception {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (String path :
                    new String[] {"/api/test/sse-broken-pipe", "/api/test/sse-gone", "/api/test/sse-async"}) {
                mockMvc.perform(get(path).accept(MediaType.TEXT_EVENT_STREAM))
                        .andExpect(content().string(""))
                        .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                                        String.valueOf(result.getResponse().getContentType()))
                                .doesNotContain("problem+json"));
            }
            org.assertj.core.api.Assertions.assertThat(appender.list)
                    .noneMatch(e -> e.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN));
            // обычная ошибка ввода-вывода остаётся ошибкой сервера
            mockMvc.perform(get("/api/test/io-other"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
            org.assertj.core.api.Assertions.assertThat(appender.list)
                    .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @RestController
    @RequestMapping("/api/test")
    static class FailingController {

        @GetMapping("/bad-request")
        void badRequest() {
            throw new InvalidRequestException("invalid lot count");
        }

        @PostMapping(path = "/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        void body(@RequestBody java.util.Map<String, Object> body) {}

        @GetMapping("/not-found")
        void notFound() {
            throw new NotFoundException("session not found");
        }

        @GetMapping("/illegal-argument")
        void illegalArgument() {
            throw new IllegalArgumentException("date 2020-03-01 SBER");
        }

        @GetMapping("/no-such-element")
        void noSuchElement() {
            throw new NoSuchElementException("SBER");
        }

        @GetMapping("/unavailable")
        void unavailable() {
            throw new DependencyUnavailableException("worker host=10.0.0.5 secret", new RuntimeException());
        }

        @GetMapping("/db-down")
        void dbDown() {
            throw new DataAccessResourceFailureException("jdbc:postgresql://db password=secret");
        }

        @GetMapping("/db-no-connection")
        void dbNoConnection() {
            throw new CannotGetJdbcConnectionException("password=secret", new java.sql.SQLException("x"));
        }

        @GetMapping(path = "/sse-broken-pipe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        void sseBrokenPipe() throws java.io.IOException {
            throw new java.io.IOException("Broken pipe");
        }

        @GetMapping(path = "/sse-gone", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        void sseGone() throws java.io.IOException {
            throw new java.io.IOException("Client is gone or too slow");
        }

        @GetMapping(path = "/sse-async", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        void sseAsync() throws Exception {
            throw new org.springframework.web.context.request.async.AsyncRequestNotUsableException("gone");
        }

        @GetMapping("/io-other")
        void ioOther() throws java.io.IOException {
            throw new java.io.IOException("disk secret");
        }

        @GetMapping("/internal")
        void internal() {
            throw new RuntimeException("db password=secret SBER");
        }
    }
}
