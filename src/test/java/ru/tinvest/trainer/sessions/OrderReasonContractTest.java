package ru.tinvest.trainer.sessions;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import ru.tinvest.trainer.worker.WorkerTypes.RejectCode;

/** {@link RejectCode} совпадает с {@code OrderReason} в {@code api/openapi.yaml} и корректно форматируется. */
class OrderReasonContractTest {

    @Test
    void orderReasonMatchesContractAndIncludesPositionClosed() throws IOException {
        String yaml = Files.readString(Path.of("api/openapi.yaml")).replace("\r\n", "\n");
        Matcher m = Pattern.compile("(?m)^    OrderReason:\n(?:      .*\n)*?      enum:\n((?:        - .*\n)+)")
                .matcher(yaml);
        assertThat(m.find()).as("OrderReason в api/openapi.yaml").isTrue();
        List<String> contractReasons =
                m.group(1).lines().map(l -> l.replace("- ", "").trim()).toList();
        List<String> javaReasons =
                Arrays.stream(RejectCode.values()).map(Enum::name).toList();

        assertThat(contractReasons).containsExactlyInAnyOrderElementsOf(javaReasons);
        assertThat(contractReasons).contains("POSITION_CLOSED");
        assertThat(ViewFormat.orderReason(RejectCode.POSITION_CLOSED)).isEqualTo("POSITION_CLOSED");
        assertThat(ViewFormat.orderReason(null)).isNull();
        assertThat(ViewFormat.rejectReason(RejectCode.POSITION_CLOSED)).isEqualTo("INVALID_PARAMS");
    }
}
