package volkovandr.hauptbuch.shared;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the reference-data caches ({@code hauptbuch.cache.*}).
 *
 * @param enabled whether caching is on; the test suites switch it off (see {@link CacheConfig})
 * @param expireAfterWrite the safety net for edits made outside the app — correctness never relies
 *     on it, every write evicts explicitly
 * @param maximumSize the most entries one cache holds
 */
@ConfigurationProperties(prefix = "hauptbuch.cache")
public record CacheProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("1h") Duration expireAfterWrite,
    @DefaultValue("1000") long maximumSize) {}
