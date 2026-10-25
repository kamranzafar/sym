package org.kamranzafar.sym.e2e;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.models.CosmosContainerProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The suite on the {@code azure} profile with the cache, against Floci-az for Cosmos DB, Kafka standing in for Event
 * Hubs' Kafka endpoint, and Valkey standing in for Azure Managed Redis. The containers are created as the
 * infrastructure would create them.
 */
@Testcontainers(disabledWithoutDocker = true)
class AzureEmulatorIT extends SymSuite {

    private static final int TLS_PORT = 4578;
    /** The Cosmos DB emulator's well-known key. */
    private static final String COSMOS_KEY =
            "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==";

    /** The Cosmos SDK only speaks HTTPS, so Floci-az gets a TLS port with a throwaway certificate. */
    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>("floci/floci-az:0.14.0")
            .withCopyFileToContainer(MountableFile.forHostPath(selfSignedKeystore()), "/certs/cosmos.p12")
            .withEnv("QUARKUS_HTTP_SSL_PORT", String.valueOf(TLS_PORT))
            .withEnv("QUARKUS_HTTP_SSL_CERTIFICATE_KEY_STORE_FILE", "/certs/cosmos.p12")
            .withEnv("QUARKUS_HTTP_SSL_CERTIFICATE_KEY_STORE_PASSWORD", "changeit")
            .withEnv("QUARKUS_HTTP_INSECURE_REQUESTS", "enabled")
            .withExposedPorts(4577, TLS_PORT)
            .waitingFor(Wait.forLogMessage(".*Listening on.*https.*", 1));
    @Container
    static final KafkaContainer KAFKA = SharedContainers.kafka();
    @Container
    static final GenericContainer<?> VALKEY = SharedContainers.valkey();

    private static InProcessService service;

    @BeforeAll
    static void start() throws Exception {
        // Only for the emulator's throwaway certificate: the Cosmos SDK's documented switch for emulators.
        System.setProperty("COSMOS.EMULATOR_SERVER_CERTIFICATE_VALIDATION_DISABLED", "true");
        String cosmosEndpoint = "https://localhost:" + FLOCI.getMappedPort(TLS_PORT) + "/";
        createContainers(cosmosEndpoint);
        SharedContainers.createTopic(KAFKA);

        Map<String, String> properties = new LinkedHashMap<>(SharedContainers.properties(KAFKA, VALKEY));
        properties.put("SYM_COSMOS_ENDPOINT", cosmosEndpoint);
        properties.put("SYM_CACHE_USER", "unused-without-entra-id");
        properties.put("halyx.providers.azure.credentials.type", "anonymous");
        properties.put("halyx.providers.azure.properties.azure.cosmos.key", COSMOS_KEY);
        service = new InProcessService(List.of("azure", "cache"), properties);
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
        return new Expectations("azure", true, Duration.ofSeconds(60));
    }

    /** The sym database and both containers, partitioned on /pk, as the infrastructure would create them. */
    private static void createContainers(String cosmosEndpoint) {
        try (CosmosClient cosmos = new CosmosClientBuilder().endpoint(cosmosEndpoint).key(COSMOS_KEY).gatewayMode()
                .endpointDiscoveryEnabled(false).buildClient()) {
            cosmos.createDatabaseIfNotExists("sym");
            for (String container : List.of("sym-messages", "sym-hashtags")) {
                cosmos.getDatabase("sym").createContainerIfNotExists(new CosmosContainerProperties(container, "/pk"));
            }
        }
    }

    private static Path selfSignedKeystore() {
        try {
            Path dir = Files.createTempDirectory("sym-cosmos");
            Path keystore = dir.resolve("cosmos.p12");
            String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
            Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "floci", "-keyalg", "RSA",
                    "-keysize", "2048", "-validity", "30", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                    "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass", "changeit", "-keypass", "changeit")
                    .redirectErrorStream(true).start();
            if (process.waitFor() != 0) {
                throw new IllegalStateException("keytool failed: " + new String(process.getInputStream().readAllBytes()));
            }
            keystore.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            return keystore;
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
