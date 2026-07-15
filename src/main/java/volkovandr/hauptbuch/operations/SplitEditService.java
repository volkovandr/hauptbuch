package volkovandr.hauptbuch.operations;

import java.math.BigDecimal;
import java.util.ArrayList;
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
 * Loads a live split transaction back into the split panel's edit mode (register §3.1/§3.10) — the
 * read half of the split dock, beside {@link DockSplitService}'s write half. It is the split-shaped
 * sibling of {@link DockEditService} (the simple dock's read half): the two-part split flow mirrors
 * the simple dock's {@link DockCommitService}/{@link DockEditService} split, keeping each service
 * focused on one direction.
 *
 * <p>The shape the panel edits is one funding own-account leg (asset/liability) plus two or more
 * category legs (income/expense); a same-currency split has all legs in one currency, a
 * cross-currency one (register §3.8a/§3.10) has the category legs in a single spending currency
 * with frozen base amounts. Anything else (a transfer, an opening balance, or a simple one-category
 * transaction — which the dock edits) is refused so the caller can fall back.
 */
@Service
public class SplitEditService {

  private static final String INCOME = "income";
  private static final String EXPENSE = "expense";
  private static final String ASSET = "asset";
  private static final String LIABILITY = "liability";
  private static final List<String> OWN_TYPES = List.of(ASSET, LIABILITY);
  private static final List<String> CATEGORY_TYPES = List.of(INCOME, EXPENSE);

  /** German entry is to the minor unit; two places covers EUR/CHF/USD. */
  private static final int FRACTION_DIGITS = 2;

  private static final String NOT_A_SPLIT =
      "This transaction cannot be edited as a split — the split panel edits one funding leg "
          + "with two or more category lines.";

  private final AccountService accountService;
  private final PayeeService payeeService;
  private final LedgerService ledgerService;

  SplitEditService(
      AccountService accountService, PayeeService payeeService, LedgerService ledgerService) {
    this.accountService = accountService;
    this.payeeService = payeeService;
    this.ledgerService = ledgerService;
  }

  /**
   * Reconstruct a live split transaction into a {@link SplitForm} for the panel's edit mode
   * (register §3.1). Each line's typed amount is reconstructed from its leg so a re-save reproduces
   * the same postings; the funding leg's tags reload into the header field and each category leg's
   * into its line's chips (register §3.6, plan stage 7e.3); the {@code view*} filter fields are
   * left null (the panel renders them from the active-filter view model, not the transaction).
   *
   * @throws IllegalArgumentException if there is no live transaction with that id, or it is not the
   *     split shape the panel edits
   */
  public SplitForm load(long transactionId) {
    final Transaction txn =
        ledgerService
            .findTransaction(transactionId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "No live transaction with id " + transactionId + " to edit"));

    SplitLegs split = classifySplit(ledgerService.findPostings(transactionId));

    List<String> categoryText = new ArrayList<>();
    List<String> categoryId = new ArrayList<>();
    List<String> categoryType = new ArrayList<>();
    List<String> transferDirection = new ArrayList<>();
    List<String> amount = new ArrayList<>();
    List<String> note = new ArrayList<>();
    List<List<Long>> lineTagIds = new ArrayList<>();
    BigDecimal spendingTotal = BigDecimal.ZERO;
    for (LegAccount leg : split.categoryLegs()) {
      Account semantic = semanticCategory(leg.account());
      categoryText.add(semantic.name());
      categoryId.add(String.valueOf(semantic.accountId()));
      categoryType.add(leg.account().type());
      // The panel only classifies category-leg splits (a split with a transfer leg has two or more
      // own-account legs and is refused above), so every reloaded line is an ordinary category.
      transferDirection.add("");
      amount.add(SplitLineAmounts.amountText(leg.posting().amount(), leg.account().type()));
      note.add(leg.posting().note() == null ? "" : leg.posting().note());
      // Each line reloads its own tags (register §3.6, plan stage 7e.3); the funding leg's tags
      // (the transaction-level set) reload into the header field below.
      lineTagIds.add(ledgerService.tagIdsForPosting(leg.posting().postingId()));
      spendingTotal = spendingTotal.add(leg.posting().amount().abs());
    }
    List<Long> headerTagIds = ledgerService.tagIdsForPosting(split.funding().posting().postingId());

    boolean crossCurrency = split.funding().posting().baseAmount() != null;
    String spendingCurrency =
        crossCurrency ? split.categoryLegs().get(0).account().currencyCode() : null;
    String fundingTotal =
        crossCurrency
            ? MoneyFormat.number(split.funding().posting().amount().abs(), FRACTION_DIGITS)
            : "";
    String baseTotal =
        crossCurrency
            ? MoneyFormat.number(split.funding().posting().baseAmount().abs(), FRACTION_DIGITS)
            : "";
    // The reference total is the spending-currency sum when cross-currency (the lines are in the
    // spending currency), else the funding magnitude (single-currency splits balance natively).
    String total =
        crossCurrency
            ? MoneyFormat.number(spendingTotal, FRACTION_DIGITS)
            : MoneyFormat.number(split.funding().posting().amount().abs(), FRACTION_DIGITS);

    String payeeText =
        txn.payeeId() == null ? null : payeeService.entryValueFor(txn.payeeId()).orElse(null);
    return new SplitForm(
        txn.transactionId(),
        txn.date(),
        split.funding().account().accountId(),
        payeeText,
        txn.note(),
        total,
        spendingCurrency,
        fundingTotal,
        baseTotal,
        categoryText,
        categoryId,
        categoryType,
        transferDirection,
        amount,
        note,
        headerTagIds,
        lineTagIds,
        null,
        null,
        null,
        null);
  }

  /**
   * Split a transaction's postings into the panel-editable shape — one funding own-account leg plus
   * two or more category legs — refusing anything else with {@link #NOT_A_SPLIT}. A cross-currency
   * split (frozen base amounts, one spending currency) is accepted: the panel edits it via the
   * header currency fields (register §3.8a).
   */
  private SplitLegs classifySplit(List<Posting> postings) {
    List<LegAccount> legs =
        postings.stream().map(p -> new LegAccount(p, requireAccount(p.accountId()))).toList();
    List<LegAccount> fundingLegs = legsOfType(legs, OWN_TYPES);
    List<LegAccount> categoryLegs = legsOfType(legs, CATEGORY_TYPES);
    if (fundingLegs.size() != 1
        || categoryLegs.size() < 2
        || fundingLegs.size() + categoryLegs.size() != legs.size()) {
      throw new IllegalArgumentException(NOT_A_SPLIT);
    }
    return new SplitLegs(fundingLegs.get(0), categoryLegs);
  }

  private static List<LegAccount> legsOfType(List<LegAccount> legs, List<String> types) {
    return legs.stream().filter(l -> types.contains(l.account().type())).toList();
  }

  /**
   * The <em>semantic</em> category the user picked: the parent when the leg hits an auto-managed
   * currency leaf (data-model §6.5), otherwise the leaf itself — so a re-save routes back through
   * {@code resolveCurrencyLeaf} to the same leaf. Mirrors {@link DockEditService}'s reconstruction
   * for the simple dock.
   */
  private Account semanticCategory(Account leaf) {
    if (!leaf.currencyLeaf() || leaf.parentId() == null) {
      return leaf;
    }
    return accountService.findById(leaf.parentId()).orElse(leaf);
  }

  private Account requireAccount(Long accountId) {
    return accountService
        .findById(accountId)
        .orElseThrow(
            () -> new IllegalStateException("Posting references missing account " + accountId));
  }

  /** A posting paired with its (already-resolved) account. */
  private record LegAccount(Posting posting, Account account) {}

  /** The classified legs of a split: its single funding leg and its category legs. */
  private record SplitLegs(LegAccount funding, List<LegAccount> categoryLegs) {}
}
