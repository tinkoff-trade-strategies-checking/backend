package ru.tinvest.trainer.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import ru.tinvest.trainer.marketdata.LaunchRejectReason;

/** Коды ошибок: enum {@link ErrorCode}, контракт {@code ProblemCode} и код не расходятся. */
class ErrorCodeContractTest {

    private static final Path MAIN = Path.of("src/main/java");

    @Test
    void contractEnumMatchesErrorCode() throws IOException {
        String yaml = Files.readString(Path.of("api/openapi.yaml"));
        Matcher block = Pattern.compile("(?m)^    ProblemCode:\n(?:      .*\n)*?      enum:\n((?:        - .*\n)+)")
                .matcher(yaml);
        assertThat(block.find()).as("ProblemCode в api/openapi.yaml").isTrue();
        List<String> contract =
                block.group(1).lines().map(l -> l.replace("- ", "").trim()).toList();
        assertThat(contract)
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
    }

    @Test
    void everyLaunchRejectReasonIsAnErrorCode() {
        for (LaunchRejectReason reason : LaunchRejectReason.values()) {
            assertThat(ErrorCode.valueOf(reason.name())).hasToString(reason.name());
        }
    }

    @Test
    void codesAreNotWrittenAsStringLiterals() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            List<String> offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("ErrorCode.java"))
                    .flatMap(p -> {
                        try {
                            return Files.readAllLines(p).stream()
                                    .filter(l -> l.contains("\"code\"") && !l.contains("ErrorCode"))
                                    .map(l -> p.getFileName() + ": " + l.trim());
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .toList();
            assertThat(offenders)
                    .as("код ошибки задаётся только через ErrorCode")
                    .isEmpty();
        }
    }
}
