package volkovandr.hauptbuch;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * SQL-logic tier (plan §1.5): the sample green test, proving the suite can run
 * Postgres-only SQL against the shared container.
 *
 * <p>It deliberately uses {@code generate_series} and a windowed running {@code sum} —
 * the very constructs the running-balance query will rely on (stage 3) and the
 * reason this suite must run on real Postgres rather than H2 (tech-stack §2.5).
 */
class WindowFunctionSmokeTest {

	@Test
	void runningSumOverGeneratedSeriesWorks() throws Exception {
		List<Long> runningTotals = new ArrayList<>();

		try (Connection conn = java.sql.DriverManager.getConnection(
				HauptbuchPostgres.INSTANCE.getJdbcUrl(),
				HauptbuchPostgres.INSTANCE.getUsername(),
				HauptbuchPostgres.INSTANCE.getPassword());
				Statement stmt = conn.createStatement();
				ResultSet rs = stmt.executeQuery(
						"select sum(n) over (order by n) as running "
								+ "from generate_series(1, 4) as n")) {
			while (rs.next()) {
				runningTotals.add(rs.getLong("running"));
			}
		}

		// 1, 1+2, 1+2+3, 1+2+3+4
		assertThat(runningTotals).containsExactly(1L, 3L, 6L, 10L);
	}
}
