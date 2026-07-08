package volkovandr.hauptbuch;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton Testcontainers Postgres for the integration suite.
 *
 * <p>The container is started once for the JVM and shared by every test class in this suite (plan
 * §1.5 / §15: one container per suite keeps the Postgres-backed loop tight).
 *
 * <p><strong>Reuse is deliberately off.</strong> A shared cross-suite container let one suite's
 * committed rows leak into another's (a dock-commit MockMvc transaction that escapes a test's
 * {@code @Transactional} rollback polluted the SQL-logic payee search). Each suite now owns a
 * container torn down at JVM exit, so no committed row can cross a suite boundary — correctness
 * over the few seconds reuse saved.
 *
 * <p>Exposed to Spring via a {@code @ServiceConnection} bean in {@link
 * TestcontainersConfiguration}; integration tests import that configuration.
 */
final class HauptbuchPostgres {

  static final PostgreSQLContainer INSTANCE =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).withReuse(false);

  static {
    INSTANCE.start();
  }

  private HauptbuchPostgres() {}
}
