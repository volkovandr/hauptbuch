package volkovandr.hauptbuch.debts;

import java.util.List;

/**
 * A live person with their per-currency debt position (data-model §7, plan stage 8d) — the {@code
 * debts}-side view model for the People-balances screen, before any base-currency valuation (which
 * needs {@code ledger}'s rates and so is applied by the {@code operations} assembler, not here).
 *
 * <p>{@code balances} lists only the currencies the person carries a <em>non-zero</em> net balance
 * in, so a fully settled person has an empty list. {@code accountIds} is the person's full set of
 * per-currency leaf ids — including any that net to zero — so the People page can build a register
 * link pre-filtered to everything that is theirs.
 *
 * @param personId the person's id
 * @param name the person's current display name
 * @param balances the non-zero per-currency signed balances, ordered by currency code
 * @param accountIds every one of the person's debt-leaf account ids (for the register pre-filter)
 */
public record PersonBalanceSummary(
    long personId, String name, List<CurrencyBalance> balances, List<Long> accountIds) {

  /** Defensively copy the balance and account-id lists to immutable lists. */
  public PersonBalanceSummary {
    balances = List.copyOf(balances);
    accountIds = List.copyOf(accountIds);
  }
}
