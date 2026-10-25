package org.kamranzafar.sym.e2e;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * What every cloud's emulated deployment uses beside its cloud emulator: Kafka for the stream and Valkey for the
 * cache. MSK, Event Hubs, Managed Kafka, ElastiCache, Azure Managed Redis and Memorystore speak the same protocols.
 */
final class SharedContainers {

    static final String TOPIC = "sym-message-posted";

    private SharedContainers() {
    }

    static KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka:4.2.1");
    }

    static GenericContainer<?> valkey() {
        return new GenericContainer<>("valkey/valkey:9.1.2-alpine")
                .withExposedPorts(6379)
                .waitingFor(Wait.forListeningPort());
    }

    /** The message topic, with a few partitions as a real deployment would have. */
    static void createTopic(KafkaContainer kafka) throws ExecutionException, InterruptedException {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get();
        }
    }

    /** How the service reaches the two, as the environment variables a deployment would set. */
    static Map<String, String> properties(KafkaContainer kafka, GenericContainer<?> valkey) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("SYM_KAFKA_BOOTSTRAP_SERVERS", kafka.getBootstrapServers());
        properties.put("SYM_KAFKA_AUTH", "none");
        properties.put("SYM_CACHE_ENDPOINT", "redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379));
        properties.put("SYM_CACHE_AUTH", "none");
        return properties;
    }
}
