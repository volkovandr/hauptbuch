package volkovandr.hauptbuch;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton Testcontainers Postgres for the SQL-logic suite.
 *
 * <p>Identical container config to the integration suite's holder by design: with {@code
 * testcontainers.reuse.enable=true} the reuse key matches, so both suites share one daemon
 * container (plan §1.5/§15). This suite exercises logic that lives <em>in</em> SQL — window
 * functions, {@code generate_series}, the conditional sum-to-zero invariant — which is exactly why
 * it runs on real Postgres, not H2.
 */
final class HauptbuchPostgres {

  static final PostgreSQLContainer INSTANCE =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).withReuse(true);

  static {
    INSTANCE.start();
  }

  private HauptbuchPostgres() {}
}
