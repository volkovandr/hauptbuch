package volkovandr.hauptbuch.importer;

import java.util.List;

/**
 * A parsed Money transaction, in the source's own vocabulary — no id resolved, no ledger concept
 * touched (import.md §3). {@code rawDate} is the literal QIF date string ({@code D26/11'2011});
 * whole-file date-format detection (day/month order) is a3's job, not this one's, so nothing here
 * has to guess at it.
 *
 * @param rawDate the literal {@code D} field, unparsed
 * @param payeeText the {@code P} field; verbatim when it carries information, null when absent or
 *     when it was <em>entirely</em> destroyed on export (all {@code ?}/whitespace, §4.4) — a
 *     partially destroyed name is kept verbatim
 * @param memo the header {@code M} field; null when absent
 * @param referenceNumber the {@code N} field (cheque/reference number); null when absent — kept
 *     separate from {@code memo} here, since "prefixed into the note" (§4.2) is a ledger-write-time
 *     combination, not part of the canonical shape
 * @param clearedStatus the {@code C} field, classified (§4.2)
 * @param openingBalance true when this is Money's opening-balance self-transfer — an {@code
 *     [Account]} line naming the very account the file is for (§5.1); reconciled against the target
 *     account's own opening balance at map time (c3), never booked blindly
 * @param lines this transaction's lines — exactly one for a simple transaction; a split carries
 *     several, with {@code E} landing on each line's memo
 */
public record ImportedTransaction(
    String rawDate,
    String payeeText,
    String memo,
    String referenceNumber,
    ClearedStatus clearedStatus,
    boolean openingBalance,
    List<ImportedLine> lines) {

  /**
   * Defensive copy of {@code lines} (the house pattern for record lists, e.g. {@code
   * ParsedReceipt}).
   */
  public ImportedTransaction {
    lines = lines == null ? List.of() : List.copyOf(lines);
  }
}
