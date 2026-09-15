package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Unit tier (CLAUDE.md §6): {@link RangeEndpointLabelController}'s pure date arithmetic — no DB
 * dependency, so instantiated directly rather than through {@code MockMvc}. Resolves against
 * "today", so the assertions check shape (a literal date round-trips exactly; a relative one
 * resolves to a real, non-blank label) rather than a fixed date.
 */
class RangeEndpointLabelControllerTest {

  private final RangeEndpointLabelController controller = new RangeEndpointLabelController();

  private static MultiValueMap<String, String> params(String prefix, String... keyValuePairs) {
    MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
    for (int i = 0; i < keyValuePairs.length; i += 2) {
      params.add(prefix + keyValuePairs[i], keyValuePairs[i + 1]);
    }
    return params;
  }

  @Test
  void resolvesLiteralDateVerbatim() {
    String label =
        controller.resolve(params("rangeStart.", "type", "LITERAL", "date", "2026-06-01"));

    assertThat(label).isEqualTo("= 01.06.2026");
  }

  @Test
  void resolvesRelativeEndpointAgainstToday() {
    String label =
        controller.resolve(
            params("rangeEnd.", "type", "RELATIVE", "unit", "DAY", "offset", "0", "edge", "START"));

    assertThat(label).matches("= \\d{2}\\.\\d{2}\\.\\d{4}");
  }

  @Test
  void blankOnAnIncompleteLiteralEdit() {
    String label = controller.resolve(params("rangeStart.", "type", "LITERAL"));

    assertThat(label).isEmpty();
  }

  @Test
  void blankOnMalformedLiteralDate() {
    String label =
        controller.resolve(params("rangeStart.", "type", "LITERAL", "date", "not-a-date"));

    assertThat(label).isEmpty();
  }

  @Test
  void blankOnAnIncompleteRelativeEdit() {
    String label = controller.resolve(params("rangeEnd.", "type", "RELATIVE", "unit", "MONTH"));

    assertThat(label).isEmpty();
  }

  @Test
  void blankOnMalformedRelativeUnit() {
    String label =
        controller.resolve(
            params(
                "rangeEnd.",
                "type",
                "RELATIVE",
                "unit",
                "NOT_A_UNIT",
                "offset",
                "0",
                "edge",
                "START"));

    assertThat(label).isEmpty();
  }
}
