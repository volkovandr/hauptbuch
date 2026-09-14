package volkovandr.hauptbuch.analytics;

import java.time.LocalDate;
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
 * <p>A {@link ReportSpec} always carries at least one measure ({@link ReportSpec}'s own
 * constructor), so the {@code measure} parameter's presence is the marker {@link #isPresent} reads
 * to decide "is this a draft at all" (§11a.1: no spec parameters means the saved Report).
 */
final class ReportSpecQueryString {

  private static final String MEASURE = "measure";
  private static final String TOKEN_SEP = "-";
  private static final String RANGE_START = "rangeStart.";
  private static final String RANGE_END = "rangeEnd.";
  private static final String LITERAL = "LITERAL";
  private static final String RELATIVE = "RELATIVE";

  private ReportSpecQueryString() {}

  /** Whether {@code params} encodes a draft spec at all. */
  static boolean isPresent(MultiValueMap<String, String> params) {
    List<String> measures = params.get(MEASURE);
    return measures != null && !measures.isEmpty();
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
        Boolean.parseBoolean(params.getFirst("suppressEmptyRows")));
  }

  private static void putDimension(
      MultiValueMap<String, String> params, String key, List<Dimension> dims) {
    if (!dims.isEmpty()) {
      params.add(key, dims.get(0).name());
    }
  }

  private static List<Dimension> dimensionList(MultiValueMap<String, String> params, String key) {
    String value = params.getFirst(key);
    return value == null ? List.of() : List.of(Dimension.valueOf(value));
  }

  private static String measureToken(Measure measure) {
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
      filter.values().forEach(v -> params.add(prefix + "value", v));
    }
  }

  private static List<ReportFilter> filtersList(MultiValueMap<String, String> params) {
    List<String> fields = params.getOrDefault("filterField", List.of());
    List<ReportFilter> result = new ArrayList<>();
    for (String fieldName : fields) {
      FilterField field = FilterField.valueOf(fieldName);
      String prefix = filterPrefix(field);
      List<String> values = params.getOrDefault(prefix + "value", List.of());
      result.add(
          new ReportFilter(
              field,
              FilterLevel.valueOf(require(params, prefix + "level")),
              FilterOperator.valueOf(require(params, prefix + "op")),
              values));
    }
    return result;
  }

  private static String filterPrefix(FilterField field) {
    return "filter." + field.name() + ".";
  }

  private static void putEndpoint(
      MultiValueMap<String, String> params, String prefix, RangeEndpoint endpoint) {
    if (endpoint instanceof RangeEndpoint.Literal literal) {
      params.add(prefix + "type", LITERAL);
      params.add(prefix + "date", literal.date().toString());
      return;
    }
    RangeEndpoint.Relative relative = (RangeEndpoint.Relative) endpoint;
    params.add(prefix + "type", RELATIVE);
    params.add(prefix + "unit", relative.unit().name());
    params.add(prefix + "offset", String.valueOf(relative.offset()));
    params.add(prefix + "edge", relative.edge().name());
  }

  private static RangeEndpoint endpointFrom(MultiValueMap<String, String> params, String prefix) {
    if (LITERAL.equals(require(params, prefix + "type"))) {
      return new RangeEndpoint.Literal(LocalDate.parse(require(params, prefix + "date")));
    }
    return new RangeEndpoint.Relative(
        RangeUnit.valueOf(require(params, prefix + "unit")),
        Integer.parseInt(require(params, prefix + "offset")),
        RangeEdge.valueOf(require(params, prefix + "edge")));
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
