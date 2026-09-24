package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Unit tier (CLAUDE.md §6): {@link RowToggle}'s per-row URL (reporting.md §9.1, issue 02) — a saved
 * Report's own page posts the node to its persisting endpoint; every other editor page re-GETs
 * itself with the whole current state plus the toggled expansion set, which is never persisted —
 * and how that set is encoded as query parameters.
 */
class RowToggleTest {

  @Test
  void persistedToggleOnlyPostsTheNodeToTheReportsOwnEndpoint() {
    RowToggle toggle = RowToggle.persisted(42L);

    assertThat(toggle.persists()).isTrue();
    assertThat(toggle.urlFor("1|10", Set.of())).isEqualTo("/reports/42/expand?node=1%7C10");
  }

  @Test
  void ephemeralToggleAddsAnUnexpandedNodeAndCarriesTheRestOfTheState() {
    MultiValueMap<String, String> carried = new LinkedMultiValueMap<>();
    carried.add("rows", "CATEGORY");
    RowToggle toggle = RowToggle.ephemeral("/reports/new", carried, Set.of("1"));

    MultiValueMap<String, String> query = queryOf(toggle.urlFor("2", Set.of()));

    assertThat(toggle.persists()).isFalse();
    assertThat(query.getFirst("rows")).isEqualTo("CATEGORY");
    assertThat(RowToggle.expandedKeysFrom(query).orElseThrow()).containsExactlyInAnyOrder("1", "2");
  }

  @Test
  void ephemeralToggleRemovesAnAlreadyExpandedNode() {
    RowToggle toggle =
        RowToggle.ephemeral("/reports/new", new LinkedMultiValueMap<>(), Set.of("1", "2"));

    MultiValueMap<String, String> query = queryOf(toggle.urlFor("1", Set.of()));

    assertThat(RowToggle.expandedKeysFrom(query).orElseThrow()).containsExactly("2");
  }

  @Test
  void ephemeralToggleWithNoExplicitSetStartsFromWhatAutoPutOnScreen() {
    // Never toggled yet (auto, reporting.md §9.2): collapsing the node auto expanded must leave an
    // explicit empty set, not fall back to auto and re-expand it.
    RowToggle toggle = RowToggle.ephemeral("/reports/new", new LinkedMultiValueMap<>(), null);

    MultiValueMap<String, String> query = queryOf(toggle.urlFor("1", Set.of("1")));

    assertThat(RowToggle.expandedKeysFrom(query).orElseThrow()).isEmpty();
  }

  @Test
  void noExpansionParamMeansNoExplicitSet() {
    assertThat(RowToggle.expandedKeysFrom(new LinkedMultiValueMap<>())).isEmpty();
  }

  @Test
  void anEmptyExpansionSetRoundTripsAsEmptyNotAsAuto() {
    // Everything hand-collapsed is a real state, distinct from "never toggled" (auto, §9.2).
    MultiValueMap<String, String> params = RowToggle.expansionParams(Set.of());

    assertThat(RowToggle.expandedKeysFrom(params).orElseThrow()).isEmpty();
  }

  private static MultiValueMap<String, String> queryOf(String url) {
    MultiValueMap<String, String> decoded = new LinkedMultiValueMap<>();
    UriComponentsBuilder.fromUriString(url)
        .build(true)
        .getQueryParams()
        .forEach(
            (key, values) ->
                values.forEach(
                    value -> decoded.add(key, URLDecoder.decode(value, StandardCharsets.UTF_8))));
    return decoded;
  }
}
