package ru.tinvest.trainer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

    private static final Set<String> EXPECTED_MODULES = Set.of(
            "api", "identity", "config", "worker", "marketdata", "workshop", "sessions", "simulation", "analysis");

    @Test
    void verifiesModuleBoundaries() {
        ApplicationModules modules = ApplicationModules.of(TrainerApplication.class);
        modules.verify();

        Set<String> actualModules = modules.stream()
                .map(ApplicationModule::getIdentifier)
                .map(Object::toString)
                .collect(Collectors.toSet());

        assertThat(actualModules).containsExactlyInAnyOrderElementsOf(EXPECTED_MODULES);
    }
}
