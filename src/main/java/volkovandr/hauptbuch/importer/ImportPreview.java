package volkovandr.hauptbuch.importer;

import java.util.List;

/**
 * What the upload screen shows before anything is staged (import.md §4.3/§4.4, plan b2): the
 * account type Money's header proposes, the detected charset and date order (each with the
 * <em>effective</em> value the owner has confirmed or overridden), the date-order evidence, the
 * first {@value QifCharset#PREVIEW_LINE_COUNT} decoded lines so mojibake is visible, and the record
 * count that gets ticked against Money's own figures.
 *
 * <p>A file the parser refuses ({@code !Type:Invst}, a destroyed account name, a broken record —
 * import.md §4.5) comes back with {@link #rejection} set and the parsed fields null; the decoded
 * preview lines are still filled in so the owner can see what was read.
 *
 * @param proposedAccountType {@code "asset"} / {@code "liability"} from the {@code !Type:} header,
 *     or null when the file was rejected
 * @param charset the effective charset code ({@code utf_8} / {@code windows_1252})
 * @param detectedCharset the charset the strict-UTF-8 probe detected, as a code
 * @param dateOrder the effective date-order code ({@code day_month} / {@code month_day} / {@code
 *     ambiguous}), or null when the file was rejected
 * @param detectedDateOrder the detected date-order code, or null when the file was rejected
 * @param dateEvidence a one-line description of the date-order detection and its proof, or null
 *     when the file was rejected
 * @param previewLines the first decoded lines of the file
 * @param recordCount the number of parsed transactions, or null when the file was rejected
 * @param accountName the Money account the file is for (import.md §4.1) — deduced from the
 *     opening-balance record or stated by the owner; null when neither has happened yet
 * @param accountNameDeduced true when {@code accountName} was read from the file's own
 *     opening-balance self-transfer (§5.1) rather than typed by the owner
 * @param rejection the user-facing reason the file cannot be imported, or null when it parsed
 */
public record ImportPreview(
    String proposedAccountType,
    String charset,
    String detectedCharset,
    String dateOrder,
    String detectedDateOrder,
    String dateEvidence,
    List<String> previewLines,
    Integer recordCount,
    String accountName,
    boolean accountNameDeduced,
    String rejection) {

  /** Defensive copy of the preview lines. */
  public ImportPreview {
    previewLines = previewLines == null ? List.of() : List.copyOf(previewLines);
  }

  /**
   * A preview of a file the parser refused (import.md §4.5) — the parsed fields are null, but the
   * decoded lines are kept so the owner can see what was read.
   */
  public static ImportPreview rejected(
      String charset, String detectedCharset, List<String> previewLines, String rejection) {
    return new ImportPreview(
        null,
        charset,
        detectedCharset,
        null,
        null,
        null,
        previewLines,
        null,
        null,
        false,
        rejection);
  }

  /** Whether the parser refused this file (import.md §4.5). */
  public boolean rejected() {
    return rejection != null;
  }

  /**
   * Whether the owner still has to state which Money account this file is for (import.md §4.1) —
   * the file has no opening-balance record to name it and none has been typed. Staging is refused
   * until this is false, the same way an ambiguous date order blocks it.
   */
  public boolean awaitingAccountName() {
    return !rejected() && accountName == null;
  }

  /** The decoded preview lines joined for rendering in a {@code <pre>}. */
  public String previewText() {
    return String.join("\n", previewLines);
  }
}
