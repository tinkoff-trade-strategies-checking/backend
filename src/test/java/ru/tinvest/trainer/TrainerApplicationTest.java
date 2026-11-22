package ru.tinvest.trainer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class TrainerApplicationTest {

    @Test
    void oneShotProfilesExitAfterRun() {
        assertThat(TrainerApplication.shouldExit(
                        new MockEnvironment().withProperty("spring.profiles.active", "create-user")))
                .isTrue();
        assertThat(TrainerApplication.shouldExit(
                        new MockEnvironment().withProperty("spring.profiles.active", "import")))
                .isTrue();
        assertThat(TrainerApplication.shouldExit(
                        new MockEnvironment().withProperty("spring.profiles.active", "import,local")))
                .isTrue();
    }

    @Test
    void webProfilesStayRunning() {
        assertThat(TrainerApplication.shouldExit(new MockEnvironment())).isFalse();
        assertThat(TrainerApplication.shouldExit(new MockEnvironment().withProperty("spring.profiles.active", "local")))
                .isFalse();
    }
}
