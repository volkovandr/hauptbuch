package volkovandr.hauptbuch.backup;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Where the ledger database lives, in the form {@code pg_dump} wants it: discrete host, port and
 * database name rather than a JDBC URL.
 *
 * <p>Resolved from the running datasource rather than from configuration, so it is correct in every
 * environment — including the integration suite, where Testcontainers configures the datasource
 * programmatically and {@code spring.datasource.url} is never set as a property.
 *
 * <p>{@link #toString()} is overridden: the generated record one would print the password, and this
 * value reaches log lines and failure messages (NFR-04 — a log line never contains a secret).
 *
 * @param host the database host
 * @param port the database port
 * @param database the database name to dump
 * @param username the role to connect as
 * @param password that role's password, passed to the subprocess as {@code PGPASSWORD}
 */
record DatabaseTarget(String host, int port, String database, String username, String password) {

  private static final String JDBC_PREFIX = "jdbc:";
  private static final String POSTGRES_SCHEME = "postgresql";
  private static final int DEFAULT_PORT = 5432;

  /**
   * Decode a PostgreSQL JDBC URL. Tolerates an absent port (PostgreSQL's default) and a trailing
   * query string, which is the shape Testcontainers produces.
   *
   * @throws BackupFailedException if the URL is not a usable PostgreSQL one
   */
  static DatabaseTarget fromJdbcUrl(String jdbcUrl, String username, String password) {
    if (jdbcUrl == null || !jdbcUrl.startsWith(JDBC_PREFIX)) {
      throw new BackupFailedException("The datasource URL is not a JDBC URL: " + jdbcUrl);
    }
    URI uri = parse(jdbcUrl.substring(JDBC_PREFIX.length()));
    if (!POSTGRES_SCHEME.equals(uri.getScheme())) {
      throw new BackupFailedException(
          "Backups require a PostgreSQL datasource; this one is " + uri.getScheme() + ".");
    }
    String database = databaseOf(uri);
    int port = uri.getPort() == -1 ? DEFAULT_PORT : uri.getPort();
    return new DatabaseTarget(uri.getHost(), port, database, username, password);
  }

  private static URI parse(String withoutJdbcPrefix) {
    try {
      return new URI(withoutJdbcPrefix);
    } catch (URISyntaxException e) {
      throw new BackupFailedException("The datasource URL could not be read.", e);
    }
  }

  private static String databaseOf(URI uri) {
    String path = uri.getPath();
    if (path == null || path.length() <= 1) {
      throw new BackupFailedException("The datasource URL names no database.");
    }
    return path.substring(1);
  }

  @Override
  public String toString() {
    return "DatabaseTarget[" + username + "@" + host + ":" + port + "/" + database + "]";
  }
}
