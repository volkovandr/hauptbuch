package volkovandr.hauptbuch.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.accounts.OpeningBalanceRecorder;

/**
 * The engine's fulfilment of {@code accounts}' {@link OpeningBalanceRecorder} SPI (plan stage 6a).
 * The interface lives in {@code accounts} and the implementation here so the module dependency
 * stays one-way — {@code ledger} → {@code accounts} — instead of forming a cycle; see the SPI's
 * javadoc.
 *
 * <p>An opening balance is the textbook stage-3 transaction (data-model T-DM-4): {@code Account +X
 * / Opening Balances −X}, sum-to-zero by construction, both legs in the account's own currency
 * against its per-currency Opening Balances leaf. It goes through {@link
 * LedgerService#recordTransaction} like every other write, so the full invariant validation — and
 * the base-currency-is-set gate — applies.
 */
@Component
class LedgerOpeningBalanceRecorder implements OpeningBalanceRecorder {

  /**
   * The seeded system parent whose per-currency leaf anchors opening balances (data-model §3.2).
   */
  static final String OPENING_BALANCES_PARENT = "Opening Balances";

  private final AccountService accountService;
  private final LedgerService ledgerService;

  LedgerOpeningBalanceRecorder(AccountService accountService, LedgerService ledgerService) {
    this.accountService = accountService;
    this.ledgerService = ledgerService;
  }

  @Override
  public long recordOpeningBalance(long accountId, BigDecimal amount, LocalDate date) {
    Account account =
        accountService
            .findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("No account with id " + accountId));
    Account openingBalancesLeaf =
        accountService
            .findLeafUnderParentNamed(OPENING_BALANCES_PARENT, account.currencyCode())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No Opening Balances leaf for currency " + account.currencyCode()));
    return ledgerService.recordTransaction(
        TransactionDraft.confirmed(
            date,
            null,
            "Opening balance",
            List.of(
                PostingDraft.of(accountId, amount),
                PostingDraft.of(openingBalancesLeaf.accountId(), amount.negate()))));
  }
}
