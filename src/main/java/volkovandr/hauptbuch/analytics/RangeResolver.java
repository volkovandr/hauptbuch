package volkovandr.hauptbuch.analytics;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/**
 * Resolves the start/end anchor grammar (reporting.md §8.1) against "today", and buckets a resolved
 * range into calendar months with partial-bucket labelling (§8.2). Pure date arithmetic — no
 * dependency on the book, so it is unit-tested rather than SQL-logic-tested.
 */
final class RangeResolver {

  private static final DateTimeFormatter LABEL_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  private RangeResolver() {}

  /**
   * The range's two endpoints resolved to concrete dates, {@code start <= end} not enforced here.
   */
  static ResolvedRange resolve(DateRange range, LocalDate today) {
    return new ResolvedRange(
        resolveEndpoint(range.start(), today), resolveEndpoint(range.end(), today));
  }

  /**
   * A single endpoint resolved to a date — the settings strip's "resolves to" label (plan stage d3)
   * needs this without a full {@link DateRange}.
   */
  static LocalDate resolveEndpoint(RangeEndpoint endpoint, LocalDate today) {
    if (endpoint instanceof RangeEndpoint.Literal literal) {
      return literal.date();
    }
    RangeEndpoint.Relative relative = (RangeEndpoint.Relative) endpoint;
    boolean start = relative.edge() == RangeEdge.START;
    int offset = relative.offset();
    return switch (relative.unit()) {
      case DAY -> today.plusDays(offset);
      case WEEK -> resolveWeek(today, offset, start);
      case MONTH -> resolveMonth(today, offset, start);
      case QUARTER -> resolveQuarter(today, offset, start);
      case YEAR -> resolveYear(today, offset, start);
    };
  }

  private static LocalDate resolveWeek(LocalDate today, int offset, boolean start) {
    LocalDate monday = mondayOfWeek(today).plusWeeks(offset);
    return start ? monday : monday.plusDays(6);
  }

  private static LocalDate resolveMonth(LocalDate today, int offset, boolean start) {
    YearMonth month = YearMonth.from(today).plusMonths(offset);
    return start ? month.atDay(1) : month.atEndOfMonth();
  }

  private static LocalDate resolveQuarter(LocalDate today, int offset, boolean start) {
    YearMonth quarterStartMonth = quarterStart(today).plusMonths((long) offset * 3);
    return start ? quarterStartMonth.atDay(1) : quarterStartMonth.plusMonths(2).atEndOfMonth();
  }

  private static LocalDate resolveYear(LocalDate today, int offset, boolean start) {
    int year = today.getYear() + offset;
    return start ? LocalDate.of(year, 1, 1) : LocalDate.of(year, 12, 31);
  }

  private static LocalDate mondayOfWeek(LocalDate date) {
    return date.minusDays(date.getDayOfWeek().getValue() - 1L);
  }

  private static YearMonth quarterStart(LocalDate date) {
    int quarterIndex = (date.getMonthValue() - 1) / 3;
    return YearMonth.of(date.getYear(), quarterIndex * 3 + 1);
  }

  /**
   * The settings strip's own "resolves to" label for one endpoint (reporting.md §11a.6, e.g. "=
   * 01.06.2026") — shared by the initial render ({@link ReportSettingsView}) and the live preview
   * ({@link RangeEndpointLabelController}) so the two can never format the same date differently.
   */
  static String resolvedLabel(RangeEndpoint endpoint, LocalDate today) {
    return "= " + LABEL_FORMAT.format(resolveEndpoint(endpoint, today));
  }

  /** The resolved endpoints of a {@link DateRange}. */
  record ResolvedRange(LocalDate start, LocalDate end) {}
}
