package volkovandr.hauptbuch;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Wires the singleton {@link HauptbuchPostgres} container into the Spring test context as a
 * {@code @ServiceConnection}, so Boot configures the datasource (and Flyway) against it
 * automatically.
 *
 * <p>Spring-wired SQL-logic tests {@code @Import} this so they can autowire the real repositories
 * and exercise the SQL that lives in production code. The raw-JDBC tests in this suite (the
 * TDD-ahead invariant / running-balance SQL that no repository hosts yet) build the schema
 * themselves via {@link SqlLogicSchema} and do not use this configuration.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgresContainer() {
    return HauptbuchPostgres.INSTANCE;
  }
}
