package ru.tinvest.trainer.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ViewFormatTest {

    @Test
    void blindLabelsScalePastTwentySixPapers() {
        assertThat(ViewFormat.label(0)).isEqualTo("Бумага A");
        assertThat(ViewFormat.label(4)).isEqualTo("Бумага E");
        assertThat(ViewFormat.label(25)).isEqualTo("Бумага Z");
        assertThat(ViewFormat.label(26)).isEqualTo("Бумага AA");
        assertThat(ViewFormat.label(27)).isEqualTo("Бумага AB");
        assertThat(ViewFormat.label(51)).isEqualTo("Бумага AZ");
        assertThat(ViewFormat.label(52)).isEqualTo("Бумага BA");
        assertThat(ViewFormat.label(701)).isEqualTo("Бумага ZZ");
        assertThat(ViewFormat.label(702)).isEqualTo("Бумага AAA");
        assertThatThrownBy(() -> ViewFormat.label(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
