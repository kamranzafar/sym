package org.kamranzafar.sym;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** The {@code cache} feature: {@code @Cacheable} on, backed by {@code halyx.cache-manager}. Off, lookups go straight to the store. */
@Configuration(proxyBeanMethods = false)
@Profile("cache")
@EnableCaching
class MessageCaching {
}
