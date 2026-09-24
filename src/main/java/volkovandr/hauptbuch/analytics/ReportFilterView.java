package volkovandr.hauptbuch.analytics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * The Filters group's own view (reporting.md §11a.5, plan stage d3-4): one fixed section per {@link
 * FilterField} — Category, Account, Tag (hierarchy pickers, {@code filter-groups.js}'s node mode),
 * Payee (a checkbox list or a regex, by operator), Person, Currency, Account type, reconciliation
 * (flat multi-selects) and note text (a single substring). Built by {@link
 * ReportFilterViewAssembler}, which owns the DB reads this class does not need — everything here is
 * pure assembly from an already-resolved {@link ReportSpec} and the candidate rows the assembler
 * fetched, so the node/option row logic is unit-testable without a container.
 */
final class ReportFilterView {

  private ReportFilterView() {}

  /** A hierarchy candidate, normalized from {@code AccountNode}/{@code TagNode} alike. */
  record Candidate(long id, Long parentId, String label, int depth) {}

  /**
   * One row of a hierarchy section's tree (§11a.5's node-storage rule): {@code ticked} is true for
   * an explicitly-ticked node <em>or</em> one covered by a ticked ancestor; {@code disabled} is
   * true only for the latter — ticking {@code Food} stores {@code Food} alone, so its descendants
   * render ticked and fixed, never contributing their own id.
   *
   * @param ancestors every ancestor's own {@code value}, space-separated outermost first — what
   *     {@code filter-groups.js}'s node mode reads client-side to re-derive the same cascade after
   *     an interactive tick, before Apply
   */
  record NodeRow(
      String value, String label, int depth, boolean ticked, boolean disabled, String ancestors) {}

  /**
   * One row of a flat multi-select section (Payee, Person, Currency, Account type, reconciliation).
   */
  record OptionRow(String value, String label, boolean ticked) {}

  /**
   * A hierarchy section: Category, Account or Tag.
   *
   * <p>Every section's {@code filtered} says whether the spec has a filter on its field — the only
   * time its Reset control shows (reporting issue 21), which resubmits {@code otherParams} alone.
   */
  record HierarchySection(
      String label,
      List<NodeRow> nodes,
      boolean transactionLevel,
      boolean filtered,
      MultiValueMap<String, String> otherParams) {}

  /**
   * Payee: a checkbox list (read when the operator is {@code IS_ONE_OF}) and a regex field (read
   * when it is {@code MATCHES}) — both always rendered, a CSS-only radio switch between them, same
   * idiom as the date-range endpoint fields.
   */
  record PayeeSection(
      List<OptionRow> options,
      boolean matchesOperator,
      String regex,
      boolean filtered,
      MultiValueMap<String, String> otherParams) {}

  /**
   * Person, Currency, Account type, reconciliation: a flat multi-select, most with a reading
   * switch.
   */
  record OptionSection(
      String label,
      List<OptionRow> options,
      boolean showLevelSwitch,
      boolean transactionLevel,
      boolean filtered,
      MultiValueMap<String, String> otherParams) {}

  /**
   * Note text: a single {@code CONTAINS} substring, no reading switch (§6.2's second exception).
   */
  record NoteSection(String value, boolean filtered, MultiValueMap<String, String> otherParams) {}

  record View(
      HierarchySection category,
      HierarchySection account,
      HierarchySection tag,
      PayeeSection payee,
      OptionSection person,
      OptionSection currency,
      OptionSection accountType,
      OptionSection reconciliation,
      NoteSection note) {}

  /**
   * Builds a hierarchy section's tree rows from its full candidate list and the field's own current
   * filter values, if any — every candidate renders (node mode has no "hide the rest" concept,
   * §11a.5).
   */
  static List<NodeRow> nodeRows(List<Candidate> candidates, List<String> tickedValues) {
    Set<String> ticked = Set.copyOf(tickedValues);
    Map<Long, Long> parentOf = new HashMap<>();
    for (Candidate c : candidates) {
      parentOf.put(c.id(), c.parentId());
    }
    List<NodeRow> rows = new ArrayList<>();
    for (Candidate c : candidates) {
      List<Long> ancestorIds = ancestorsOf(c.id(), parentOf);
      boolean ancestorTicked = ancestorIds.stream().map(String::valueOf).anyMatch(ticked::contains);
      boolean explicitlyTicked = ticked.contains(String.valueOf(c.id()));
      String ancestors = ancestorIds.stream().map(String::valueOf).collect(Collectors.joining(" "));
      rows.add(
          new NodeRow(
              String.valueOf(c.id()),
              c.label(),
              c.depth(),
              explicitlyTicked || ancestorTicked,
              ancestorTicked,
              ancestors));
    }
    return rows;
  }

  /** {@code id}'s ancestor chain, outermost first, walked via {@code parentOf}. */
  private static List<Long> ancestorsOf(long id, Map<Long, Long> parentOf) {
    List<Long> ancestors = new ArrayList<>();
    Long parent = parentOf.get(id);
    while (parent != null) {
      ancestors.add(0, parent);
      parent = parentOf.get(parent);
    }
    return ancestors;
  }

  /**
   * Builds a flat multi-select section's option rows, ticking exactly the current filter values.
   */
  static List<OptionRow> optionRows(
      List<String> values, List<String> labels, List<String> tickedValues) {
    Set<String> ticked = Set.copyOf(tickedValues);
    List<OptionRow> rows = new ArrayList<>();
    for (int i = 0; i < values.size(); i++) {
      rows.add(new OptionRow(values.get(i), labels.get(i), ticked.contains(values.get(i))));
    }
    return rows;
  }

  /**
   * {@code all} minus exactly {@code field}'s own contribution — its one {@code filterField} entry
   * (the others, if any, are kept, since {@code filterField} is shared by every active field) and
   * its {@code filter.<field>.*} keys. What each section's own {@code <form>} carries as hidden
   * fields for every field it does not itself own, mirroring {@link ReportSettingsView}'s own
   * {@code without} helper.
   */
  static MultiValueMap<String, String> withoutFilter(
      MultiValueMap<String, String> all, FilterField field) {
    MultiValueMap<String, String> copy = new LinkedMultiValueMap<>(all);
    List<String> fields = copy.get("filterField");
    if (fields != null) {
      List<String> kept = fields.stream().filter(f -> !f.equals(field.name())).toList();
      if (kept.isEmpty()) {
        copy.remove("filterField");
      } else {
        copy.put("filterField", new ArrayList<>(kept));
      }
    }
    String prefix = "filter." + field.name() + ".";
    copy.remove(prefix + "level");
    copy.remove(prefix + "op");
    copy.remove(prefix + "value");
    copy.remove(prefix + "matches");
    return copy;
  }
}
