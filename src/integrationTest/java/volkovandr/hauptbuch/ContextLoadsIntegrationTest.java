package volkovandr.hauptbuch;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Integration tier (plan §1.5): the application context boots against a real Testcontainers
 * Postgres. This is the sample green test that proves the suite's plumbing — container,
 * {@code @ServiceConnection}, datasource — works end to end. Stage 3 adds Flyway-migration and
 * repository round-trip tests alongside it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ContextLoadsIntegrationTest {

  @Autowired DataSource dataSource;

  @Test
  void talksToRealPostgres() throws SQLException {
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("select 1")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isEqualTo(1);
    }
  }
}
