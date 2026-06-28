package volkovandr.hauptbuch;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton Testcontainers Postgres for the integration suite.
 *
 * <p>The container is started once for the JVM and shared by every test class (plan §1.5 / §15:
 * reused containers keep the Postgres-backed loop tight). With {@code withReuse(true)} and {@code
 * testcontainers.reuse.enable=true} on the host, the same daemon container is even reused
 * <em>across</em> Gradle runs and across the two Postgres-backed suites, since the reuse key is the
 * container config.
 *
 * <p>Exposed to Spring via a {@code @ServiceConnection} bean in {@link
 * TestcontainersConfiguration}; integration tests import that configuration.
 */
final class HauptbuchPostgres {

  static final PostgreSQLContainer INSTANCE =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).withReuse(true);

  static {
    INSTANCE.start();
  }

  private HauptbuchPostgres() {}
}
