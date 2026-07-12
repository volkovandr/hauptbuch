package volkovandr.hauptbuch.operations;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.ledger.Posting;
import volkovandr.hauptbuch.ledger.Transaction;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * Loads a live transaction back into the entry dock's edit mode (register §3.1) — the read half of
 * the dock's "second half", beside {@link DockCommitService}'s write half. It lives in {@code
 * operations} for the same reason the commit does (plan stage 7 boundary note): it composes {@code
 * ledger}'s transaction/posting reads, {@code accounts}' leg classification, and the payee entry
 * value into the {@link DockEditModel} the dock pre-fills.
 *
 * <p>Edit mode covers a <em>simple</em> transaction (register §3.1): exactly one funding
 * own-account leg (asset/liability), exactly one category leg (income/expense), single currency.
 * That is the only shape the dock can round-trip today — opening balances (an equity leg),
 * transfers (two own-account legs), splits, and cross-currency transactions are refused with a
 * clear message until their sub-stage lands. The one piece of real logic is reconstructing the
 * sign-free amount text (register §3.8) so a re-save maps back to the same legs; it is unit-tested.
 */
@Service
public class DockEditService {

  private static final String INCOME = "income";
  private static final String EXPENSE = "expense";
  private static final String ASSET = "asset";
  private static final String LIABILITY = "liability";
  private static final List<String> OWN_TYPES = List.of(ASSET, LIABILITY);
  private static final List<String> CATEGORY_TYPES = List.of(INCOME, EXPENSE);

  /** Dock amounts are entered German-formatted to the minor unit; two places covers EUR/CHF. */
  private static final int AMOUNT_FRACTION_DIGITS = 2;

  private static final String NOT_EDITABLE =
      "This transaction cannot be edited in the dock yet — edit mode covers simple "
          + "single-category transactions (splits and transfers arrive later).";

  private final LedgerService ledgerService;
  private final AccountService accountService;
  private final PayeeService payeeService;

  DockEditService(
      LedgerService ledgerService, AccountService accountService, PayeeService payeeService) {
    this.ledgerService = ledgerService;
    this.accountService = accountService;
    this.payeeService = payeeService;
  }

  /**
   * Reconstruct the dock's fields from a live transaction (register §3.1).
   *
   * @throws IllegalArgumentException if there is no live transaction with that id, or it is not the
   *     simple shape the dock can edit yet
   */
  public DockEditModel load(long transactionId) {
    Transaction txn =
        ledgerService
            .findTransaction(transactionId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "No live transaction with id " + transactionId + " to edit"));

    SimpleLegs legs = classify(ledgerService.findPostings(transactionId));

    Account semantic = semanticCategory(legs.categoryLeaf());
    String payeeText =
        txn.payeeId() == null ? null : payeeService.entryValueFor(txn.payeeId()).orElse(null);

    return new DockEditModel(
        txn.transactionId(),
        txn.date(),
        legs.fundingAccount().accountId(),
        payeeText,
        amountText(legs.fundingLeg().amount(), legs.categoryLeaf().type()),
        semantic.accountId(),
        semantic.name(),
        txn.note());
  }

  /**
   * Split a transaction's postings into the dock's simple shape — one funding own-account leg, one
   * category leg — refusing anything else (an equity/opening-balance leg, a second own or category
   * leg, a cross-currency {@code baseAmount}) with the {@link #NOT_EDITABLE} message.
   */
  private SimpleLegs classify(List<Posting> postings) {
    List<LegAccount> legs =
        postings.stream().map(p -> new LegAccount(p, requireAccount(p.accountId()))).toList();
    boolean crossCurrency = legs.stream().anyMatch(l -> l.posting().baseAmount() != null);
    if (postings.size() != 2 || crossCurrency) {
      throw new IllegalArgumentException(NOT_EDITABLE);
    }
    LegAccount funding = onlyLegOfType(legs, OWN_TYPES);
    LegAccount category = onlyLegOfType(legs, CATEGORY_TYPES);
    return new SimpleLegs(funding.posting(), funding.account(), category.account());
  }

  /** The one leg whose account type is in {@code types}; anything but exactly one is not simple. */
  private static LegAccount onlyLegOfType(List<LegAccount> legs, List<String> types) {
    List<LegAccount> matching =
        legs.stream().filter(l -> types.contains(l.account().type())).toList();
    if (matching.size() != 1) {
      throw new IllegalArgumentException(NOT_EDITABLE);
    }
    return matching.get(0);
  }

  /** A posting paired with its (already-resolved) account. */
  private record LegAccount(Posting posting, Account account) {}

  /**
   * The classified legs of a simple transaction (its funding leg/account and its category leaf).
   */
  private record SimpleLegs(Posting fundingLeg, Account fundingAccount, Account categoryLeaf) {}

  /**
   * The <em>semantic</em> category the user picked: the parent when the leg hits an auto-managed
   * currency leaf (data-model §6.5), otherwise the leaf itself. Pre-filling the parent means a
   * re-save routes back through {@code resolveCurrencyLeaf} to the same leaf, rather than showing
   * the user the internal, hidden currency-leaf account.
   */
  private Account semanticCategory(Account leaf) {
    if (!leaf.currencyLeaf() || leaf.parentId() == null) {
      return leaf;
    }
    return accountService.findById(leaf.parentId()).orElse(leaf);
  }

  /**
   * The magnitude the user would type for a funding leg of the given signed amount (register §3.8):
   * bare when its direction is the category type's default (expense → outflow, income → inflow),
   * carrying an explicit {@code +}/{@code −} only for the overriding (refund/reversal) direction so
   * a re-save reproduces the same leg.
   */
  static String amountText(BigDecimal fundingAmount, String categoryType) {
    String magnitude = MoneyFormat.number(fundingAmount.abs(), AMOUNT_FRACTION_DIGITS);
    boolean outflow = fundingAmount.signum() < 0;
    boolean defaultOutflow = EXPENSE.equals(categoryType);
    if (outflow == defaultOutflow) {
      return magnitude;
    }
    return (outflow ? "-" : "+") + magnitude;
  }

  private Account requireAccount(Long accountId) {
    return accountService
        .findById(accountId)
        .orElseThrow(
            () -> new IllegalStateException("Posting references missing account " + accountId));
  }
}
