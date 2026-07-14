package volkovandr.hauptbuch.ledger;

import java.util.List;

/**
 * One fully-rendered register row (register §2.4–§2.10) — everything the template prints, already
 * formatted and classified, so the template only iterates. The amount and balance arrive as German
 * strings (base bare / non-base with symbol — register §2.9); the flags carry the display rules
 * (income green, negative-balance red, pending muted).
 *
 * @param postingId the row's stable identity (the leg it is)
 * @param transactionId the owning transaction (the row links to its edit dock at 7c)
 * @param date the booking date, ISO for the template's date formatting
 * @param accountName the Account cell
 * @param payeeName the Payee cell; null for a payee-less transaction (a transfer)
 * @param accountHue the account's stored hue for the same-hue zebra (register §2.8); null → neutral
 * @param zebraDark whether this is the darker of the account's two zebra shades (alternates per
 *     account thread, not per screen row — register §2.8)
 * @param category the summarised Category cell (biggest wins · +n / ⇄ transfer — register §2.6)
 * @param amountDisplay the signed native amount, German-formatted
 * @param income whether the amount is an inflow (rendered green — register §2.8)
 * @param balanceDisplay the running balance, German-formatted; {@code "—"} for a pending row
 * @param negativeBalance whether the balance is negative (rendered red — "in the red")
 * @param pending whether the row is {@code pending_review} (muted, no balance — register §2.10)
 * @param reconciliation the reconciliation state, for the trailing status glyph
 * @param tags the canonical {@code Parent:Child} labels of this leg's tags, rendered as chips in
 *     the Category cell (register §3.6, plan stage 7e); empty when the leg is untagged
 */
public record RegisterRowView(
    long postingId,
    long transactionId,
    String date,
    String accountName,
    String payeeName,
    Integer accountHue,
    boolean zebraDark,
    RegisterCategoryCell category,
    String amountDisplay,
    boolean income,
    String balanceDisplay,
    boolean negativeBalance,
    boolean pending,
    String reconciliation,
    List<String> tags) {

  /** Defensively copy the tags to an immutable list (null-safe). */
  public RegisterRowView {
    tags = tags == null ? List.of() : List.copyOf(tags);
  }

  /**
   * The Category cell's content (register §2.6): the chips shown, biggest-magnitude first, plus a
   * count of any not shown (the {@code · +n} overflow hint). A chip is a category name or a {@code
   * ⇄ Account} transfer target.
   *
   * @param chips the shown counterpart chips, in the order rendered
   * @param overflow how many further counterpart legs are summarised away (0 = none)
   */
  public record RegisterCategoryCell(List<CategoryChip> chips, int overflow) {

    /** Defensively copy the chips to an immutable list. */
    public RegisterCategoryCell {
      chips = List.copyOf(chips);
    }
  }

  /**
   * One chip in the Category cell.
   *
   * <p>A transfer chip shows the counterpart account with a direction arrow set by the flow
   * (register §2.6, plan stage 7d.3): {@code → Account} when money went <em>to</em> that account
   * (its leg is a debit), {@code ← Account} when it came <em>from</em> it (its leg is a credit). A
   * category chip is a plain name with no arrow.
   *
   * @param label the display text (the category name, or the account name for a transfer)
   * @param transfer whether this is a transfer to another own account (rendered with a direction
   *     arrow)
   * @param inbound for a transfer, whether funds flowed from the counterpart into the row's account
   *     ({@code ← label}); {@code false} means they flowed out to it ({@code → label}). Meaningless
   *     for a category chip.
   */
  public record CategoryChip(String label, boolean transfer, boolean inbound) {}
}
