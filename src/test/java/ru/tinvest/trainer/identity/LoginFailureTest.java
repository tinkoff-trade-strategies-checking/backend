package ru.tinvest.trainer.identity;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.tinvest.trainer.PostgresIntegrationTest;

/** Сбой БД при входе — 503 без внутреннего текста, а не «неверный пароль». */
@AutoConfigureMockMvc
class LoginFailureTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private Accounts accounts;

    @Test
    void databaseFailureDuringLoginIs503() throws Exception {
        doThrow(new DataAccessResourceFailureException("jdbc:postgresql://db password=secret"))
                .when(accounts)
                .findByEmail(anyString());
        String token = mockMvc.perform(get("/api/v1/me"))
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN")
                .getValue();

        mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(new Cookie("XSRF-TOKEN", token))
                        .header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@example.com\",\"password\":\"x\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Service temporarily unavailable"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("secret"))));
    }
}
