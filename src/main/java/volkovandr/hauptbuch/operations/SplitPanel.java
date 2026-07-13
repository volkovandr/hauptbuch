package volkovandr.hauptbuch.operations;

import java.time.LocalDate;
import java.util.List;

/**
 * The split panel's view model (register §3.10, plan stage 7c.2) — everything the panel fragment
 * needs to render: the transaction-level fields, the per-line list, and the derived readouts
 * (remaining, the pay/receive direction, the Save-button state). Assembled by {@link
 * SplitPanelAssembler} from a {@link SplitForm} on every server round-trip (open, add/remove line,
 * error redisplay); the same numbers are recomputed live in the keyboard.js leaf as the user types,
 * but the server's are authoritative.
 *
 * <p>{@code transactionId} flips new-vs-edit exactly as the simple dock does (register §3.1):
 * {@code null} records; a non-null id re-threads in place. {@code direction} is {@code pay} when
 * the lines net to an outflow ({@code Σ < 0}), {@code receive} when they net to an inflow ({@code Σ
 * > 0}), and {@code none} at a net-zero receipt — the cue that makes the derived funding side
 * visible before Save (owner-approved, 2026-07-09).
 *
 * @param transactionId the transaction being edited, or {@code null} for a new split
 * @param date booking date
 * @param accountId the funding account
 * @param payeeText the payee text to prefill; nullable
 * @param note transaction-level note to prefill; nullable
 * @param total the reference total (German-formatted) the remaining counts against — in the
 *     spending currency when cross-currency (register §3.8a)
 * @param currency the cross-currency header state (§3.8a/§3.10, plan 7d.2); {@link
 *     SplitCurrency#singleCurrency} for the untouched single-currency split
 * @param lines the rendered lines
 * @param remaining {@code total − |Σ|}, German-formatted — {@code 0,00} when the lines match the
 *     reference total (the spending-currency remaining when cross-currency)
 * @param balanced whether {@code remaining} is zero (drives the Save-button label: Save vs Save and
 *     update amount); one shared rate means every currency's remaining reaches zero together
 * @param netDisplay {@code |Σ|}, German-formatted — the amount that will hit the funding account
 *     (in the funding currency when cross-currency)
 * @param direction {@code pay} / {@code receive} / {@code none} (the derived funding side)
 * @param error a validation message to show, or {@code null}
 */
public record SplitPanel(
    Long transactionId,
    LocalDate date,
    Long accountId,
    String payeeText,
    String note,
    String total,
    SplitCurrency currency,
    List<SplitLineView> lines,
    String remaining,
    boolean balanced,
    String netDisplay,
    String direction,
    String error) {

  /** Defensively copy the lines so the panel cannot be mutated after the fact. */
  public SplitPanel {
    lines = lines == null ? List.of() : List.copyOf(lines);
  }
}
