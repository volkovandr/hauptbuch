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
import volkovandr.hauptbuch.ledger.TransferTarget;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * Loads a live split transaction back into the split panel's edit mode (register §3.1/§3.10) — the
 * read half of the split dock, beside {@link DockSplitService}'s write half. It is the split-shaped
 * sibling of {@link DockEditService} (the simple dock's read half): the two-part split flow mirrors
 * the simple dock's {@link DockCommitService}/{@link DockEditService} split, keeping each service
 * focused on one direction.
 *
 * <p>The shape the panel edits is one funding own-account leg (asset/liability) plus two or more
 * counter legs — each a category (income/expense) or, a transfer, another own account (register
 * §3.8, plan stage 7d.3/7f). A same-currency split has all legs in one currency; a cross-currency
 * one (register §3.8a/§3.10) has the counter legs in a single spending currency with frozen base
 * amounts. Anything else (an opening balance, or a simple/single-line-transfer transaction — which
 * the dock edits) is refused so the caller can fall back.
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

  /** One funding leg plus at least two counter lines — anything smaller is a dock shape. */
  private static final int FUNDING_PLUS_MIN_LINES = 3;

  private static final String NOT_A_SPLIT =
      "This transaction cannot be edited as a split — the split panel edits one funding leg "
          + "with two or more counter lines (categories or transfers).";

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
   * the same postings; the funding leg's tags reload into the header field and each counter leg's
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
    BigDecimal counterNet = BigDecimal.ZERO;
    for (LegAccount leg : split.counterLegs()) {
      ReloadedLine line = reloadLine(leg);
      categoryText.add(line.categoryText());
      categoryId.add(line.categoryId());
      categoryType.add(line.categoryType());
      transferDirection.add(line.transferDirection());
      amount.add(line.amount());
      note.add(line.note());
      // Each line reloads its own tags (register §3.6, plan stage 7e.3); the funding leg's tags
      // (the transaction-level set) reload into the header field below.
      lineTagIds.add(ledgerService.tagIdsForPosting(leg.posting().postingId()));
      counterNet = counterNet.add(leg.posting().amount());
    }
    List<Long> headerTagIds = ledgerService.tagIdsForPosting(split.funding().posting().postingId());

    boolean crossCurrency = split.funding().posting().baseAmount() != null;
    String spendingCurrency =
        crossCurrency ? split.counterLegs().get(0).account().currencyCode() : null;
    String fundingTotal =
        crossCurrency
            ? MoneyFormat.number(split.funding().posting().amount().abs(), FRACTION_DIGITS)
            : "";
    String baseTotal =
        crossCurrency
            ? MoneyFormat.number(split.funding().posting().baseAmount().abs(), FRACTION_DIGITS)
            : "";
    // The reference total is what hits the funding account: the funding magnitude natively (single-
    // currency splits balance natively), or — cross-currency — the counter legs' spending-currency
    // net. It is the net (|Σ leg|), never the sum of magnitudes: a mixed income/expense split must
    // reload balanced (remaining 0,00), so +2500/−500 reads a 2000 total, not 3000.
    String total =
        crossCurrency
            ? MoneyFormat.number(counterNet.abs(), FRACTION_DIGITS)
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
   * Reconstruct one counter leg into the fields the panel prints for its line (register §3.10). A
   * <em>transfer</em> leg (a real own account) inverts {@code transferContribution}: the leg is the
   * negated contribution, so a positive leg (the target took an inflow) reads back as a {@code TO}
   * and a negative one as a {@code FROM}, its bare magnitude shown in the direction-prefixed {@code
   * To →}/{@code From ←} field text (register §3.8, plan stage 7d.3/7f). A category leg maps back
   * to its semantic category and the sign-free amount its type implies.
   */
  private ReloadedLine reloadLine(LegAccount leg) {
    BigDecimal legAmount = leg.posting().amount();
    String noteText = leg.posting().note() == null ? "" : leg.posting().note();
    if (OWN_TYPES.contains(leg.account().type())) {
      TransferTarget.Direction direction =
          legAmount.signum() < 0 ? TransferTarget.Direction.FROM : TransferTarget.Direction.TO;
      return new ReloadedLine(
          TransferTarget.option(direction, leg.account().name()),
          String.valueOf(leg.account().accountId()),
          "",
          direction.name(),
          MoneyFormat.number(legAmount.abs(), FRACTION_DIGITS),
          noteText);
    }
    Account semantic = semanticCategory(leg.account());
    return new ReloadedLine(
        semantic.name(),
        String.valueOf(semantic.accountId()),
        leg.account().type(),
        "",
        SplitLineAmounts.amountText(legAmount, leg.account().type()),
        noteText);
  }

  /** One counter leg reconstructed into its panel-line fields (a category line or a transfer). */
  private record ReloadedLine(
      String categoryText,
      String categoryId,
      String categoryType,
      String transferDirection,
      String amount,
      String note) {}

  /**
   * Split a transaction's postings into the panel-editable shape — one funding own-account leg plus
   * two or more counter legs — refusing anything else with {@link #NOT_A_SPLIT}. Each counter leg
   * is a category (income/expense) or, a transfer, another own account (register §3.8, plan stage
   * 7d.3/7f). A cross-currency split (frozen base amounts, one spending currency) is accepted: the
   * panel edits it via the header currency fields (register §3.8a).
   *
   * <p>The funding leg is {@code legs.get(0)}: every recording path ({@link DockSplitService},
   * {@link DockCommitService}) adds it before the counter legs, and {@code findPostings} returns
   * postings in {@code posting_id} (insertion) order — so the first own-account leg carries the
   * account the header chose, and the rest are its lines. Requiring at least three legs keeps the
   * dock's shapes (a simple or single-line-transfer transaction) with the dock; an equity
   * (opening-balance) counter leg is refused.
   */
  private SplitLegs classifySplit(List<Posting> postings) {
    List<LegAccount> legs =
        postings.stream().map(p -> new LegAccount(p, requireAccount(p.accountId()))).toList();
    if (legs.size() < FUNDING_PLUS_MIN_LINES) {
      throw new IllegalArgumentException(NOT_A_SPLIT);
    }
    LegAccount funding = legs.get(0);
    if (!OWN_TYPES.contains(funding.account().type())) {
      throw new IllegalArgumentException(NOT_A_SPLIT);
    }
    List<LegAccount> counterLegs = legs.subList(1, legs.size());
    for (LegAccount leg : counterLegs) {
      String type = leg.account().type();
      if (!CATEGORY_TYPES.contains(type) && !OWN_TYPES.contains(type)) {
        throw new IllegalArgumentException(NOT_A_SPLIT);
      }
    }
    return new SplitLegs(funding, counterLegs);
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

  /**
   * The classified legs of a split: its single funding leg and its counter legs (category legs and,
   * for a split with transfers, own-account transfer legs).
   */
  private record SplitLegs(LegAccount funding, List<LegAccount> counterLegs) {}
}
