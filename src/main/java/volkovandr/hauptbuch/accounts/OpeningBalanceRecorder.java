package volkovandr.hauptbuch.accounts;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The one thing account management needs from the double-entry engine: posting an opening balance
 * as a real balanced transaction (data-model T-DM-4).
 *
 * <p>This interface lives <em>here</em> and the implementation lives in {@code ledger} — a
 * deliberate inversion. The module dependency points one way only, {@code ledger} → {@code
 * accounts} (the engine reads account definitions; plan stage 6). If this module called {@code
 * LedgerService} directly, the two modules would form a cycle and {@code
 * ApplicationModules.verify()} would rightly fail. Instead {@code accounts} states its need as this
 * SPI and the engine fulfils it — the posting still goes through the engine's full validation, the
 * only sanctioned way to write the ledger (CLAUDE.md §1.7).
 */
@FunctionalInterface
public interface OpeningBalanceRecorder {

  /**
   * Record {@code Account +amount / Opening Balances −amount} (sum-to-zero by construction) against
   * the per-currency Opening Balances leaf, dated {@code date}.
   *
   * @param accountId the just-created leaf account receiving its starting balance
   * @param amount signed starting balance in the account's own currency
   * @param date the booking date — the account's opening date
   * @return the new transaction's id
   */
  long recordOpeningBalance(long accountId, BigDecimal amount, LocalDate date);
}
