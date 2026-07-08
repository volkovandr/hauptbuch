package volkovandr.hauptbuch.operations;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.ledger.PostingDraft;
import volkovandr.hauptbuch.ledger.TransactionDraft;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * The entry dock's commit path for a simple single-category transaction (register §3, plan stage
 * 7b). Lives in {@code operations} — it must orchestrate payee resolution ({@code ledger}),
 * per-currency-leaf resolution ({@link CurrencyLeafService}, which can subdivide), and {@code
 * recordTransaction} ({@code ledger}), and a dock controller in {@code ledger} could reach none of
 * those without closing a module cycle (the 6d precedent — plan stage 7 boundary note).
 *
 * <p>Category <em>create-new</em> is deliberately <em>not</em> here: it is {@code categories}' own
 * logic (the 6b path), and {@code operations → categories} would cycle with {@code categories →
 * operations}. The dock resolves a new category through the {@code categories} screen first, so
 * this service always receives an existing category id (see the dock controller).
 *
 * <p>The one piece of real logic is sign resolution (register §3.8): the amount is a bare magnitude
 * whose direction the counterpart's type fixes, unless an explicit leading {@code +}/{@code −}
 * overrides it (the refund/reversal case). Because the resolved leaf shares the funding account's
 * currency (§6.5 routing), the transaction is single-currency and sums to zero natively.
 */
@Service
public class DockCommitService {

  /** The Unicode minus sign, accepted as an override alongside the ASCII hyphen-minus. */
  private static final char UNICODE_MINUS = '−';

  private final AccountService accountService;
  private final PayeeService payeeService;
  private final CurrencyLeafService currencyLeafService;
  private final LedgerService ledgerService;

  DockCommitService(
      AccountService accountService,
      PayeeService payeeService,
      CurrencyLeafService currencyLeafService,
      LedgerService ledgerService) {
    this.accountService = accountService;
    this.payeeService = payeeService;
    this.currencyLeafService = currencyLeafService;
    this.ledgerService = ledgerService;
  }

  /**
   * Commit a simple dock entry: resolve the payee (existing or create-new), route the category to
   * its per-currency leaf (§6.5), resolve the sign-free amount into a balanced pair of legs (§3.8),
   * and record the transaction through the engine.
   *
   * @return the new transaction's id
   * @throws IllegalArgumentException if the funding account is unknown or the amount is unparseable
   */
  @Transactional
  public long commit(DockEntry entry) {
    Account fundingAccount =
        accountService
            .findById(entry.accountId())
            .orElseThrow(
                () -> new IllegalArgumentException("No account with id " + entry.accountId()));

    Account leaf =
        currencyLeafService.resolveCurrencyLeaf(entry.categoryId(), fundingAccount.currencyCode());
    Long payeeId = payeeService.resolvePayee(entry.payeeId(), entry.payeeText());

    BigDecimal fundingAmount = signedFundingAmount(entry.amount(), leaf.type());

    TransactionDraft draft =
        TransactionDraft.confirmed(
            entry.date(),
            payeeId,
            entry.note(),
            List.of(
                PostingDraft.of(fundingAccount.accountId(), fundingAmount),
                PostingDraft.of(leaf.accountId(), fundingAmount.negate())));
    return ledgerService.recordTransaction(draft);
  }

  /**
   * The signed amount on the <em>funding</em> leg (register §3.8). The magnitude is entered bare;
   * its sign is the counterpart's type — {@code expense} is an outflow ({@code −}), {@code income}
   * an inflow ({@code +}) — unless an explicit leading {@code +}/{@code −} overrides it (a refund
   * is an inflow to an expense category, which the type alone cannot express).
   *
   * @param amountText the typed amount, German-formatted, with an optional leading sign
   * @param counterpartType the resolved leaf's type ({@code income}/{@code expense})
   */
  static BigDecimal signedFundingAmount(String amountText, String counterpartType) {
    if (amountText == null || amountText.isBlank()) {
      throw new IllegalArgumentException("An amount is required");
    }
    String trimmed = amountText.strip();
    char first = trimmed.charAt(0);

    boolean explicitPlus = first == '+';
    boolean explicitMinus = first == '-' || first == UNICODE_MINUS;
    String magnitudeText = (explicitPlus || explicitMinus) ? trimmed.substring(1).strip() : trimmed;

    BigDecimal magnitude = MoneyFormat.parse(magnitudeText).abs();

    boolean outflow;
    if (explicitPlus) {
      outflow = false; // funds enter the account
    } else if (explicitMinus) {
      outflow = true; // funds leave the account
    } else {
      // No override: the counterpart type fixes direction (§3.8).
      outflow = "expense".equals(counterpartType);
    }
    return outflow ? magnitude.negate() : magnitude;
  }
}
