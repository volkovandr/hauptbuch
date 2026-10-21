package volkovandr.hauptbuch.shared;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Switches on Spring's cache abstraction with Caffeine behind it (.scratch/performance/issues/02).
 * The manager is wrapped in {@link TransactionAwareCacheManagerProxy}, so a write inside a
 * transaction evicts only once it commits: a rollback evicts nothing, and no reader can refill the
 * old value between the eviction and the commit. Reads inside a transaction skip the cache
 * altogether ({@link CacheNames#OUTSIDE_TRANSACTION}), so they see that transaction's own writes.
 *
 * <p>One race remains by design: a reader that loaded the old rows just before a commit can put
 * them back just after the eviction. It needs two concurrent requests on a single-user app, and the
 * entry expires after {@code hauptbuch.cache.expire-after-write} at the latest.
 *
 * <p>With {@code hauptbuch.cache.enabled=false} every {@code @Cacheable} falls through to the
 * database. The test suites run that way: their transactions roll back, so cached rows would leak
 * from one test into the next.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
class CacheConfig {

  @Bean
  @ConditionalOnProperty(
      name = "hauptbuch.cache.enabled",
      havingValue = "true",
      matchIfMissing = true)
  CacheManager caffeineCacheManager(CacheProperties properties) {
    CaffeineCacheManager manager = new CaffeineCacheManager();
    manager.setCaffeine(
        Caffeine.newBuilder()
            .expireAfterWrite(properties.expireAfterWrite())
            .maximumSize(properties.maximumSize()));
    manager.setCacheNames(CacheNames.ALL);
    return new TransactionAwareCacheManagerProxy(manager);
  }

  @Bean
  @ConditionalOnProperty(name = "hauptbuch.cache.enabled", havingValue = "false")
  CacheManager noOpCacheManager() {
    return new NoOpCacheManager();
  }
}
