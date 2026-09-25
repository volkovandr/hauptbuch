package volkovandr.hauptbuch;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton Testcontainers Postgres for the browser suite.
 *
 * <p><strong>Reuse is deliberately off</strong> (matching the other suites' holders): the app under
 * test runs on a real port and commits every row it writes, so this suite owns its own container,
 * torn down at JVM exit, and no row can cross into another suite.
 */
final class HauptbuchPostgres {

  static final PostgreSQLContainer INSTANCE =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).withReuse(false);

  static {
    INSTANCE.start();
  }

  private HauptbuchPostgres() {}
}
