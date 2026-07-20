package volkovandr.hauptbuch.operations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.PersonProvisioningService;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.ledger.PostingDraft;
import volkovandr.hauptbuch.ledger.SettingsService;
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
 *
 * <p><strong>Person lines (register §3.5, plan stage 8b.2).</strong> A line may instead attribute
 * its share to a person ({@code for}/{@code by}), which is how one receipt expresses multi-person
 * attribution — "€31,50: €21,50 my food, €10 for Max" (register §2.6). Unlike a category, a
 * person's per-currency debt leaf is <em>provisioned here, at commit</em> (data-model §7): it may
 * not exist yet, and its currency is only known once the line's currency is fixed. Everything
 * downstream is unchanged — the leaf is an ordinary {@code asset} account and its leg is signed and
 * summed exactly like any other.
 */
@Service
public class DockSplitService {

  /** Base amounts are frozen to the minor unit; two places covers EUR/CHF/USD. */
  private static final int BASE_FRACTION_DIGITS = 2;

  /** Intermediate scale for the proportional base allocation before rounding to the minor unit. */
  private static final int ALLOCATION_SCALE = 10;

  private final AccountService accountService;
  private final PayeeService payeeService;
  private final CurrencyLeafService currencyLeafService;
  private final LedgerService ledgerService;
  private final SettingsService settingsService;
  private final PersonProvisioningService personProvisioningService;

  DockSplitService(
      AccountService accountService,
      PayeeService payeeService,
      CurrencyLeafService currencyLeafService,
      LedgerService ledgerService,
      SettingsService settingsService,
      PersonProvisioningService personProvisioningService) {
    this.accountService = accountService;
    this.payeeService = payeeService;
    this.currencyLeafService = currencyLeafService;
    this.ledgerService = ledgerService;
    this.settingsService = settingsService;
    this.personProvisioningService = personProvisioningService;
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
          tagged(
              PostingDraft.of(
                  resolved.leaf().accountId(), resolved.contribution().negate(), resolved.note()),
              resolved.tagIds()));
    }

    List<PostingDraft> legs = new ArrayList<>();
    legs.add(tagged(PostingDraft.of(fundingAccount.accountId(), fundingAmount), entry.tagIds()));
    legs.addAll(counterLegs);
    return legs;
  }

  /**
   * Resolve one split line into its counter-leg account and signed contribution (register §3.8):
   *
   * <ul>
   *   <li>a <em>transfer</em> to a real own account when {@link SplitLineDraft#transferDirection()}
   *       is set — its currency fixed by the account, its sign from {@code TO}/{@code FROM} ({@code
   *       TO} = an outflow, like an expense; {@code FROM} = an inflow, like income);
   *   <li>a <em>person</em> (register §3.5, plan stage 8b.2) when {@link
   *       SplitLineDraft#personName()}/{@link SplitLineDraft#personDirection()} are set — their
   *       per-currency debt leaf, auto-provisioned here at commit in {@code lineCurrency} (so a
   *       cross-currency split attributes the line in its spending currency, exactly as a category
   *       line is routed), its sign from {@code FOR}/{@code BY};
   *   <li>otherwise the picked category's per-currency leaf routed to {@code lineCurrency}, signed
   *       by the leaf's type.
   * </ul>
   *
   * <p>A transfer's account must be denominated in {@code lineCurrency}: the split's shared rate
   * fixes at most two currencies at the header (register §3.8a), so a third-currency transfer leg
   * cannot be expressed and is refused. A person's leaf needs no such check — it is provisioned in
   * {@code lineCurrency}. A storno (a leading {@code −}) flows through every path.
   */
  private ResolvedLine resolveLine(
      SplitLineDraft line, Account fundingAccount, String lineCurrency) {
    String direction = blankToNull(line.transferDirection());
    if (direction != null) {
      Account other =
          accountService
              .findById(requireCategoryId(line))
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
          blankToNull(line.note()),
          line.tagIds());
    }
    String personName = blankToNull(line.personName());
    String personDirection = blankToNull(line.personDirection());
    if (personName != null && personDirection != null) {
      boolean revive = "true".equalsIgnoreCase(blankToNull(line.personRevive()));
      Account leaf = personProvisioningService.ensureLeaf(personName, lineCurrency, revive);
      return new ResolvedLine(
          leaf,
          SplitLineAmounts.personContribution(line.amount(), personDirection),
          blankToNull(line.note()),
          line.tagIds());
    }
    Account leaf = currencyLeafService.resolveCurrencyLeaf(requireCategoryId(line), lineCurrency);
    return new ResolvedLine(
        leaf,
        SplitLineAmounts.signedContribution(line.amount(), leaf.type()),
        blankToNull(line.note()),
        line.tagIds());
  }

  /**
   * The line's category/account id, required by every branch except a person line (whose leaf does
   * not exist until commit). Blank is already refused at binding time (a line needs a category);
   * this is the commit-side backstop for a caller that built the draft directly.
   */
  private static long requireCategoryId(SplitLineDraft line) {
    if (line.categoryId() == null) {
      throw new IllegalArgumentException("Each split line needs a category (pick or create one)");
    }
    return line.categoryId();
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
    legs.add(
        tagged(
            PostingDraft.ofCrossCurrency(fundingAccount.accountId(), fundingNative, fundingBase),
            entry.tagIds()));
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
          tagged(
              PostingDraft.ofCrossCurrency(
                  line.leaf().accountId(), categoryNative, categoryBase, line.note()),
              line.tagIds()));
    }
    return legs;
  }

  /**
   * Attach a leg's tags (data-model §10.2, owner decision 2026-07-14) — the funding leg carries the
   * transaction-level tags, each category leg its own line's chips. The ids are de-duplicated so a
   * doubly-picked chip can never violate the {@code posting_tag} unique constraint; an untagged leg
   * (the common case) is returned unchanged.
   */
  private static PostingDraft tagged(PostingDraft leg, List<Long> tagIds) {
    List<Long> distinct = tagIds.stream().distinct().toList();
    return distinct.isEmpty() ? leg : leg.withTags(distinct);
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
   * line — the real own account), its signed contribution, note, and the line's own tag ids.
   */
  private record ResolvedLine(
      Account leaf, BigDecimal contribution, String note, List<Long> tagIds) {}

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
