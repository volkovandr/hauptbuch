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
 *
 * <p><strong>{@code max_connections} is raised above the Postgres default of 100.</strong> Spring
 * caches one context per distinct test configuration, each holding its own Hikari pool of up to ten
 * connections, and every one of them points at this single container. At around ten distinct
 * configurations the suite exhausted the default and unrelated classes started failing to boot with
 * {@code FATAL: sorry, too many clients already} — a failure that looks nothing like its cause. The
 * ceiling is lifted here rather than by shrinking each pool, so the app under test keeps its real
 * pool settings.
 *
 * <p>Note that {@code withCommand} <em>replaces</em> the container's command rather than adding to
 * it, and {@code PostgreSQLContainer}'s constructor sets {@code postgres -c fsync=off}. So that
 * flag has to be repeated here — dropping it would quietly make every commit in the suite fsync to
 * disk.
 */
final class HauptbuchPostgres {

  private static final String MAX_CONNECTIONS = "300";

  static final PostgreSQLContainer INSTANCE =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
          .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=" + MAX_CONNECTIONS)
          .withReuse(false);

  static {
    INSTANCE.start();
  }

  private HauptbuchPostgres() {}
}
