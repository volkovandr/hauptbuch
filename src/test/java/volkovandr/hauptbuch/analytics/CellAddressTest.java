package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tier (CLAUDE.md §6): {@link CellAddress}'s one-value token — the {@code cell} a Report
 * table's figure submits to its drill-down (reporting.md §12). Resolving an address against a real
 * grid is {@code ReportDrillDownSqlLogicTest}'s job.
 */
class CellAddressTest {

  @Test
  void bodyCellTokenRoundTripsNestedKeys() {
    CellAddress nested = new CellAddress("personal|person:3", "2026-01|2026-01-05", 2);

    assertThat(nested.token()).isEqualTo("2/personal|person:3/2026-01|2026-01-05");
    assertThat(CellAddress.parse(nested.token())).isEqualTo(nested);
  }

  @Test
  void totalsMissingKeysTravelAsEmpty() {
    assertThat(CellAddress.parse("0/7/")).isEqualTo(new CellAddress("7", null, 0));
    assertThat(CellAddress.parse("1//2026-01")).isEqualTo(new CellAddress(null, "2026-01", 1));
    assertThat(CellAddress.parse("0//")).isEqualTo(new CellAddress(null, null, 0));
  }

  @Test
  void rejectsWhatIsNotAnAddress() {
    assertThatThrownBy(() -> CellAddress.parse("not-a-cell"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CellAddress.parse("x/1/2"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CellAddress.parse("-1/1/2"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
