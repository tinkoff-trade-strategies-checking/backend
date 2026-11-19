package ru.tinvest.trainer.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(controllers = ProblemDetailTest.FailingController.class)
@Import({ApiExceptionHandler.class, ProblemDetailTest.FailingController.class})
class ProblemDetailTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void illegalArgumentReturns400ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/test/bad-request"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").value("invalid lot count"));
    }

    @Test
    void noSuchElementReturns404ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("session not found"));
    }

    @Test
    void unavailableDependencyReturns503ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/test/unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value("worker unavailable"));
    }

    @Test
    void unexpectedExceptionReturns500WithoutLeakingInternalMessage() throws Exception {
        mockMvc.perform(get("/api/test/internal"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.detail").value("Internal server error"));
    }

    @RestController
    @RequestMapping("/api/test")
    static class FailingController {

        @GetMapping("/bad-request")
        void badRequest() {
            throw new IllegalArgumentException("invalid lot count");
        }

        @GetMapping("/not-found")
        void notFound() {
            throw new NoSuchElementException("session not found");
        }

        @GetMapping("/unavailable")
        void unavailable() {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "worker unavailable");
        }

        @GetMapping("/internal")
        void internal() {
            throw new RuntimeException("db password=secret SBER");
        }
    }
}
