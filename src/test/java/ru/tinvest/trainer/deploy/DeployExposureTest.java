package ru.tinvest.trainer.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * INV-10: внутренние данные (gRPC, PostgreSQL, ClickHouse) недоступны снаружи, вход только через Caddy по HTTPS.
 * Проверяется конфигурация {@code deploy/}; сканирование снаружи — вне этого теста.
 */
class DeployExposureTest {

    private static final Path COMPOSE = Path.of("deploy/docker-compose.yml");
    private static final Path CADDYFILE = Path.of("deploy/Caddyfile");
    private static final List<String> INTERNAL_SERVICES = List.of("postgres", "clickhouse", "worker", "backend");
    private static final List<String> CADDY_PORTS = List.of("80", "443");

    private static final Pattern HTTP_SITE = Pattern.compile("^http://.*\\{$");
    private static final Pattern TLS_OFF = Pattern.compile("^tls\\s+off\\b");
    private static final Pattern AUTO_HTTPS_OFF = Pattern.compile("^auto_https\\s+off\\b");

    @Test
    void onlyCaddyPublishesPorts() throws IOException {
        assertThat(portViolations(loadCompose(read(COMPOSE)))).isEmpty();
    }

    @Test
    void internalServicesAreOnInternalNetwork() throws IOException {
        assertThat(networkViolations(loadCompose(read(COMPOSE)))).isEmpty();
    }

    @Test
    void caddyfileServesOnlyHttps() throws IOException {
        assertThat(caddyfileViolations(read(CADDYFILE))).isEmpty();
    }

    @Test
    void portCheckReportsLeakedPostgresPort() {
        String yaml = """
                services:
                  postgres:
                    ports: ["5432:5432"]
                    networks: [internal]
                  caddy:
                    ports: ["80:80", "443:443"]
                networks:
                  internal:
                    internal: true
                """;
        assertThat(portViolations(loadCompose(yaml))).singleElement().asString().contains("postgres");
    }

    @Test
    void portCheckReportsUnexpectedCaddyPort() {
        String yaml = """
                services:
                  caddy:
                    ports: ["80:80", "443:443", "8080:8080"]
                """;
        assertThat(portViolations(loadCompose(yaml))).isNotEmpty();
    }

    @Test
    void networkCheckReportsServiceOnNonInternalNetwork() {
        String yaml = """
                services:
                  postgres:
                    networks: [edge]
                  clickhouse:
                    networks: [internal]
                  worker: {}
                  backend:
                    networks: [internal, edge]
                networks:
                  internal:
                    internal: true
                  edge: {}
                """;
        assertThat(networkViolations(loadCompose(yaml))).hasSize(3);
    }

    @Test
    void caddyfileCheckReportsPlainHttpAndDisabledTls() {
        String caddyfile = """
                http://localhost {
                    respond "ok"
                }
                example.com {
                    tls off
                }
                {
                    auto_https off
                }
                """;
        assertThat(caddyfileViolations(caddyfile)).hasSize(3);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadCompose(String yaml) {
        return (Map<String, Object>) new Yaml().load(yaml);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> services(Map<String, Object> compose) {
        return (Map<String, Object>) compose.get("services");
    }

    @SuppressWarnings("unchecked")
    private static List<String> portViolations(Map<String, Object> compose) {
        List<String> violations = new ArrayList<>();
        services(compose).forEach((name, value) -> {
            Map<String, Object> service = (Map<String, Object>) value;
            Object ports = service.get("ports");
            if (!"caddy".equals(name)) {
                if (ports != null) {
                    violations.add("сервис " + name + " публикует порты: " + ports);
                }
                return;
            }
            List<String> published = new ArrayList<>();
            if (ports != null) {
                for (Object port : (List<Object>) ports) {
                    String text = String.valueOf(port);
                    int colon = text.indexOf(':');
                    published.add(colon < 0 ? text : text.substring(0, colon));
                }
            }
            if (!published.equals(CADDY_PORTS)) {
                violations.add("caddy публикует " + published + ", ожидается " + CADDY_PORTS);
            }
        });
        return violations;
    }

    @SuppressWarnings("unchecked")
    private static List<String> networkViolations(Map<String, Object> compose) {
        List<String> violations = new ArrayList<>();
        Map<String, Object> networks = (Map<String, Object>) compose.getOrDefault("networks", Map.of());
        Map<String, Object> services = services(compose);
        for (String name : INTERNAL_SERVICES) {
            Map<String, Object> service = (Map<String, Object>) services.get(name);
            if (service == null) {
                violations.add("сервис " + name + " не найден");
                continue;
            }
            Object declared = service.get("networks");
            List<String> names;
            if (declared instanceof Map<?, ?> map) {
                names = map.keySet().stream().map(String::valueOf).toList();
            } else if (declared instanceof List<?> list) {
                names = list.stream().map(String::valueOf).toList();
            } else {
                names = List.of();
            }
            if (names.isEmpty()) {
                violations.add("сервис " + name + " без явной сети (попадёт в default, не internal)");
            }
            for (String network : names) {
                Object definition = networks.get(network);
                boolean internal = definition instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("internal"));
                if (!internal) {
                    violations.add("сервис " + name + " в сети " + network + " без internal: true");
                }
            }
        }
        return violations;
    }

    private static List<String> caddyfileViolations(String caddyfile) {
        List<String> violations = new ArrayList<>();
        for (String raw : caddyfile.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("#")) {
                continue;
            }
            if (HTTP_SITE.matcher(line).matches()) {
                violations.add("http-сайт: " + line);
            }
            if (TLS_OFF.matcher(line).find()) {
                violations.add("tls off: " + line);
            }
            if (AUTO_HTTPS_OFF.matcher(line).find()) {
                violations.add("auto_https off: " + line);
            }
        }
        return violations;
    }
}
