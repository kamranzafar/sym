package org.kamranzafar.sym.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The suite on the {@code aws} profile with the cache, against Floci for DynamoDB, Kafka standing in for MSK, and
 * Valkey standing in for ElastiCache. The tables are created as the infrastructure would create them.
 */
@Testcontainers(disabledWithoutDocker = true)
class AwsEmulatorIT extends SymSuite {

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>("floci/floci:2.2.0")
            .withExposedPorts(4566)
            .waitingFor(Wait.forListeningPort());
    @Container
    static final KafkaContainer KAFKA = SharedContainers.kafka();
    @Container
    static final GenericContainer<?> VALKEY = SharedContainers.valkey();

    private static InProcessService service;

    @BeforeAll
    static void start() throws Exception {
        URI endpoint = URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566));
        createTables(endpoint);
        SharedContainers.createTopic(KAFKA);

        Map<String, String> properties = new LinkedHashMap<>(SharedContainers.properties(KAFKA, VALKEY));
        properties.put("AWS_REGION", "us-east-1");
        properties.put("halyx.providers.aws.endpoint", endpoint.toString());
        properties.put("halyx.providers.aws.credentials.type", "static");
        properties.put("halyx.providers.aws.credentials.id", "test");
        properties.put("halyx.providers.aws.credentials.secret", "test");
        service = new InProcessService(List.of("aws", "cache"), properties);
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
        return new Expectations("aws", true, Duration.ofSeconds(60));
    }

    /** Both tables, with string pk and sk keys, as the infrastructure would create them. */
    private static void createTables(URI endpoint) {
        try (DynamoDbClient dynamo = DynamoDbClient.builder().region(Region.US_EAST_1).endpointOverride(endpoint)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            for (String table : List.of("sym-messages", "sym-hashtags")) {
                dynamo.createTable(b -> b.tableName(table).billingMode(BillingMode.PAY_PER_REQUEST)
                        .attributeDefinitions(
                                AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                                AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                        .keySchema(
                                KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                                KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()));
            }
        }
    }
}
