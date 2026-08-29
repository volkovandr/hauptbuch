package volkovandr.hauptbuch.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tier (§1.5): decoding the JDBC URL into the host/port/database {@code pg_dump} needs as
 * arguments. Covers the shapes this app actually sees — the Pi's plain URL, dev's non-default port,
 * and the query-string-bearing URL Testcontainers hands the integration suite.
 */
class DatabaseTargetTest {

  @Test
  void parsesPlainUrl() {
    DatabaseTarget target =
        DatabaseTarget.fromJdbcUrl("jdbc:postgresql://localhost:5432/hauptbuch", "hauptbuch", "pw");

    assertThat(target.host()).isEqualTo("localhost");
    assertThat(target.port()).isEqualTo(5432);
    assertThat(target.database()).isEqualTo("hauptbuch");
    assertThat(target.username()).isEqualTo("hauptbuch");
    assertThat(target.password()).isEqualTo("pw");
  }

  @Test
  void parsesNonDefaultPort() {
    DatabaseTarget target =
        DatabaseTarget.fromJdbcUrl("jdbc:postgresql://localhost:15432/hauptbuch", "u", "p");

    assertThat(target.port()).isEqualTo(15_432);
  }

  @Test
  void defaultsPortWhenAbsent() {
    DatabaseTarget target =
        DatabaseTarget.fromJdbcUrl("jdbc:postgresql://db.local/hauptbuch", "u", "p");

    assertThat(target.host()).isEqualTo("db.local");
    assertThat(target.port()).isEqualTo(5432);
  }

  @Test
  void ignoresQueryParameters() {
    // The shape Testcontainers produces for the integration suite.
    DatabaseTarget target =
        DatabaseTarget.fromJdbcUrl(
            "jdbc:postgresql://localhost:32773/test?loggerLevel=OFF", "u", "p");

    assertThat(target.port()).isEqualTo(32_773);
    assertThat(target.database()).isEqualTo("test");
  }

  @Test
  void rejectsNonPostgresUrl() {
    assertThatThrownBy(() -> DatabaseTarget.fromJdbcUrl("jdbc:h2:mem:test", "u", "p"))
        .isInstanceOf(BackupFailedException.class)
        .hasMessageContaining("PostgreSQL");
  }

  @Test
  void rejectsUrlWithoutDatabase() {
    assertThatThrownBy(
            () -> DatabaseTarget.fromJdbcUrl("jdbc:postgresql://localhost:5432/", "u", "p"))
        .isInstanceOf(BackupFailedException.class);
  }

  @Test
  void neverRendersPasswordInToString() {
    // The dump target is logged and appears in failure messages; NFR-04 forbids a secret in a log
    // line, and a record's generated toString would print the password verbatim.
    DatabaseTarget target =
        DatabaseTarget.fromJdbcUrl(
            "jdbc:postgresql://localhost:5432/hauptbuch", "hauptbuch", "s3cr3t");

    assertThat(target.toString()).doesNotContain("s3cr3t");
    assertThat(target.toString()).contains("hauptbuch");
  }
}
