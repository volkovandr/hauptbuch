package volkovandr.hauptbuch;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.locks.ReentrantLock;
import org.flywaydb.core.Flyway;

/**
 * Builds the real schema for the SQL-logic suite. This suite runs raw JDBC against the shared
 * container (no Spring Boot autoconfiguration), so it applies the Flyway migrations itself — the
 * same {@code classpath:db/migration} set the app and the integration suite use, so the SQL under
 * test runs against the production schema, not a hand-rolled one.
 *
 * <p>{@code migrate()} is idempotent (Flyway's schema history skips already-applied versions), so
 * every test class can call {@link #ensureMigrated()} cheaply; only the first does real work.
 */
public final class SqlLogicSchema {

  private static final ReentrantLock MIGRATION_LOCK = new ReentrantLock();
  private static boolean migrated;

  private SqlLogicSchema() {}

  /** Apply the Flyway migrations to the shared container once; a no-op on subsequent calls. */
  public static void ensureMigrated() {
    MIGRATION_LOCK.lock();
    try {
      if (migrated) {
        return;
      }
      Flyway.configure()
          .dataSource(
              HauptbuchPostgres.INSTANCE.getJdbcUrl(),
              HauptbuchPostgres.INSTANCE.getUsername(),
              HauptbuchPostgres.INSTANCE.getPassword())
          .locations("classpath:db/migration")
          .load()
          .migrate();
      migrated = true;
    } finally {
      MIGRATION_LOCK.unlock();
    }
  }

  /**
   * A fresh connection to the migrated container, with autocommit off so a test can wrap its
   * crafted data in a transaction and roll it back — keeping the reused container clean between
   * tests.
   */
  public static Connection connection() throws SQLException {
    ensureMigrated();
    Connection conn =
        java.sql.DriverManager.getConnection(
            HauptbuchPostgres.INSTANCE.getJdbcUrl(),
            HauptbuchPostgres.INSTANCE.getUsername(),
            HauptbuchPostgres.INSTANCE.getPassword());
    conn.setAutoCommit(false);
    return conn;
  }
}
