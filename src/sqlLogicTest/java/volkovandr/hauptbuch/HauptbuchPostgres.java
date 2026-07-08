package volkovandr.hauptbuch;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton Testcontainers Postgres for the SQL-logic suite.
 *
 * <p><strong>Reuse is deliberately off</strong> (matching the integration suite's holder): a shared
 * cross-suite container let another suite's committed rows leak into this one's payee-search
 * assertions. This suite owns its own container, torn down at JVM exit (plan §1.5/§15). It
 * exercises logic that lives <em>in</em> SQL — window functions, {@code generate_series}, the
 * conditional sum-to-zero invariant — which is exactly why it runs on real Postgres, not H2.
 */
final class HauptbuchPostgres {

  static final PostgreSQLContainer INSTANCE =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).withReuse(false);

  static {
    INSTANCE.start();
  }

  private HauptbuchPostgres() {}
}
