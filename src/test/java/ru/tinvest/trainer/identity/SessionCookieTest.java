package ru.tinvest.trainer.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.tinvest.trainer.PostgresIntegrationTest;

/** Флаги настоящих cookie Tomcat: MockMvc их не формирует. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionCookieTest extends PostgresIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private Accounts accounts;

    private static String header(List<String> setCookies, String name) {
        return setCookies.stream()
                .filter(c -> c.startsWith(name + "="))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void sessionAndCsrfCookiesHaveSecurityFlags() throws Exception {
        accounts.create("cookie-flags@example.com", "correct horse");
        HttpClient client = HttpClient.newHttpClient();
        URI base = URI.create("http://localhost:" + port);

        HttpResponse<String> me = client.send(
                HttpRequest.newBuilder(base.resolve("/api/v1/me")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode()).isEqualTo(401);
        String tokenHeader = header(me.headers().allValues("Set-Cookie"), "XSRF-TOKEN");
        String token = tokenHeader.substring("XSRF-TOKEN=".length(), tokenHeader.indexOf(';'));

        HttpResponse<String> login = client.send(
                HttpRequest.newBuilder(base.resolve("/api/v1/auth/login"))
                        .header("Content-Type", "application/json")
                        .header("Cookie", "XSRF-TOKEN=" + token)
                        .header("X-XSRF-TOKEN", token)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"email\":\"cookie-flags@example.com\",\"password\":\"correct horse\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(204);

        List<String> setCookies = login.headers().allValues("Set-Cookie");
        assertThat(header(setCookies, "SESSION"))
                .containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("Secure")
                .containsIgnoringCase("SameSite=Lax");
        String xsrf = header(setCookies, "XSRF-TOKEN");
        assertThat(xsrf).containsIgnoringCase("Secure").containsIgnoringCase("SameSite=Lax");
        assertThat(xsrf.toLowerCase(Locale.ROOT)).doesNotContain("httponly");
    }
}
