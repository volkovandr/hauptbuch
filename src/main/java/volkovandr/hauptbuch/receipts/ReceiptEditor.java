package volkovandr.hauptbuch.receipts;

import java.time.LocalDate;
import java.util.List;
import volkovandr.hauptbuch.operations.SplitCurrency;

/**
 * The post-process editor's view model (plan §9f) — everything the {@code editor} fragment prints
 * back on every server round-trip (open, add/remove/redistribute line, Save redisplay), so the form
 * is the single source of truth exactly as the register's split panel is. The per-line rows reuse
 * the shared line-editor core; the header + readouts are this surface's own.
 *
 * <p>{@code currency} carries the full cross-currency header state (issue receipts/23): a receipt
 * billed in another currency than the paying account's reveals the register's own funding/base
 * total fields and per-line derived columns, resolved by the same {@code SplitCurrencyService} the
 * split panel reads. A same-currency receipt gets {@link SplitCurrency}'s single-currency shape and
 * no chrome at all. {@code remaining}/{@code balanced}/{@code status} are the server-authoritative
 * readout the keyboard.js leaf also recomputes live from {@code data-split-*}.
 *
 * @param date the booking date to prefill
 * @param payeeText the payee picker text (prefilled from the receipt's merchant on first open)
 * @param accountId the selected paying account, or null when the book has no accounts
 * @param currency the header currency state the shared fragment reads — single-currency, or the
 *     full cross-currency header when the paying account's currency differs from the receipt's
 * @param total the editable total, German-formatted; the reference the remaining counts against
 * @param note the header note (9g) — free text, copied to {@code transaction.note} at Confirm
 * @param receiptNumber the printed receipt/Beleg number (9g) — prefilled from the parse, editable
 * @param remaining {@code total − |Σ signed lines|}, German-formatted
 * @param balanced whether {@code remaining} is zero (drives the ✓ state)
 * @param status {@code ok} (lines sum to the total), {@code warn} (they diverge), or {@code none}
 *     (no total to check against) — the neutral "no total" hint
 * @param lines the editable rows (view + ghost)
 */
public record ReceiptEditor(
    LocalDate date,
    String payeeText,
    Long accountId,
    SplitCurrency currency,
    String total,
    String note,
    String receiptNumber,
    String remaining,
    boolean balanced,
    String status,
    List<ReceiptEditorLine> lines) {

  /** {@link #status()}: there is no total to check the lines against — the neutral hint. */
  public static final String STATUS_NO_TOTAL = "none";

  /** {@link #status()}: the lines sum exactly to the total (the ✓ state). */
  public static final String STATUS_BALANCED = "ok";

  /** {@link #status()}: a total is set but the lines diverge from it. */
  public static final String STATUS_UNBALANCED = "warn";

  /** Defensively copy the lines so the view model cannot be mutated after assembly. */
  public ReceiptEditor {
    lines = lines == null ? List.of() : List.copyOf(lines);
  }
}
