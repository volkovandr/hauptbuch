package volkovandr.hauptbuch;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Wires the singleton {@link HauptbuchPostgres} container into the Spring test
 * context as a {@code @ServiceConnection}, so Boot configures the datasource (and
 * Flyway) against it automatically. Integration tests {@code @Import} this.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer<?> postgresContainer() {
		return HauptbuchPostgres.INSTANCE;
	}
}
