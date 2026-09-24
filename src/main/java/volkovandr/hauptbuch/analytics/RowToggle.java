package volkovandr.hauptbuch.analytics;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * What a Report table's expand/collapse control does (reporting.md §9.1, plan stage e2, issue 02).
 * On a saved Report's own page with no unsaved edits it is {@link #persisted}: the control posts
 * the node to {@code /reports/{id}/expand}, which remembers the new state against the Report.
 * Everywhere else on an editor page (a draft, a Preset, {@code /reports/new}) it is {@link
 * #ephemeral}: the control re-GETs the page itself with the whole current state plus the toggled
 * expansion set, exactly like a settings-strip change, and nothing is persisted. A Frame's compact
 * card has no toggle at all ({@code null}). An ephemeral set travels in {@link #EXPANDED}, which
 * every editor page's forms carry along ({@link PresetRendering#allParams}).
 *
 * @param reportId the saved Report the toggle persists against; {@code null} when ephemeral
 * @param pagePath the page's own bare URL an ephemeral toggle re-GETs
 * @param carriedParams the rest of the page's state an ephemeral toggle resubmits — the whole spec
 *     for a draft, nothing for an unedited page (so toggling alone does not make it a draft)
 * @param expandedKeys the page's explicit expansion set, or {@code null} while it is still {@code
 *     auto} (§9.2)
 */
record RowToggle(
    Long reportId,
    String pagePath,
    MultiValueMap<String, String> carriedParams,
    Set<String> expandedKeys) {

  /**
   * The query parameter an editor page's explicit row-tree expansion set travels in, one value per
   * expanded node key. It is not part of {@link ReportSpec}, so it never makes a page a draft on
   * its own ({@link ReportSpecQueryString#isPresent}).
   */
  static final String EXPANDED = "expanded";

  static RowToggle persisted(long reportId) {
    return new RowToggle(reportId, null, null, null);
  }

  static RowToggle ephemeral(
      String pagePath, MultiValueMap<String, String> carriedParams, Set<String> expandedKeys) {
    return new RowToggle(null, pagePath, carriedParams, expandedKeys);
  }

  boolean persists() {
    return reportId != null;
  }

  /**
   * The URL toggling {@code nodeKey} requests.
   *
   * @param onScreen the keys of the rows currently shown expanded — an ephemeral toggle's starting
   *     set while {@link #expandedKeys} is still {@code null}, so the first toggle flips one node
   *     of what {@code auto} actually showed rather than of an empty set
   */
  String urlFor(String nodeKey, Set<String> onScreen) {
    if (persists()) {
      return UriComponentsBuilder.fromPath("/reports/" + reportId + "/expand")
          .queryParam("node", nodeKey)
          .build()
          .encode()
          .toUriString();
    }
    Set<String> toggled = new LinkedHashSet<>(expandedKeys == null ? onScreen : expandedKeys);
    if (!toggled.remove(nodeKey)) {
      toggled.add(nodeKey);
    }
    return UriComponentsBuilder.fromPath(pagePath)
        .queryParams(carriedParams)
        .queryParams(expansionParams(toggled))
        .build()
        .encode()
        .toUriString();
  }

  /**
   * {@code keys} as {@link #EXPANDED} parameters. An empty set (every row collapsed by hand) is one
   * empty value rather than no parameter, since no parameter means {@code auto} (§9.2).
   */
  static MultiValueMap<String, String> expansionParams(Set<String> keys) {
    MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
    if (keys.isEmpty()) {
      params.add(EXPANDED, "");
    }
    keys.forEach(key -> params.add(EXPANDED, key));
    return params;
  }

  /**
   * The explicit expansion set {@code params} carries; empty when it carries none (the page renders
   * {@code auto}, or a saved Report's own remembered state).
   */
  static Optional<Set<String>> expandedKeysFrom(MultiValueMap<String, String> params) {
    List<String> values = params.get(EXPANDED);
    if (values == null) {
      return Optional.empty();
    }
    Set<String> keys = new LinkedHashSet<>();
    values.stream().filter(value -> !value.isEmpty()).forEach(keys::add);
    return Optional.of(keys);
  }
}
