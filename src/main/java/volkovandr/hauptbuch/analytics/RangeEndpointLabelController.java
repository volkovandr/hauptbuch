package volkovandr.hauptbuch.analytics;

import java.time.DateTimeException;
import java.time.LocalDate;
import org.springframework.stereotype.Controller;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The date range editor's "resolves to" label (reporting.md §11a.6): refreshed live as the operator
 * edits an endpoint's unit/offset/edge or literal date, without running a report query — this
 * endpoint touches only {@link RangeResolver}, never {@link ReportEngine}. A malformed intermediate
 * state (a date field cleared mid-edit) resolves to a blank label rather than an error, since
 * {@link volkovandr.hauptbuch.web.GlobalHtmxErrorAdvice}'s toast would be a jarring reaction to
 * routine typing.
 *
 * <p>One endpoint's own {@code <fieldset>} (fragments/report-settings.html) submits its fields
 * under its own {@code rangeStart.}/{@code rangeEnd.} prefix ({@code hx-include="closest
 * fieldset"}), so this reads whichever prefix's {@code type} key is actually present rather than a
 * fixed parameter name — the same {@link ReportSpecQueryString#RANGE_START}/{@link
 * ReportSpecQueryString#RANGE_END} prefixes and {@link ReportSpecQueryString#LITERAL} marker that
 * class itself decodes with, so the two can never drift on what "Literal" means.
 */
@Controller
class RangeEndpointLabelController {

  @GetMapping("/reports/settings/resolve-endpoint")
  @ResponseBody
  String resolve(@RequestParam MultiValueMap<String, String> params) {
    String prefix =
        params.containsKey(ReportSpecQueryString.RANGE_START + "type")
            ? ReportSpecQueryString.RANGE_START
            : ReportSpecQueryString.RANGE_END;
    String type = params.getFirst(prefix + "type");
    if (ReportSpecQueryString.LITERAL.equals(type)) {
      String date = params.getFirst(prefix + "date");
      return date == null ? "" : resolvedLabel(literalOrBlank(date));
    }
    return resolvedLabel(relativeOrBlank(params, prefix));
  }

  private static RangeEndpoint literalOrBlank(String date) {
    try {
      return new RangeEndpoint.Literal(LocalDate.parse(date));
    } catch (DateTimeException incompleteEdit) {
      return null;
    }
  }

  private static RangeEndpoint relativeOrBlank(
      MultiValueMap<String, String> params, String prefix) {
    String unit = params.getFirst(prefix + "unit");
    String offset = params.getFirst(prefix + "offset");
    String edge = params.getFirst(prefix + "edge");
    if (unit == null || offset == null || edge == null) {
      return null;
    }
    try {
      return new RangeEndpoint.Relative(
          RangeUnit.valueOf(unit), Integer.parseInt(offset), RangeEdge.valueOf(edge));
    } catch (IllegalArgumentException incompleteEdit) {
      return null;
    }
  }

  private static String resolvedLabel(RangeEndpoint endpoint) {
    return endpoint == null ? "" : RangeResolver.resolvedLabel(endpoint, LocalDate.now());
  }
}
