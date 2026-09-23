package volkovandr.hauptbuch.analytics;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * {@link ReportSpec} &lt;-&gt; the query string an unsaved draft lives in (reporting.md §11a.1,
 * §14). One query parameter per field, repeated for a multi-value field — the same shape a plain
 * HTML form (rendered by the d3 settings strip) submits via {@code hx-get} with no JS-built payload
 * — rather than a single opaque blob, mirroring {@link
 * volkovandr.hauptbuch.analytics.repository.ReportSpecJson}'s field-by-field approach for the
 * {@code jsonb} column but shaped for a {@link MultiValueMap} instead of a JSON tree.
 * Percent-encoding itself is Spring MVC's job on the way in and a URL builder's job on the way out;
 * this class only ever sees already-decoded parameter values.
 *
 * <p>{@link #isPresent} reads {@code rangeStart.type}'s presence, not {@code measure}'s, to decide
 * "is this a draft at all" (§11a.1: no spec parameters means the saved Report). {@link #toParams}
 * always writes both endpoints' {@code type} unconditionally, unlike {@code measure} — a settings-
 * strip Apply with every Measures checkbox unticked would otherwise carry no {@code measure}
 * parameter at all and read as "no draft", silently reverting the whole edit (rows, scope, date
 * range, everything) back to the saved/Preset spec instead of surfacing {@link ReportSpec}'s own
 * "needs at least one measure" rejection.
 */
final class ReportSpecQueryString {

  private static final String MEASURE = "measure";
  private static final String TOKEN_SEP = "-";
  private static final String TYPE = "type";

  /** Reused by {@link RangeEndpointLabelController}, which reads one endpoint's own fields. */
  static final String RANGE_START = "rangeStart.";

  /** Reused by {@link RangeEndpointLabelController}, which reads one endpoint's own fields. */
  static final String RANGE_END = "rangeEnd.";

  /**
   * Reused by {@link RangeEndpointLabelController}, which decides Literal vs Relative the same way.
   */
  static final String LITERAL = "LITERAL";

  private static final String RELATIVE = "RELATIVE";

  private ReportSpecQueryString() {}

  /** Whether {@code params} encodes a draft spec at all. */
  static boolean isPresent(MultiValueMap<String, String> params) {
    return params.getFirst(RANGE_START + TYPE) != null;
  }

  /** Encodes {@code spec} as query parameters, ready for a URL builder or {@code hx-get}. */
  static MultiValueMap<String, String> toParams(ReportSpec spec) {
    MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
    putDimension(params, "rows", spec.rows());
    putDimension(params, "columns", spec.columns());
    putDimension(params, "series", spec.series());
    spec.measures().forEach(m -> params.add(MEASURE, measureToken(m)));
    spec.scope().accountTypes().forEach(t -> params.add("scopeType", t));
    params.add("includeClosed", String.valueOf(spec.scope().includeClosedAccounts()));
    params.add("includePending", String.valueOf(spec.scope().includePendingReview()));
    putFilters(params, spec.filters());
    putEndpoint(params, RANGE_START, spec.range().start());
    putEndpoint(params, RANGE_END, spec.range().end());
    params.add("rowTotals", String.valueOf(spec.rowTotals()));
    params.add("columnTotals", String.valueOf(spec.columnTotals()));
    params.add("suppressEmptyRows", String.valueOf(spec.suppressEmptyRows()));
    params.add("groupHeaderParents", String.valueOf(spec.groupHeaderParents()));
    params.add("dateLadder", spec.dateLadder().name());
    params.add("suppressEmptyColumns", String.valueOf(spec.suppressEmptyColumns()));
    return params;
  }

  /**
   * Decodes {@code params} into a {@link ReportSpec}; call only when {@link #isPresent} is true.
   */
  static ReportSpec fromParams(MultiValueMap<String, String> params) {
    return new ReportSpec(
        dimensionList(params, "rows"),
        dimensionList(params, "columns"),
        dimensionList(params, "series"),
        measuresList(params),
        scopeFrom(params),
        filtersList(params),
        new DateRange(endpointFrom(params, RANGE_START), endpointFrom(params, RANGE_END)),
        Boolean.parseBoolean(params.getFirst("rowTotals")),
        Boolean.parseBoolean(params.getFirst("columnTotals")),
        Boolean.parseBoolean(params.getFirst("suppressEmptyRows")),
        Boolean.parseBoolean(params.getFirst("groupHeaderParents")),
        dateLadderFrom(params),
        Boolean.parseBoolean(params.getFirst("suppressEmptyColumns")));
  }

  /**
   * A draft with no {@code dateLadder} parameter at all (a link/form predating stage e4) degrades
   * to {@link DateLadder#MONTH}, mirroring {@code groupHeaderParents}' own null-safe default above.
   */
  private static DateLadder dateLadderFrom(MultiValueMap<String, String> params) {
    String value = params.getFirst("dateLadder");
    return value == null ? DateLadder.MONTH : DateLadder.valueOf(value);
  }

  private static void putDimension(
      MultiValueMap<String, String> params, String key, List<Dimension> dims) {
    if (!dims.isEmpty()) {
      params.add(key, dims.get(0).name());
    }
  }

  private static List<Dimension> dimensionList(MultiValueMap<String, String> params, String key) {
    // A settings-strip <select> (plan stage d3) always submits its "None" option's own value, an
    // empty string, rather than omitting the parameter the way toParams does when the list is
    // empty — both must decode the same way.
    String value = params.getFirst(key);
    return (value == null || value.isEmpty()) ? List.of() : List.of(Dimension.valueOf(value));
  }

  /** The settings-strip's Measures grid (plan stage d3) needs the same token for its checkboxes. */
  static String measureToken(Measure measure) {
    return switch (measure.kind()) {
      case TURNOVER ->
          String.join(TOKEN_SEP, "TURNOVER", measure.leg().name(), measure.currency().name());
      case CLOSING_BALANCE -> String.join(TOKEN_SEP, "CLOSING_BALANCE", measure.currency().name());
      case COUNT_POSTINGS -> "COUNT_POSTINGS";
      case COUNT_TRANSACTIONS -> "COUNT_TRANSACTIONS";
    };
  }

  private static Measure measureFromToken(String token) {
    String[] parts = token.split(TOKEN_SEP);
    return switch (MeasureKind.valueOf(parts[0])) {
      case TURNOVER ->
          Measure.turnover(PresentationCurrency.valueOf(parts[2]), Leg.valueOf(parts[1]));
      case CLOSING_BALANCE -> Measure.closingBalance(PresentationCurrency.valueOf(parts[1]));
      case COUNT_POSTINGS -> Measure.countPostings();
      case COUNT_TRANSACTIONS -> Measure.countTransactions();
    };
  }

  private static List<Measure> measuresList(MultiValueMap<String, String> params) {
    List<String> tokens = params.get(MEASURE);
    List<Measure> result = new ArrayList<>();
    if (tokens != null) {
      tokens.forEach(t -> result.add(measureFromToken(t)));
    }
    return result;
  }

  private static Scope scopeFrom(MultiValueMap<String, String> params) {
    List<String> types = params.get("scopeType");
    Set<String> accountTypes = types == null ? Set.of() : new LinkedHashSet<>(types);
    return new Scope(
        accountTypes,
        Boolean.parseBoolean(params.getFirst("includeClosed")),
        Boolean.parseBoolean(params.getFirst("includePending")));
  }

  /**
   * {@code filterField} lists which fields carry a filter, in the spec's own order — {@link
   * ReportSpec#filters()} is a {@link List}, and while AND-combined filters have no semantic order
   * (§6.2), preserving it here is simpler and less surprising than canonicalizing to {@link
   * FilterField}'s declaration order.
   */
  private static void putFilters(MultiValueMap<String, String> params, List<ReportFilter> filters) {
    for (ReportFilter filter : filters) {
      params.add("filterField", filter.field().name());
      String prefix = filterPrefix(filter.field());
      params.add(prefix + "level", filter.level().name());
      params.add(prefix + "op", filter.operator().name());
      filter.values().forEach(v -> params.add(valueKey(filter.field(), filter.operator()), v));
    }
  }

  /**
   * Every field's values live under {@code value} except Payee's {@code MATCHES} regex (plan stage
   * d3-4): the settings strip's Payee section keeps both its checkbox list ({@code value}, read for
   * {@code IS_ONE_OF}) and its regex field always in the DOM — the same "both always submitted,
   * only one decoded" idiom the date-range endpoint fields already use ({@code ReportSettingsView})
   * — so the two need non-colliding parameter names or a stray empty regex field would land in the
   * same {@code value} list as the ticked payee ids.
   */
  private static String valueKey(FilterField field, FilterOperator operator) {
    String prefix = filterPrefix(field);
    return field == FilterField.PAYEE && operator == FilterOperator.MATCHES
        ? prefix + "matches"
        : prefix + "value";
  }

  /**
   * An empty values list decodes to no filter at all for that field (reporting.md §11a.5: "an empty
   * section is no filter") rather than {@link ReportFilter}'s own "needs at least one value"
   * rejection — the settings strip's per-field Apply form (plan stage d3-4) always resubmits {@code
   * filterField}/{@code level}/{@code op} for every one of the nine fixed sections regardless of
   * whether any value is ticked, so a section with nothing ticked must degrade to "not present"
   * rather than fail the whole render.
   */
  private static List<ReportFilter> filtersList(MultiValueMap<String, String> params) {
    List<String> fields = params.getOrDefault("filterField", List.of());
    List<ReportFilter> result = new ArrayList<>();
    for (String fieldName : fields) {
      FilterField field = FilterField.valueOf(fieldName);
      String prefix = filterPrefix(field);
      FilterOperator operator = FilterOperator.valueOf(require(params, prefix + "op"));
      List<String> values = params.getOrDefault(valueKey(field, operator), List.of());
      if (values.isEmpty()) {
        continue;
      }
      result.add(
          new ReportFilter(
              field, FilterLevel.valueOf(require(params, prefix + "level")), operator, values));
    }
    return result;
  }

  private static String filterPrefix(FilterField field) {
    return "filter." + field.name() + ".";
  }

  private static void putEndpoint(
      MultiValueMap<String, String> params, String prefix, RangeEndpoint endpoint) {
    if (endpoint instanceof RangeEndpoint.Literal literal) {
      params.add(prefix + TYPE, LITERAL);
      params.add(prefix + "date", literal.date().toString());
      return;
    }
    RangeEndpoint.Relative relative = (RangeEndpoint.Relative) endpoint;
    params.add(prefix + TYPE, RELATIVE);
    params.add(prefix + "unit", relative.unit().name());
    params.add(prefix + "offset", String.valueOf(relative.offset()));
    params.add(prefix + "edge", relative.edge().name());
  }

  /**
   * Just the range's own parameters, for a date-range shortcut link (plan stage d3): filling both
   * endpoints is a complete decision (reporting.md §11a.6), so a shortcut carries these alongside
   * the rest of the current state rather than the whole {@link #toParams} encoding.
   */
  static MultiValueMap<String, String> rangeParams(DateRange range) {
    MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
    putEndpoint(params, RANGE_START, range.start());
    putEndpoint(params, RANGE_END, range.end());
    return params;
  }

  private static RangeEndpoint endpointFrom(MultiValueMap<String, String> params, String prefix) {
    if (LITERAL.equals(require(params, prefix + TYPE))) {
      return literalOrToday(params.getFirst(prefix + "date"));
    }
    return new RangeEndpoint.Relative(
        RangeUnit.valueOf(require(params, prefix + "unit")),
        Integer.parseInt(require(params, prefix + "offset")),
        RangeEdge.valueOf(require(params, prefix + "edge")));
  }

  /**
   * A blank or malformed literal date — Apply submitted before the Date field was ever filled in —
   * resolves to today rather than throwing; the settings strip's own live preview ({@code
   * RangeEndpointLabelController}) already degrades the same way for the same input.
   */
  private static RangeEndpoint literalOrToday(String date) {
    if (date == null) {
      return new RangeEndpoint.Literal(LocalDate.now());
    }
    try {
      return new RangeEndpoint.Literal(LocalDate.parse(date));
    } catch (DateTimeParseException malformed) {
      return new RangeEndpoint.Literal(LocalDate.now());
    }
  }

  /** A required query parameter, malformed if absent (mirrors {@code ReportSpecJson}'s style). */
  private static String require(MultiValueMap<String, String> params, String key) {
    String value = params.getFirst(key);
    if (value == null) {
      throw new IllegalStateException("Missing report spec query parameter: " + key);
    }
    return value;
  }
}
