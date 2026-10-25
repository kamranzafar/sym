package org.kamranzafar.sym.e2e;

import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import dev.halyx.Halyx;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The suite on the {@code gcp} profile with the cache, against Floci-gcp for Firestore and Cloud Storage, Kafka
 * standing in for Managed Service for Apache Kafka, and Valkey standing in for Memorystore. The bucket is created as
 * the infrastructure would create it; Firestore collections need no creating.
 */
@Testcontainers(disabledWithoutDocker = true)
class GcpEmulatorIT extends SymSuite {

    private static final int PORT = 4588;
    private static final String PROJECT = "floci-local";

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>("floci/floci-gcp:0.10.0")
            .withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/health").forPort(PORT))
            .withStartupTimeout(Duration.ofMinutes(2));
    @Container
    static final KafkaContainer KAFKA = SharedContainers.kafka();
    @Container
    static final GenericContainer<?> VALKEY = SharedContainers.valkey();

    private static InProcessService service;

    @BeforeAll
    static void start() throws Exception {
        URI endpoint = URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(PORT));
        createBucket(endpoint);
        SharedContainers.createTopic(KAFKA);

        Map<String, String> properties = new LinkedHashMap<>(SharedContainers.properties(KAFKA, VALKEY));
        properties.put("GOOGLE_CLOUD_PROJECT", PROJECT);
        properties.put("halyx.providers.gcp.endpoint", endpoint.toString());
        properties.put("halyx.providers.gcp.credentials.type", "anonymous");
        service = new InProcessService(List.of("gcp", "cache"), properties);
    }

    @AfterAll
    static void stop() {
        if (service != null) {
            service.close();
        }
    }

    /** The images bucket, as the infrastructure would create it. */
    private static void createBucket(URI endpoint) {
        try (Halyx halyx = Halyx.builder().register("gcp", ProviderConfig.of("gcp").endpoint(endpoint)
                .credentials(Credentials.anonymous()).property("gcp.project-id", PROJECT)).build()) {
            halyx.provider("gcp").blobStore("sym-images").unwrap(Storage.class).create(BucketInfo.of("sym-images"));
        }
    }

    @Override
    protected SymClient client() {
        return service.client();
    }

    @Override
    protected Expectations expected() {
        return new Expectations("gcp", true, Duration.ofSeconds(60));
    }
}
