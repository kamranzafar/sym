package org.kamranzafar.sym.e2e;

import java.time.Duration;

/**
 * What a deployment is expected to be.
 *
 * @param cloud    the active cloud profile
 * @param cache    whether the {@code cache} feature is on
 * @param patience how long the stream consumer may take to index a message
 */
public record Expectations(String cloud, boolean cache, Duration patience) {
}
