package volkovandr.hauptbuch.ledger;

import java.util.List;

/**
 * Asked by the register's dock when it opens a transaction for editing, so the dock can say which
 * of its legs a bank statement has already proven (statements.md §6.4). Like {@link
 * ReconciliationDropListener}, the interface lives in {@code ledger} and the module that knows
 * about statements implements it, so neither {@code ledger} nor {@code operations} learns about
 * that module.
 */
@FunctionalInterface
public interface ReconciledLegNotices {

  /**
   * One short line per leg of the transaction that is matched to a statement, e.g. {@code "BankAaa
   * leg reconciled — statement 2026-05"}; empty when no leg is.
   */
  List<String> noticesFor(long transactionId);
}
