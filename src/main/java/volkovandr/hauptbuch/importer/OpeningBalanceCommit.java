package volkovandr.hauptbuch.importer;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.OpeningBalanceRecorder;
import volkovandr.hauptbuch.importer.StagedCommitData.CommitData;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.OpeningBalanceView;

/**
 * Applies each mapped account's opening-balance reconciliation at commit (import.md §5.1; plan f2).
 * Money exports an opening balance as a self-transfer that must not be booked as-is; the owner's
 * recorded choice — or, when none was recorded, {@link OpeningBalanceReconciliation}'s proposal —
 * decides:
 *
 * <ul>
 *   <li>{@code keep_hauptbuch} → drop Money's;
 *   <li>{@code take_money} → void the target account's own opening balance (if any) and book
 *       Money's figure;
 *   <li>{@code override} → the same, but book the owner's typed amount.
 * </ul>
 *
 * <p>A row that maps to a person's debt leaf has no opening balance and is skipped.
 */
@Component
class OpeningBalanceCommit {

  private final LedgerService ledgerService;
  private final OpeningBalanceRecorder openingBalanceRecorder;

  OpeningBalanceCommit(LedgerService ledgerService, OpeningBalanceRecorder openingBalanceRecorder) {
    this.ledgerService = ledgerService;
    this.openingBalanceRecorder = openingBalanceRecorder;
  }

  /**
   * Apply every mapped account's opening-balance reconciliation for a loaded campaign.
   *
   * @return how many opening balances were brought in or overridden from Money
   */
  int apply(CommitData data) {
    Map<String, ImportStagedOpeningBalance> stagedByName = new HashMap<>();
    for (ImportStagedOpeningBalance staged : data.stagedOpeningBalances()) {
      // The list is ordered by name then date — keep the earliest for an account with two files.
      stagedByName.putIfAbsent(staged.moneyAccountName(), staged);
    }

    int applied = 0;
    for (ImportAccount row : data.accountMap()) {
      if (row.accountId() == null) {
        continue;
      }
      ImportStagedOpeningBalance money = stagedByName.get(row.moneyAccountName());
      if (money == null) {
        continue;
      }
      Account account = data.accountsById().get(row.accountId());
      if (account == null || account.personLeaf()) {
        continue;
      }
      if (book(row, account, money)) {
        applied++;
      }
    }
    return applied;
  }

  private boolean book(ImportAccount row, Account account, ImportStagedOpeningBalance money) {
    // One lookup — used both to propose a winner and, on take_money/override, to void what it
    // finds.
    Optional<OpeningBalanceView> existing = ledgerService.openingBalanceOf(account.accountId());

    String choice = row.openingBalanceChoice();
    if (choice == null) {
      OpeningBalanceReconciliation.Balance hauptbuch =
          existing
              .map(view -> new OpeningBalanceReconciliation.Balance(view.date(), view.amount()))
              .orElse(null);
      choice =
          OpeningBalanceReconciliation.propose(
              hauptbuch, new OpeningBalanceReconciliation.Balance(money.date(), money.amount()));
    }
    if (OpeningBalanceChoice.KEEP_HAUPTBUCH.equals(choice)) {
      return false;
    }

    BigDecimal amount =
        OpeningBalanceChoice.OVERRIDE.equals(choice) ? row.openingBalanceAmount() : money.amount();
    if (amount == null) {
      throw new IllegalStateException(
          "Opening-balance override for \"" + row.moneyAccountName() + "\" has no amount");
    }

    existing.ifPresent(view -> ledgerService.voidTransaction(view.transactionId()));
    openingBalanceRecorder.recordOpeningBalance(account.accountId(), amount, money.date());
    return true;
  }
}
