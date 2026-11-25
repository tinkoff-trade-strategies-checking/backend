package ru.tinvest.trainer.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RunFormatTest {

    @Test
    void blindLabelsScalePastTwentySixPapers() {
        assertThat(RunFormat.label(0)).isEqualTo("Бумага A");
        assertThat(RunFormat.label(25)).isEqualTo("Бумага Z");
        assertThat(RunFormat.label(26)).isEqualTo("Бумага AA");
        assertThat(RunFormat.label(52)).isEqualTo("Бумага BA");
        assertThatThrownBy(() -> RunFormat.label(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
