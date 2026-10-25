package org.kamranzafar.sym.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** The suite on {@code local} with the {@code cache} feature, on the memory provider's own cache. */
class LocalCacheDeploymentTest extends SymSuite {

    private static InProcessService service;

    @BeforeAll
    static void start() {
        service = new InProcessService(List.of("local", "cache"), Map.of());
    }

    @AfterAll
    static void stop() {
        if (service != null) {
            service.close();
        }
    }

    @Override
    protected SymClient client() {
        return service.client();
    }

    @Override
    protected Expectations expected() {
        return new Expectations("local", true, Duration.ofSeconds(20));
    }
}
