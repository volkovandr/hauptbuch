package volkovandr.hauptbuch.operations;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.ledger.Posting;
import volkovandr.hauptbuch.ledger.PostingDraft;
import volkovandr.hauptbuch.ledger.Transaction;
import volkovandr.hauptbuch.ledger.TransactionDraft;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * The split panel's commit path (register §3.10, plan stage 7c.2) — the multi-line sibling of
 * {@link DockCommitService}. Lives in {@code operations} for the same reason the simple commit does
 * (plan stage 7 boundary note): it orchestrates payee resolution ({@code ledger}),
 * per-currency-leaf routing ({@link CurrencyLeafService}), and {@code recordTransaction}/{@code
 * editTransaction} ({@code ledger}), which a controller in {@code ledger} could not reach without a
 * module cycle.
 *
 * <p><strong>The mixed-split sign rule (register §3.8, ratified 2026-07-09).</strong> One receipt
 * is always one transaction, even when it mixes expense and income lines. Each line has a
 * <em>signed contribution</em>: {@code +amount} when its category is income, {@code −amount} when
 * expense — where {@code amount} is the number the user typed <em>already signed</em>, so a leading
 * {@code −} (a storno) flows through (a negative on an income line counts negatively; on an expense
 * line, positively). The funding leg is the sum of contributions (its magnitude the amount that
 * hits the account, its sign the direction: {@code +} debit/inflow, {@code −} credit/outflow, and
 * an exactly zero sum booked on the debit side — a net-zero receipt is legal and recordable). Each
 * category leg is its line's <em>negated</em> contribution, so the funding leg plus the category
 * legs sum to zero by construction, for any mix.
 *
 * <p>Category <em>create-new</em> is not here — like the simple dock, the browser resolves a new
 * category through the {@code categories} module first ({@code operations → categories} would
 * cycle), so every line arrives with an existing category id.
 */
@Service
public class DockSplitService {

  private static final String INCOME = "income";
  private static final List<String> OWN_TYPES = List.of("asset", "liability");
  private static final List<String> CATEGORY_TYPES = List.of(INCOME, "expense");

  /** German entry is to the minor unit; two places covers EUR/CHF. */
  private static final int FRACTION_DIGITS = 2;

  /** The Unicode minus sign, accepted as a storno marker alongside the ASCII hyphen-minus. */
  private static final char UNICODE_MINUS = '−';

  private static final String NOT_A_SPLIT =
      "This transaction cannot be edited as a split — the split panel edits one funding leg "
          + "with two or more category lines in a single currency.";

  private final AccountService accountService;
  private final PayeeService payeeService;
  private final CurrencyLeafService currencyLeafService;
  private final LedgerService ledgerService;

  DockSplitService(
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
   * Commit a split entry: resolve the payee, route each line's category to the funding account's
   * currency leaf (§6.5), sign each line by its leaf's type, and build the funding leg (the signed
   * sum) plus one category leg per line (the negated contribution). Records a new transaction when
   * {@link SplitEntry#transactionId()} is {@code null}, otherwise re-threads that one in place.
   *
   * @return the affected transaction's id (the new one, or the edited one unchanged)
   * @throws IllegalArgumentException if there are no lines, the funding account is unknown, or a
   *     line amount is unparseable
   */
  @Transactional
  public long commit(SplitEntry entry) {
    if (entry.lines().isEmpty()) {
      throw new IllegalArgumentException("A split needs at least one line");
    }
    Account fundingAccount =
        accountService
            .findById(entry.accountId())
            .orElseThrow(
                () -> new IllegalArgumentException("No account with id " + entry.accountId()));

    List<PostingDraft> categoryLegs = new ArrayList<>();
    BigDecimal fundingAmount = BigDecimal.ZERO;
    for (SplitLineDraft line : entry.lines()) {
      Account leaf =
          currencyLeafService.resolveCurrencyLeaf(line.categoryId(), fundingAccount.currencyCode());
      BigDecimal contribution = signedContribution(line.amount(), leaf.type());
      fundingAmount = fundingAmount.add(contribution);
      categoryLegs.add(
          PostingDraft.of(leaf.accountId(), contribution.negate(), blankToNull(line.note())));
    }

    List<PostingDraft> legs = new ArrayList<>();
    legs.add(PostingDraft.of(fundingAccount.accountId(), fundingAmount));
    legs.addAll(categoryLegs);

    Long payeeId = payeeService.resolvePayee(entry.payeeId(), entry.payeeText());
    TransactionDraft draft =
        TransactionDraft.confirmed(entry.date(), payeeId, blankToNull(entry.note()), legs);
    if (entry.transactionId() == null) {
      return ledgerService.recordTransaction(draft);
    }
    ledgerService.editTransaction(entry.transactionId(), draft);
    return entry.transactionId();
  }

  /**
   * A split line's signed contribution to the funding sum (register §3.8, the mixed-split rule):
   * the typed amount kept with its own sign, then made positive for an income category and negative
   * for an expense one. A storno (a leading {@code −}) therefore flips: negative on income counts
   * negatively, negative on expense counts positively.
   *
   * @param amountText the typed amount, German-formatted, optionally a leading {@code −} storno
   * @param categoryType the resolved leaf's type ({@code income}/{@code expense})
   */
  static BigDecimal signedContribution(String amountText, String categoryType) {
    BigDecimal value = parseSignedAmount(amountText);
    return INCOME.equals(categoryType) ? value : value.negate();
  }

  /**
   * Parse a line amount keeping its sign: a bare magnitude is positive; a leading {@code −} (ASCII
   * or Unicode) makes it negative (a storno); a leading {@code +} is accepted and redundant. Unlike
   * the simple dock's sign resolution, there is no direction <em>override</em> here — the category
   * type decides direction, and the sign is only the storno.
   */
  static BigDecimal parseSignedAmount(String amountText) {
    if (amountText == null || amountText.isBlank()) {
      throw new IllegalArgumentException("A line amount is required");
    }
    String trimmed = amountText.strip();
    char first = trimmed.charAt(0);
    boolean negative = first == '-' || first == UNICODE_MINUS;
    boolean signed = negative || first == '+';
    String magnitudeText = signed ? trimmed.substring(1).strip() : trimmed;

    BigDecimal magnitude = MoneyFormat.parse(magnitudeText).abs();
    return negative ? magnitude.negate() : magnitude;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /**
   * Reconstruct a live split transaction into a {@link SplitForm} for the panel's edit mode
   * (register §3.1) — the split-shaped sibling of {@link DockEditService#load}. The shape the panel
   * edits is one funding own-account leg (asset/liability) plus two or more category legs
   * (income/expense) in a single currency; anything else (a transfer, an opening balance, a
   * cross-currency conversion, or a simple one-category transaction — which the dock edits) is
   * refused so the caller can fall back. Each line's typed amount is reconstructed from its leg so
   * a re-save reproduces the same postings; the {@code view*} filter fields are left null (the
   * panel renders them from the active-filter view model, not the transaction).
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
    List<String> amount = new ArrayList<>();
    List<String> note = new ArrayList<>();
    for (LegAccount leg : split.categoryLegs()) {
      Account semantic = semanticCategory(leg.account());
      categoryText.add(semantic.name());
      categoryId.add(String.valueOf(semantic.accountId()));
      categoryType.add(leg.account().type());
      amount.add(amountText(leg.posting().amount(), leg.account().type()));
      note.add(leg.posting().note() == null ? "" : leg.posting().note());
    }

    String payeeText =
        txn.payeeId() == null ? null : payeeService.entryValueFor(txn.payeeId()).orElse(null);
    return new SplitForm(
        txn.transactionId(),
        txn.date(),
        split.funding().account().accountId(),
        payeeText,
        txn.note(),
        MoneyFormat.number(split.funding().posting().amount().abs(), FRACTION_DIGITS),
        categoryText,
        categoryId,
        categoryType,
        amount,
        note,
        null,
        null,
        null,
        null);
  }

  /**
   * Split a transaction's postings into the panel-editable shape — one funding own-account leg plus
   * two or more category legs, single currency — refusing anything else with {@link #NOT_A_SPLIT}.
   */
  private SplitLegs classifySplit(List<Posting> postings) {
    List<LegAccount> legs =
        postings.stream().map(p -> new LegAccount(p, requireAccount(p.accountId()))).toList();
    if (legs.stream().anyMatch(l -> l.posting().baseAmount() != null)) {
      throw new IllegalArgumentException(NOT_A_SPLIT); // cross-currency is not a dock/panel edit
    }
    List<LegAccount> fundingLegs = legsOfType(legs, OWN_TYPES);
    List<LegAccount> categoryLegs = legsOfType(legs, CATEGORY_TYPES);
    if (fundingLegs.size() != 1
        || categoryLegs.size() < 2
        || fundingLegs.size() + categoryLegs.size() != legs.size()) {
      throw new IllegalArgumentException(NOT_A_SPLIT);
    }
    return new SplitLegs(fundingLegs.get(0), categoryLegs);
  }

  /**
   * The magnitude the user would type for a category leg (register §3.8, the mixed-split rule),
   * inverting {@link #signedContribution}: an income leg's typed value is {@code −amount}, an
   * expense leg's is {@code +amount}; a negative result is a storno and carries a leading {@code
   * −}.
   */
  static String amountText(BigDecimal legAmount, String categoryType) {
    BigDecimal typed = INCOME.equals(categoryType) ? legAmount.negate() : legAmount;
    String magnitude = MoneyFormat.number(typed.abs(), FRACTION_DIGITS);
    return typed.signum() < 0 ? "-" + magnitude : magnitude;
  }

  private static List<LegAccount> legsOfType(List<LegAccount> legs, List<String> types) {
    return legs.stream().filter(l -> types.contains(l.account().type())).toList();
  }

  /**
   * The <em>semantic</em> category the user picked: the parent when the leg hits a per-currency
   * leaf (named {@code "<Parent> <CODE>"} by the §6.5 routing), otherwise the leaf itself — so a
   * re-save routes back through {@code resolveCurrencyLeaf} to the same leaf. Mirrors {@link
   * DockEditService}'s reconstruction for the simple dock.
   */
  private Account semanticCategory(Account leaf) {
    if (leaf.parentId() == null) {
      return leaf;
    }
    return accountService
        .findById(leaf.parentId())
        .filter(parent -> leaf.name().equals(parent.name() + " " + leaf.currencyCode()))
        .orElse(leaf);
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
