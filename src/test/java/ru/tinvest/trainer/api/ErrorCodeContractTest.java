package ru.tinvest.trainer.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import ru.tinvest.trainer.marketdata.LaunchRejectReason;

/** Коды ошибок: enum {@link ErrorCode}, контракт ({@code ProblemCode}, {@code LaunchRejectReason}) и код не расходятся. */
class ErrorCodeContractTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static String yaml() throws IOException {
        return Files.readString(Path.of("api/openapi.yaml")).replace("\r\n", "\n");
    }

    private static List<String> names(Object[] values) {
        return Arrays.stream(values).map(v -> ((Enum<?>) v).name()).toList();
    }

    @Test
    void contractEnumMatchesErrorCode() throws IOException {
        assertThat(yamlList(yaml(), "ProblemCode")).containsExactlyInAnyOrderElementsOf(names(ErrorCode.values()));
    }

    @Test
    void launchRejectReasonMatchesContractAndIsSubsetOfProblemCode() throws IOException {
        Matcher m = Pattern.compile("(?m)^    LaunchRejectReason:\n(?:      .*\n)*?      enum: \\[(.*)\\]")
                .matcher(yaml());
        assertThat(m.find()).as("LaunchRejectReason в api/openapi.yaml").isTrue();
        List<String> reasons =
                Arrays.stream(m.group(1).split(",")).map(String::trim).toList();
        assertThat(reasons).containsExactlyInAnyOrderElementsOf(names(LaunchRejectReason.values()));
        assertThat(yamlList(yaml(), "ProblemCode")).containsAll(reasons);
    }

    @Test
    void problemRequiresCode() throws IOException {
        assertThat(yaml())
                .containsPattern("(?m)^    Problem:\n(?:      .*\n)*?      required: \\[type, title, status, code\\]");
    }

    @Test
    void everyErrorCodeIsUsed() throws IOException {
        String sources = mainSources();
        List<String> launch = names(LaunchRejectReason.values());
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(launch.contains(code.name()) || sources.contains("ErrorCode." + code.name()))
                    .as("ErrorCode.%s нигде не используется", code)
                    .isTrue();
        }
    }

    @Test
    void codesAreNotWrittenAsStringLiterals() throws IOException {
        List<String> offenders = mainSources()
                .lines()
                .filter(l -> l.contains("\"code\"") && !l.contains("ErrorCode.") && !l.contains("code.name()"))
                .map(String::trim)
                .toList();
        assertThat(offenders).as("код ошибки задаётся только через ErrorCode").isEmpty();
    }

    private static List<String> yamlList(String yaml, String schema) {
        Matcher m = Pattern.compile("(?m)^    " + schema + ":\n(?:      .*\n)*?      enum:\n((?:        - .*\n)+)")
                .matcher(yaml);
        assertThat(m.find()).as(schema + " в api/openapi.yaml").isTrue();
        return m.group(1).lines().map(l -> l.replace("- ", "").trim()).toList();
    }

    /** Все исходники main, кроме {@code ErrorCode.java}. */
    private static String mainSources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("ErrorCode.java"))
                    .map(p -> {
                        try {
                            return Files.readString(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .reduce("", (a, b) -> a + "\n" + b);
        }
    }
}
