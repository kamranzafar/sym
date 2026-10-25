package org.kamranzafar.sym.e2e;

import org.kamranzafar.sym.SymApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The service started in this JVM on a free port, as {@code java -jar} would start it. Properties go in as
 * command-line arguments, as a deployment's environment variables would.
 */
final class InProcessService implements AutoCloseable {

    private final ConfigurableApplicationContext context;
    private final SymClient client;

    InProcessService(List<String> profiles, Map<String, String> properties) {
        List<String> args = new ArrayList<>(List.of("--server.port=0",
                "--spring.profiles.active=" + String.join(",", profiles)));
        properties.forEach((key, value) -> args.add("--" + key + "=" + value));
        context = new SpringApplicationBuilder(SymApplication.class).run(args.toArray(String[]::new));
        client = new SymClient(URI.create("http://localhost:"
                + context.getEnvironment().getRequiredProperty("local.server.port")));
    }

    SymClient client() {
        return client;
    }

    @Override
    public void close() {
        context.close();
    }
}
