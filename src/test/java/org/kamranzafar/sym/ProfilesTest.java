package org.kamranzafar.sym;

import dev.halyx.spring.autoconfigure.HalyxProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each cloud profile, with and without the cache, resolved and bound as a deployment would load it, without
 * creating a bean or connecting anywhere. The emulator tests override endpoints and logins, so this is what checks
 * the profiles as written: the provider types, IAM logins, no stored credentials, and the names per cloud.
 */
class ProfilesTest {

    @Configuration(proxyBeanMethods = false)
    static class NoBeans {
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws", "azure", "gcp"})
    void cloudProfileResolves(String cloud) {
        try (ConfigurableApplicationContext context = run(cloud)) {
            Environment env = context.getEnvironment();

            assertThat(env.getProperty("halyx.providers." + cloud + ".type")).isEqualTo(cloud);
            assertThat(env.getProperty("halyx.providers." + cloud + ".credentials.type")).isNull();
            assertThat(env.getProperty("halyx.stream-clusters.voices.bootstrap-servers")).isEqualTo("kafka.example:9098");
            assertThat(env.getProperty("halyx.stream-clusters.voices.auth.type")).isEqualTo("iam");
            assertThat(env.getProperty("halyx.document-stores.messages.table")).isEqualTo("sym-messages");
            assertThat(env.getProperty("halyx.document-stores.hashtags.table")).isEqualTo("sym-hashtags");
            assertThat(env.getProperty("halyx.blob-stores.images.bucket")).isEqualTo("sym-images");
            if (cloud.equals("azure")) {
                assertThat(env.getProperty("halyx.providers.azure.properties.azure.storage.account")).isEqualTo("symstorage");
            }
            assertThat(env.getProperty("halyx.cache-manager.endpoint")).isNull();
            assertThat(env.getProperty("halyx.providers.local.type")).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws", "azure", "gcp"})
    void cacheProfileLogsInWithIam(String cloud) {
        try (ConfigurableApplicationContext context = run(cloud, "cache")) {
            Environment env = context.getEnvironment();

            assertThat(env.getProperty("halyx.cache-manager.endpoint")).isEqualTo("rediss://cache.example:6379");
            assertThat(env.getProperty("halyx.cache-manager.auth.type")).isEqualTo("iam");
            assertThat(env.getProperty("halyx.cache-manager.fail-open")).isEqualTo("true");
            if (cloud.equals("aws")) {
                assertThat(env.getProperty("halyx.cache-manager.properties.aws.elasticache.cache-name")).isEqualTo("sym");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "local,cache"})
    void localProfileNeedsNoCloud(String profiles) {
        try (ConfigurableApplicationContext context = run(profiles.split(","))) {
            Environment env = context.getEnvironment();

            assertThat(env.getProperty("halyx.providers.local.type")).isEqualTo("memory");
            assertThat(env.getProperty("halyx.stream-clusters.voices.bootstrap-servers")).isEqualTo("memory:9092");
            assertThat(env.getProperty("halyx.stream-clusters.voices.auth.type")).isEqualTo("none");
        }
    }

    private static ConfigurableApplicationContext run(String... profiles) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(NoBeans.class)
                .web(WebApplicationType.NONE).profiles(profiles).run(
                        "--halyx.config-import.enabled=false",          // load nothing from a cloud, connect nowhere
                        "--SYM_KAFKA_BOOTSTRAP_SERVERS=kafka.example:9098", // what each deployment sets, with no default
                        "--SYM_CACHE_ENDPOINT=rediss://cache.example:6379",
                        "--SYM_CACHE_USER=00000000-0000-0000-0000-000000000000",
                        "--SYM_COSMOS_ENDPOINT=https://sym.documents.azure.com:443/",
                        "--SYM_STORAGE_ACCOUNT=symstorage",
                        "--GOOGLE_CLOUD_PROJECT=acme-prod");
        Binder.get(context.getEnvironment()).bind("halyx", HalyxProperties.class).get();
        return context;
    }
}
