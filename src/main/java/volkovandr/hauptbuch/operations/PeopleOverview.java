package volkovandr.hauptbuch.operations;

import java.util.List;

/**
 * The render model for the People-balances screen (data-model §7, plan stage 8d) — every live
 * person with their per-currency debt position and a supplementary base-currency total.
 *
 * <p>Assembled in {@code operations} rather than {@code debts} because the base total is a
 * mark-to-market valuation (native balance × rate@today, data-model §9) and so needs {@code
 * ledger}'s rates and base currency, which {@code debts} may not reach (the module edge already
 * runs {@code ledger} → {@code debts}, from the 8c register name resolution). Per-currency figures
 * are the truth and the settlement basis; the base total is a gloss (data-model §7, T-DM-5).
 *
 * @param people the live persons, ordered by name
 * @param baseCurrencyCode the book's base currency code, shown as the base-total gloss's unit (the
 *     base amount is rendered bare, so its currency is named here); empty when no base is set, in
 *     which case no gloss is ever shown
 */
public record PeopleOverview(List<PersonRow> people, String baseCurrencyCode) {

  /** Defensively copy the person list to an immutable list. */
  public PeopleOverview {
    people = List.copyOf(people);
  }

  /**
   * One person's line in the roster. Collapsed, it shows the signed per-currency figures and the
   * base gloss; expanded (the template's {@code <details>}), it shows the directional wording per
   * currency and a register link pre-filtered to the person's leaves.
   *
   * @param personId the person's id (links to the edit page)
   * @param name the person's display name
   * @param lines the non-zero per-currency balances; empty when the person is settled (all balances
   *     net to zero, or they have no leaf yet)
   * @param baseTotalShown whether to show the base-currency gloss: true only when the person holds
   *     at least one non-base currency (an all-base position needs no conversion gloss)
   *     <em>and</em> every currency could be valued (each non-base leg had a rate) — otherwise
   *     suppressed rather than shown redundant or partial
   * @param baseTotal the base-currency total, German-formatted and bare (its currency is named by
   *     {@link PeopleOverview#baseCurrencyCode}); meaningful only when {@code baseTotalShown}
   * @param baseTotalNegative whether that total is negative (you owe on balance) — drives the ink
   * @param accountIds the person's leaf account ids for the register pre-filter link; empty for a
   *     brand-new person with no leaves yet, which suppresses the link
   */
  public record PersonRow(
      long personId,
      String name,
      List<CurrencyLine> lines,
      boolean baseTotalShown,
      String baseTotal,
      boolean baseTotalNegative,
      List<Long> accountIds) {

    /** Defensively copy the line and account-id lists to immutable lists. */
    public PersonRow {
      lines = List.copyOf(lines);
      accountIds = List.copyOf(accountIds);
    }
  }

  /**
   * One currency's balance, formatted two ways: {@code amount} is the signed figure for the
   * collapsed cell, {@code direction} is the human-readable sentence for the expanded breakdown.
   *
   * @param amount the signed native balance, German-formatted with a currency symbol unless base
   * @param negative whether the balance is negative (you owe) — drives the oxblood ink
   * @param direction directional wording, e.g. {@code "You owe 10,00"} or {@code "Max owes you 5,00
   *     CHF"}
   */
  public record CurrencyLine(String amount, boolean negative, String direction) {}
}
