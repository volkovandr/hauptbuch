package volkovandr.hauptbuch.backup;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * Reads the connection details {@code pg_dump} needs off the running datasource.
 *
 * <p>Deliberately not read from {@code spring.datasource.*} properties: with Testcontainers'
 * {@code @ServiceConnection} the datasource is configured programmatically and those properties are
 * never set, so a property-based resolver would work in production and fail in the integration
 * tier. The pool is the one place that knows the truth in every environment.
 */
@Component
class DatabaseTargetResolver {

  private final DataSource dataSource;

  DatabaseTargetResolver(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * The current connection target, including the password to hand the subprocess.
   *
   * @throws BackupFailedException if the datasource cannot be described
   */
  // PMD.CloseResource: the DataSource is a container-managed singleton that this class borrows,
  // never owns. Closing it would shut the connection pool the whole app runs on.
  @SuppressWarnings("PMD.CloseResource")
  DatabaseTarget resolve() {
    if (dataSource instanceof HikariDataSource hikari) {
      return DatabaseTarget.fromJdbcUrl(
          hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword());
    }
    return fromMetaData();
  }

  /**
   * Fallback for a non-Hikari pool: the JDBC metadata gives the URL and user but no password, which
   * only works where the server does not ask for one (a local trust/peer connection).
   */
  private DatabaseTarget fromMetaData() {
    try (Connection connection = dataSource.getConnection()) {
      return DatabaseTarget.fromJdbcUrl(
          connection.getMetaData().getURL(), connection.getMetaData().getUserName(), null);
    } catch (SQLException e) {
      throw new BackupFailedException("Could not read the database connection details.", e);
    }
  }
}
