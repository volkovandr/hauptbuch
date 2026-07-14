package volkovandr.hauptbuch.operations;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import volkovandr.hauptbuch.ledger.SettingsService;
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
  private static final String EXPENSE = "expense";
  private static final String ASSET = "asset";
  private static final String LIABILITY = "liability";
  private static final List<String> OWN_TYPES = List.of(ASSET, LIABILITY);
  private static final List<String> CATEGORY_TYPES = List.of(INCOME, EXPENSE);

  /** German entry is to the minor unit; two places covers EUR/CHF. */
  private static final int FRACTION_DIGITS = 2;

  private static final String NOT_A_SPLIT =
      "This transaction cannot be edited as a split — the split panel edits one funding leg "
          + "with two or more category lines.";

  /** Base amounts are frozen to the minor unit; two places covers EUR/CHF/USD. */
  private static final int BASE_FRACTION_DIGITS = 2;

  /** Intermediate scale for the proportional base allocation before rounding to the minor unit. */
  private static final int ALLOCATION_SCALE = 10;

  private final AccountService accountService;
  private final PayeeService payeeService;
  private final CurrencyLeafService currencyLeafService;
  private final LedgerService ledgerService;
  private final SettingsService settingsService;

  DockSplitService(
      AccountService accountService,
      PayeeService payeeService,
      CurrencyLeafService currencyLeafService,
      LedgerService ledgerService,
      SettingsService settingsService) {
    this.accountService = accountService;
    this.payeeService = payeeService;
    this.currencyLeafService = currencyLeafService;
    this.ledgerService = ledgerService;
    this.settingsService = settingsService;
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

    String spending = blankToNull(entry.spendingCurrencyCode());
    boolean crossCurrency = spending != null && !spending.equals(fundingAccount.currencyCode());
    List<PostingDraft> legs =
        crossCurrency
            ? crossCurrencyLegs(entry, fundingAccount, spending)
            : sameCurrencyLegs(entry, fundingAccount);

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
   * The single-currency split (register §3.10, plan stage 7c.2): every line routes to the funding
   * account's own currency leaf (or, for a transfer line, the named real own account — register
   * §3.8, plan stage 7d.3), its signed contribution feeds the funding sum, and each counter-leg is
   * the negated contribution — the whole set summing to zero natively by construction.
   */
  private List<PostingDraft> sameCurrencyLegs(SplitEntry entry, Account fundingAccount) {
    List<PostingDraft> counterLegs = new ArrayList<>();
    BigDecimal fundingAmount = BigDecimal.ZERO;
    for (SplitLineDraft line : entry.lines()) {
      ResolvedLine resolved = resolveLine(line, fundingAccount, fundingAccount.currencyCode());
      fundingAmount = fundingAmount.add(resolved.contribution());
      counterLegs.add(
          PostingDraft.of(
              resolved.leaf().accountId(), resolved.contribution().negate(), resolved.note()));
    }

    List<PostingDraft> legs = new ArrayList<>();
    legs.add(PostingDraft.of(fundingAccount.accountId(), fundingAmount));
    legs.addAll(counterLegs);
    return legs;
  }

  /**
   * Resolve one split line into its counter-leg account and signed contribution (register §3.8): a
   * <em>transfer</em> to a real own account when {@link SplitLineDraft#transferDirection()} is set
   * — its currency fixed by the account, its sign from {@code TO}/{@code FROM} ({@code TO} = an
   * outflow, like an expense; {@code FROM} = an inflow, like income) — otherwise the picked
   * category's per-currency leaf routed to {@code lineCurrency}, signed by the leaf's type. A
   * transfer's account must be denominated in {@code lineCurrency}: the split's shared rate fixes
   * at most two currencies at the header (register §3.8a), so a third-currency transfer leg cannot
   * be expressed and is refused. A storno (a leading {@code −}) flows through either path.
   */
  private ResolvedLine resolveLine(
      SplitLineDraft line, Account fundingAccount, String lineCurrency) {
    String direction = blankToNull(line.transferDirection());
    if (direction != null) {
      Account other =
          accountService
              .findById(line.categoryId())
              .orElseThrow(
                  () -> new IllegalArgumentException("No account with id " + line.categoryId()));
      if (other.accountId().equals(fundingAccount.accountId())) {
        throw new IllegalArgumentException("A transfer needs two different accounts");
      }
      if (!other.currencyCode().equals(lineCurrency)) {
        throw new IllegalArgumentException(
            "A transfer line must target a "
                + lineCurrency
                + " account ("
                + other.name()
                + " is "
                + other.currencyCode()
                + "); a receipt mixing more currencies is two transactions");
      }
      return new ResolvedLine(
          other,
          SplitLineAmounts.transferContribution(line.amount(), direction),
          blankToNull(line.note()));
    }
    Account leaf = currencyLeafService.resolveCurrencyLeaf(line.categoryId(), lineCurrency);
    return new ResolvedLine(
        leaf,
        SplitLineAmounts.signedContribution(line.amount(), leaf.type()),
        blankToNull(line.note()));
  }

  /**
   * The cross-currency split (register §3.8a/§3.10, plan stage 7d.2, owner-decided 2026-07-13). A
   * single receipt is one merchant billing one spending currency, paid from one account at one
   * rate, so the currencies are fixed once at the header and the ledger balances in <em>base</em>:
   *
   * <ul>
   *   <li>the <strong>funding leg is pinned to the header totals</strong> — native {@code
   *       ±fundingTotal} (funding currency), base {@code ±baseTotal} — signed by the lines' net
   *       direction (a pure-expense split is an outflow);
   *   <li>each <strong>category leg</strong> is in the spending currency: native the line's negated
   *       contribution (unchanged from the single-currency rule), base the line's derived share of
   *       the base total, allocated proportionally by spending magnitude;
   *   <li>the <strong>last line absorbs the rounding residual</strong> so the category legs' base
   *       amounts sum to exactly {@code ∓baseTotal} and {@code Σ base_amount = 0} holds exactly
   *       (data-model §6.4 — the engine books no residual and re-validates the base sum).
   * </ul>
   */
  private List<PostingDraft> crossCurrencyLegs(
      SplitEntry entry, Account fundingAccount, String spendingCurrency) {
    String baseCurrency =
        settingsService
            .baseCurrency()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Base currency is not set; a cross-currency split needs it to balance "
                            + "(data-model §3.8)"));

    List<ResolvedLine> resolved = new ArrayList<>();
    BigDecimal netSpending = BigDecimal.ZERO;
    BigDecimal spendingMagnitude = BigDecimal.ZERO;
    for (SplitLineDraft line : entry.lines()) {
      ResolvedLine resolvedLine = resolveLine(line, fundingAccount, spendingCurrency);
      netSpending = netSpending.add(resolvedLine.contribution());
      spendingMagnitude = spendingMagnitude.add(resolvedLine.contribution().abs());
      resolved.add(resolvedLine);
    }

    // The funding side (register §3.8, mixed-split convention): outflow when the lines net to a
    // debit of the categories, else inflow; an exactly-zero net books on the debit side.
    int fundingSign = netSpending.signum() < 0 ? -1 : 1;
    BigDecimal fundingMagnitude =
        requiredMagnitude(entry.fundingTotal(), fundingAccount.currencyCode());
    BigDecimal baseMagnitude =
        baseTotalMagnitude(
            entry, fundingAccount, spendingCurrency, baseCurrency, spendingMagnitude);
    BigDecimal fundingNative = signed(fundingMagnitude, fundingSign);
    BigDecimal fundingBase = signed(baseMagnitude, fundingSign);

    List<PostingDraft> legs = new ArrayList<>();
    legs.add(PostingDraft.ofCrossCurrency(fundingAccount.accountId(), fundingNative, fundingBase));
    legs.addAll(
        categoryLegsInBase(resolved, spendingMagnitude, baseMagnitude, fundingBase.negate()));
    return legs;
  }

  /**
   * Build the spending-currency category legs, freezing each one's base amount as its share of the
   * base total (proportional to its spending magnitude) with the last line absorbing the residual
   * so the legs' base amounts sum to exactly {@code targetBaseSum} ({@code = −fundingBase}).
   */
  private List<PostingDraft> categoryLegsInBase(
      List<ResolvedLine> resolved,
      BigDecimal spendingMagnitude,
      BigDecimal baseMagnitude,
      BigDecimal targetBaseSum) {
    List<PostingDraft> legs = new ArrayList<>();
    BigDecimal allocated = BigDecimal.ZERO;
    for (int i = 0; i < resolved.size(); i++) {
      ResolvedLine line = resolved.get(i);
      BigDecimal categoryNative = line.contribution().negate();
      BigDecimal categoryBase;
      if (i == resolved.size() - 1) {
        categoryBase = targetBaseSum.subtract(allocated); // the last line closes the base gap
      } else {
        BigDecimal share =
            proportionalBase(line.contribution().abs(), spendingMagnitude, baseMagnitude);
        categoryBase = signed(share, categoryNative.signum());
        allocated = allocated.add(categoryBase);
      }
      legs.add(
          PostingDraft.ofCrossCurrency(
              line.leaf().accountId(), categoryNative, categoryBase, line.note()));
    }
    return legs;
  }

  /** A line's proportional share of the base total, rounded to the minor unit (magnitude only). */
  private static BigDecimal proportionalBase(
      BigDecimal lineMagnitude, BigDecimal spendingMagnitude, BigDecimal baseMagnitude) {
    if (spendingMagnitude.signum() == 0) {
      return BigDecimal.ZERO;
    }
    return lineMagnitude
        .divide(spendingMagnitude, ALLOCATION_SCALE, RoundingMode.HALF_UP)
        .multiply(baseMagnitude)
        .setScale(BASE_FRACTION_DIGITS, RoundingMode.HALF_UP);
  }

  /**
   * The base-currency total magnitude (the funding leg's frozen base), following the header's field
   * layout (register §3.8a): when the funding account is already the base currency it is the
   * funding total; when the spending currency is base the lines are already in base, so it is their
   * summed magnitude; otherwise neither leg is base and it is the explicit base-total field.
   */
  private static BigDecimal baseTotalMagnitude(
      SplitEntry entry,
      Account fundingAccount,
      String spendingCurrency,
      String baseCurrency,
      BigDecimal spendingMagnitude) {
    if (fundingAccount.currencyCode().equals(baseCurrency)) {
      return requiredMagnitude(entry.fundingTotal(), baseCurrency);
    }
    if (spendingCurrency.equals(baseCurrency)) {
      return spendingMagnitude;
    }
    return requiredMagnitude(entry.baseTotal(), baseCurrency);
  }

  private static BigDecimal signed(BigDecimal magnitude, int sign) {
    return sign < 0 ? magnitude.negate() : magnitude;
  }

  /**
   * A required sign-free magnitude for a header total field; rejects blank with a clear message.
   */
  private static BigDecimal requiredMagnitude(String text, String currencyCode) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("A " + currencyCode + " total is required");
    }
    return MoneyFormat.parse(text).abs();
  }

  /**
   * A resolved split line: its counter-leg account (a category currency leaf, or — for a transfer
   * line — the real own account), its signed contribution, and note.
   */
  private record ResolvedLine(Account leaf, BigDecimal contribution, String note) {}

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /**
   * Reconstruct a live split transaction into a {@link SplitForm} for the panel's edit mode
   * (register §3.1) — the split-shaped sibling of {@link DockEditService#load}. The shape the panel
   * edits is one funding own-account leg (asset/liability) plus two or more category legs
   * (income/expense); a same-currency split has all legs in one currency, a cross-currency one
   * (register §3.8a/§3.10) has the category legs in a single spending currency with frozen base
   * amounts. Anything else (a transfer, an opening balance, or a simple one-category transaction —
   * which the dock edits) is refused so the caller can fall back. Each line's typed amount is
   * reconstructed from its leg so a re-save reproduces the same postings; the {@code view*} filter
   * fields are left null (the panel renders them from the active-filter view model, not the
   * transaction).
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
      spendingTotal = spendingTotal.add(leg.posting().amount().abs());
    }

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
