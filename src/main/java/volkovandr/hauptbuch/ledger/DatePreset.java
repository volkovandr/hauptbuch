package volkovandr.hauptbuch.ledger;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.function.Function;

/**
 * The register filter's date-range quick presets (issue transaction-register-ui/12). A preset
 * button submits its {@link #param() token} as {@code datePreset}; the server works the dates out
 * from today, so each one works without JavaScript.
 *
 * <p>"Last N days" leaves the upper bound open so future-dated (scheduled) rows still show;
 * "Previous month" is the statement workflow — a bank statement covers a calendar month.
 */
public enum DatePreset {
  LAST_90_DAYS("last-90-days", "Last 90 days", today -> new DateRange(today.minusDays(89), null)),
  LAST_365_DAYS(
      "last-365-days", "Last 365 days", today -> new DateRange(today.minusDays(364), null)),
  PREVIOUS_MONTH(
      "previous-month",
      "Previous month",
      today -> {
        YearMonth previous = YearMonth.from(today).minusMonths(1);
        return new DateRange(previous.atDay(1), previous.atEndOfMonth());
      });

  /**
   * The dates a preset sets.
   *
   * @param from inclusive lower bound
   * @param to inclusive upper bound; null for open-ended
   */
  public record DateRange(LocalDate from, LocalDate to) {}

  private final String token;
  private final String label;
  private final Function<LocalDate, DateRange> range;

  DatePreset(String token, String label, Function<LocalDate, DateRange> range) {
    this.token = token;
    this.label = label;
    this.range = range;
  }

  /** The stable {@code datePreset} form-value token for this preset. */
  public String param() {
    return token;
  }

  /** The button text. */
  public String label() {
    return label;
  }

  /** The date range this preset selects when today is {@code today}. */
  public DateRange rangeAt(LocalDate today) {
    return range.apply(today);
  }

  /** The preset named by a submitted token; empty for null, blank, or unrecognised values. */
  public static Optional<DatePreset> fromParam(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    String trimmed = value.trim();
    for (DatePreset preset : values()) {
      if (preset.token.equals(trimmed)) {
        return Optional.of(preset);
      }
    }
    return Optional.empty();
  }
}
