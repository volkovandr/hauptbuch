package volkovandr.hauptbuch.recurring;

import java.util.Locale;

/**
 * The unit a recurring template's cadence counts in (data-model §14.1): every N days, weeks, months
 * or years. Stored in {@code recurring_template.cadence_unit} as its lower-case {@link #code()}.
 */
public enum CadenceUnit {
  DAY,
  WEEK,
  MONTH,
  YEAR;

  /** The stored value, as the {@code cadence_unit} check constraint spells it. */
  public String code() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * The unit a stored {@code cadence_unit} value names.
   *
   * @throws IllegalArgumentException if the code names no unit
   */
  public static CadenceUnit fromCode(String code) {
    for (CadenceUnit unit : values()) {
      if (unit.code().equals(code)) {
        return unit;
      }
    }
    throw new IllegalArgumentException("Unknown cadence unit: " + code);
  }
}
