package ru.tinvest.trainer.sessions;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** {@link ReplayKind} совпадает с {@code kind} в {@code ReplayReady} контракта. */
class ReplayKindContractTest {

    @Test
    void kindsMatchContract() throws IOException {
        String yaml = Files.readString(Path.of("api/openapi.yaml")).replace("\r\n", "\n");
        for (ReplayKind kind : ReplayKind.values()) {
            assertThat(yaml).contains("kind: { type: string, const: " + kind.name() + " }");
            assertThat(yaml).contains(kind.name() + ": '#/components/schemas/Replay");
        }
        assertThat(yaml.lines()
                        .filter(l -> l.contains("kind: { type: string, const:"))
                        .count())
                .isEqualTo(ReplayKind.values().length);
    }
}
