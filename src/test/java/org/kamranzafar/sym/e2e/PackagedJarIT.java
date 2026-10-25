package org.kamranzafar.sym.e2e;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The built jar started with {@code java -jar} on the {@code local} profile: catches what only the test classpath had. */
class PackagedJarIT {

    @Test
    void startsAndTakesAMessage() throws Exception {
        Path jar;
        try (Stream<Path> files = Files.list(Path.of("target"))) {
            jar = files.filter(p -> p.toString().endsWith(".jar") && !p.toString().endsWith("-plain.jar"))
                    .findFirst().orElseThrow();
        }
        int port = freePort();
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar.toString(), "--server.port=" + port, "--spring.profiles.active=local,cache")
                .inheritIO().start();
        try {
            SymClient client = new SymClient(URI.create("http://localhost:" + port));
            awaitHealthy(client);

            String messageId = client.postMessage("jar_test", "from the jar #packaged");

            assertThat(client.get("/users/jar_test/messages/" + messageId).status()).isEqualTo(200);
        } finally {
            process.destroy();
            process.waitFor(30, TimeUnit.SECONDS);
        }
    }

    private static void awaitHealthy(SymClient client) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(60);
        while (true) {
            try {
                if (client.get("/actuator/health").status() == 200) {
                    return;
                }
            } catch (RuntimeException notListeningYet) {
                // keep waiting
            }
            assertThat(Instant.now()).as("the jar didn't become healthy in 60s").isBefore(deadline);
            Thread.sleep(500);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
