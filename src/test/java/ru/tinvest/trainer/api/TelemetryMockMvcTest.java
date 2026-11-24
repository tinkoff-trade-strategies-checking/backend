package ru.tinvest.trainer.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Проверка, что в MockMvc-режиме {@code /actuator/health} остаётся доступен, а {@code /actuator/prometheus} закрыт. */
@SpringBootTest(
        classes = TelemetryMockMvcTest.TestApp.class,
        properties = {
            "management.server.port=0",
            "spring.datasource.url=jdbc:postgresql://localhost:1/unused",
            "spring.datasource.username=unused",
            "spring.datasource.password=unused"
        })
@AutoConfigureMockMvc
class TelemetryMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthIsPublicAndPrometheusIsDeniedOnMainApi() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    }

    @Configuration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import({SecurityConfiguration.class, HealthController.class, ActuatorHealthCompatibilityController.class})
    static class TestApp {}
}
