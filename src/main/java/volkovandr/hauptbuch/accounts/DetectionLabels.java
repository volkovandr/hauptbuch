package volkovandr.hauptbuch.accounts;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The comma-separated payment-line labels stored on an account (data-model §13.4) — the one place
 * that knows how the list is written and read. {@link AccountService} normalises through it on the
 * way in and {@link PayingAccountDetector} matches through it on the way out; without a shared home
 * the split-and-strip rule would be spelled twice, and a stored list could mean two things.
 *
 * <p>A blank entry is dropped rather than kept, in both directions: an empty label is a substring
 * of every payment line, so a stray trailing comma would otherwise match every receipt ever parsed.
 *
 * <p>Deliberately a plain comma-separated column rather than its own table: labels carry no
 * identity and are <em>not</em> unique — two cards can share their printed last four by luck.
 */
public final class DetectionLabels {

  private static final String SEPARATOR = ",";

  private DetectionLabels() {}

  /**
   * The label list as it should be stored: each entry stripped, blanks dropped, the operator's
   * order and casing preserved — the order is the tie-break when two accounts share a label, and
   * matching is case-insensitive anyway. Null when nothing is left.
   */
  public static String normalise(String labels) {
    String joined = split(labels).collect(Collectors.joining(", "));
    return joined.isEmpty() ? null : joined;
  }

  /**
   * Whether any label appears in the payment line, which the caller has already lower-cased. The
   * labels are stripped again here rather than trusted: {@link #normalise} runs on the edit screen,
   * but the column is plain text and V16 carried over values written before it existed.
   */
  public static boolean matches(String labels, String lowerSignal) {
    return split(labels).anyMatch(label -> lowerSignal.contains(label.toLowerCase(Locale.ROOT)));
  }

  /** The non-blank labels, stripped, in the order given. */
  private static Stream<String> split(String labels) {
    if (labels == null || labels.isBlank()) {
      return Stream.of();
    }
    return Arrays.stream(labels.split(SEPARATOR)).map(String::strip).filter(l -> !l.isEmpty());
  }
}
