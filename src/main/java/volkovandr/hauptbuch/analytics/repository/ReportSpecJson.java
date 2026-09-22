package volkovandr.hauptbuch.analytics.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import volkovandr.hauptbuch.analytics.DateRange;
import volkovandr.hauptbuch.analytics.Dimension;
import volkovandr.hauptbuch.analytics.FilterField;
import volkovandr.hauptbuch.analytics.FilterLevel;
import volkovandr.hauptbuch.analytics.FilterOperator;
import volkovandr.hauptbuch.analytics.Leg;
import volkovandr.hauptbuch.analytics.Measure;
import volkovandr.hauptbuch.analytics.MeasureKind;
import volkovandr.hauptbuch.analytics.PresentationCurrency;
import volkovandr.hauptbuch.analytics.RangeEdge;
import volkovandr.hauptbuch.analytics.RangeEndpoint;
import volkovandr.hauptbuch.analytics.RangeUnit;
import volkovandr.hauptbuch.analytics.ReportFilter;
import volkovandr.hauptbuch.analytics.ReportSpec;
import volkovandr.hauptbuch.analytics.Scope;

/**
 * {@link ReportSpec} &lt;-&gt; the {@code report.spec} jsonb column (reporting.md §14, plan stage
 * d). Hand-written rather than reflective Jackson bean (de)serialization on purpose: {@link
 * ReportSpec} and its parts carry no persistence annotations, and {@link RangeEndpoint} is a sealed
 * interface Jackson cannot decode polymorphically without them — building/reading the tree
 * explicitly keeps the domain records clean and keeps the wire shape visible in one place.
 */
final class ReportSpecJson {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String LITERAL = "LITERAL";
  private static final String RELATIVE = "RELATIVE";

  private ReportSpecJson() {}

  /** Encodes {@code spec} as a JSON document, ready for the {@code spec::jsonb} cast. */
  static String toJson(ReportSpec spec) {
    ObjectNode root = MAPPER.createObjectNode();
    root.set("rows", dimensionsNode(spec.rows()));
    root.set("columns", dimensionsNode(spec.columns()));
    root.set("series", dimensionsNode(spec.series()));
    root.set("measures", measuresNode(spec.measures()));
    root.set("scope", scopeNode(spec.scope()));
    root.set("filters", filtersNode(spec.filters()));
    root.set("range", rangeNode(spec.range()));
    root.put("rowTotals", spec.rowTotals());
    root.put("columnTotals", spec.columnTotals());
    root.put("suppressEmptyRows", spec.suppressEmptyRows());
    try {
      return MAPPER.writeValueAsString(root);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to encode a ReportSpec as JSON.", e);
    }
  }

  /**
   * Encodes a saved Report's remembered expansion state (reporting.md §9.1, plan stage e2) as a
   * JSON array of top-level node keys, ready for the {@code expanded_node_keys::jsonb} cast —
   * {@code null} stays {@code null} (no explicit state yet; every render falls back to {@code
   * auto}).
   */
  static String toNodeKeysJson(Set<String> keys) {
    if (keys == null) {
      return null;
    }
    ArrayNode array = MAPPER.createArrayNode();
    keys.forEach(array::add);
    try {
      return MAPPER.writeValueAsString(array);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to encode expanded node keys as JSON.", e);
    }
  }

  /** Decodes a {@code report.expanded_node_keys} document back into a key set, or {@code null}. */
  // PMD.ReturnEmptyCollectionRatherThanNull: null is not "no keys" here, it is "no explicit state
  // at all" (auto decides) — a real, distinct third state from an empty-but-explicit set (plan
  // stage e2, reporting.md §9.1/§9.2), so it must survive the round trip rather than collapse to
  // an empty collection.
  @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
  static Set<String> fromNodeKeysJson(String json) {
    if (json == null) {
      return null;
    }
    Set<String> keys = new LinkedHashSet<>();
    readTree(json).forEach(node -> keys.add(node.asText()));
    return keys;
  }

  /** Decodes a {@code report.spec} document back into a {@link ReportSpec}. */
  static ReportSpec fromJson(String json) {
    JsonNode root = readTree(json);
    return new ReportSpec(
        dimensionsList(root.get("rows")),
        dimensionsList(root.get("columns")),
        dimensionsList(root.get("series")),
        measuresList(root.get("measures")),
        scopeFrom(root.get("scope")),
        filtersList(root.get("filters")),
        rangeFrom(root.get("range")),
        root.get("rowTotals").asBoolean(),
        root.get("columnTotals").asBoolean(),
        root.get("suppressEmptyRows").asBoolean());
  }

  private static JsonNode readTree(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to decode a stored Report spec.", e);
    }
  }

  private static ArrayNode dimensionsNode(List<Dimension> dimensions) {
    ArrayNode array = MAPPER.createArrayNode();
    dimensions.forEach(dimension -> array.add(dimension.name()));
    return array;
  }

  private static List<Dimension> dimensionsList(JsonNode array) {
    List<Dimension> result = new ArrayList<>();
    array.forEach(node -> result.add(Dimension.valueOf(node.asText())));
    return result;
  }

  private static ArrayNode measuresNode(List<Measure> measures) {
    ArrayNode array = MAPPER.createArrayNode();
    for (Measure measure : measures) {
      ObjectNode node = MAPPER.createObjectNode();
      node.put("kind", measure.kind().name());
      node.put("currency", measure.currency() == null ? null : measure.currency().name());
      node.put("leg", measure.leg() == null ? null : measure.leg().name());
      array.add(node);
    }
    return array;
  }

  private static List<Measure> measuresList(JsonNode array) {
    List<Measure> result = new ArrayList<>();
    array.forEach(
        node ->
            result.add(
                new Measure(
                    MeasureKind.valueOf(node.get("kind").asText()),
                    enumOrNull(node.get("currency"), PresentationCurrency.class),
                    enumOrNull(node.get("leg"), Leg.class))));
    return result;
  }

  private static ObjectNode scopeNode(Scope scope) {
    ObjectNode node = MAPPER.createObjectNode();
    ArrayNode types = MAPPER.createArrayNode();
    scope.accountTypes().forEach(types::add);
    node.set("accountTypes", types);
    node.put("includeClosedAccounts", scope.includeClosedAccounts());
    node.put("includePendingReview", scope.includePendingReview());
    return node;
  }

  private static Scope scopeFrom(JsonNode node) {
    Set<String> types = new LinkedHashSet<>();
    node.get("accountTypes").forEach(n -> types.add(n.asText()));
    return new Scope(
        types,
        node.get("includeClosedAccounts").asBoolean(),
        node.get("includePendingReview").asBoolean());
  }

  private static ArrayNode filtersNode(List<ReportFilter> filters) {
    ArrayNode array = MAPPER.createArrayNode();
    for (ReportFilter filter : filters) {
      ObjectNode node = MAPPER.createObjectNode();
      node.put("field", filter.field().name());
      node.put("level", filter.level().name());
      node.put("operator", filter.operator().name());
      ArrayNode values = MAPPER.createArrayNode();
      filter.values().forEach(values::add);
      node.set("values", values);
      array.add(node);
    }
    return array;
  }

  private static List<ReportFilter> filtersList(JsonNode array) {
    List<ReportFilter> result = new ArrayList<>();
    array.forEach(
        node -> {
          List<String> values = new ArrayList<>();
          node.get("values").forEach(v -> values.add(v.asText()));
          result.add(
              new ReportFilter(
                  FilterField.valueOf(node.get("field").asText()),
                  FilterLevel.valueOf(node.get("level").asText()),
                  FilterOperator.valueOf(node.get("operator").asText()),
                  values));
        });
    return result;
  }

  private static ObjectNode rangeNode(DateRange range) {
    ObjectNode node = MAPPER.createObjectNode();
    node.set("start", endpointNode(range.start()));
    node.set("end", endpointNode(range.end()));
    return node;
  }

  private static DateRange rangeFrom(JsonNode node) {
    return new DateRange(endpointFrom(node.get("start")), endpointFrom(node.get("end")));
  }

  private static ObjectNode endpointNode(RangeEndpoint endpoint) {
    ObjectNode node = MAPPER.createObjectNode();
    if (endpoint instanceof RangeEndpoint.Literal literal) {
      node.put("type", LITERAL);
      node.put("date", literal.date().toString());
      return node;
    }
    RangeEndpoint.Relative relative = (RangeEndpoint.Relative) endpoint;
    node.put("type", RELATIVE);
    node.put("unit", relative.unit().name());
    node.put("offset", relative.offset());
    node.put("edge", relative.edge().name());
    return node;
  }

  private static RangeEndpoint endpointFrom(JsonNode node) {
    if (LITERAL.equals(node.get("type").asText())) {
      return new RangeEndpoint.Literal(LocalDate.parse(node.get("date").asText()));
    }
    return new RangeEndpoint.Relative(
        RangeUnit.valueOf(node.get("unit").asText()),
        node.get("offset").asInt(),
        RangeEdge.valueOf(node.get("edge").asText()));
  }

  private static <E extends Enum<E>> E enumOrNull(JsonNode node, Class<E> type) {
    return node == null || node.isNull() ? null : Enum.valueOf(type, node.asText());
  }
}
