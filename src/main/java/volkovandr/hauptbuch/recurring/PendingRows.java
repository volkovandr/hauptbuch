package volkovandr.hauptbuch.recurring;

import java.time.LocalDate;

/**
 * The operator's answer when ending or deleting a template leaves pending rows behind (data-model
 * §14.3): keep them all, keep only those dated before today, or remove them all. Removed rows are
 * hard-deleted; confirmed transactions always stay.
 *
 * <p>Keeping only those dated before today is also the save's own rule for every pending row still
 * inside the schedule: an untouched future row is a forecast, removed and rebooked.
 */
public enum PendingRows {
  KEEP_ALL("keep-all", "Keep all pending"),
  KEEP_PAST("keep-past", "Keep only those dated before today"),
  REMOVE_ALL("remove-all", "Remove all pending");

  private final String token;
  private final String text;

  PendingRows(String token, String text) {
    this.token = token;
    this.text = text;
  }

  /** The form value that carries this answer. */
  public String code() {
    return token;
  }

  /** The button text. */
  public String label() {
    return text;
  }

  /** The button style: the destructive answer in red, keeping everything quiet. */
  public String buttonClass() {
    return switch (this) {
      case KEEP_ALL -> "btn btn--ghost";
      case KEEP_PAST -> "btn";
      case REMOVE_ALL -> "btn btn--danger";
    };
  }

  /** Whether a pending row dated {@code date} is removed under this answer on day {@code today}. */
  boolean removes(LocalDate date, LocalDate today) {
    return switch (this) {
      case KEEP_ALL -> false;
      case KEEP_PAST -> !date.isBefore(today);
      case REMOVE_ALL -> true;
    };
  }

  /**
   * The answer a form value names, or null for a blank one (not asked, or not answered yet).
   *
   * @throws IllegalArgumentException if the value names no answer
   */
  static PendingRows fromCode(String code) {
    if (code == null || code.isBlank()) {
      return null;
    }
    for (PendingRows answer : values()) {
      if (answer.token.equals(code.strip())) {
        return answer;
      }
    }
    throw new IllegalArgumentException("Unknown pending-rows answer: " + code);
  }
}
