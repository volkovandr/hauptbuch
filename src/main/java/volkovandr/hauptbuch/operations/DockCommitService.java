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
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.ledger.TransactionDraft;
import volkovandr.hauptbuch.ledger.TransferTarget;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * The entry dock's commit path for a simple single-category transaction (register §3, plan stages
 * 7b/7d.1) — same-currency or cross-currency. Lives in {@code operations} — it must orchestrate
 * payee resolution ({@code ledger}), per-currency-leaf resolution ({@link CurrencyLeafService},
 * which can subdivide), and {@code recordTransaction} ({@code ledger}), and a dock controller in
 * {@code ledger} could reach none of those without closing a module cycle (the 6d precedent — plan
 * stage 7 boundary note).
 *
 * <p>Category <em>create-new</em> is deliberately <em>not</em> here: it is {@code categories}' own
 * logic (the 6b path), and {@code operations → categories} would cycle with {@code categories →
 * operations}. The dock resolves a new category through the {@code categories} screen first, so
 * this service always receives an existing category id (see the dock controller).
 *
 * <p>The sign resolution (register §3.8) is the load-bearing single-currency logic: the amount is a
 * bare magnitude whose direction the counterpart's type fixes, unless an explicit leading {@code
 * +}/{@code −} overrides it (the refund/reversal case). When the category-currency selector
 * overrides the leaf's currency away from the funding account's (register §3.5), the transaction is
 * cross-currency (§3.8a): the category leg gets its own native magnitude, and every leg's {@code
 * baseAmount} is derived so the pair always balances in base by construction — either directly from
 * whichever leg is already the book's base currency, or, when neither is, from the confirmed
 * base-amount field (data-model §6.4). The engine still re-validates the sum (data-model §6.3);
 * this derivation just means that check can never actually fail for a two-leg entry.
 */
@Service
public class DockCommitService {

  /** The Unicode minus sign, accepted as an override alongside the ASCII hyphen-minus. */
  private static final char UNICODE_MINUS = '−';

  private final AccountService accountService;
  private final PayeeService payeeService;
  private final CurrencyLeafService currencyLeafService;
  private final LedgerService ledgerService;
  private final SettingsService settingsService;

  DockCommitService(
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
   * Commit a simple dock entry (register §3.1): resolve the payee (existing or create-new), route
   * the category to its per-currency leaf (§6.5), resolve the sign-free amount into a balanced pair
   * of legs (§3.8), and either <em>record</em> a new transaction or <em>re-thread</em> an existing
   * one through the engine. Which one is decided by {@link DockEntry#transactionId()}: {@code null}
   * records; a non-null id edits in place ({@code editTransaction}) — changing the account or date
   * re-threads both affected balance threads (register §3.3), which the caller repaints with the
   * same bounded re-fetch as a backdated insert.
   *
   * @return the affected transaction's id (the new one, or the edited one unchanged)
   * @throws IllegalArgumentException if the funding account is unknown or the amount is unparseable
   */
  @Transactional
  public long commit(DockEntry entry) {
    Account fundingAccount =
        accountService
            .findById(entry.accountId())
            .orElseThrow(
                () -> new IllegalArgumentException("No account with id " + entry.accountId()));
    Long payeeId = payeeService.resolvePayee(entry.payeeId(), entry.payeeText());

    Counterpart counterpart = resolveCounterpart(entry, fundingAccount);
    BigDecimal fundingAmount = signedAmount(entry.amount(), counterpart.defaultOutflow());
    Account other = counterpart.account();
    List<PostingDraft> legs =
        fundingAccount.currencyCode().equals(other.currencyCode())
            ? List.of(
                PostingDraft.of(fundingAccount.accountId(), fundingAmount),
                PostingDraft.of(other.accountId(), fundingAmount.negate()))
            : crossCurrencyLegs(entry, fundingAccount, other, fundingAmount);

    TransactionDraft draft = TransactionDraft.confirmed(entry.date(), payeeId, entry.note(), legs);

    if (entry.transactionId() == null) {
      return ledgerService.recordTransaction(draft);
    }
    ledgerService.editTransaction(entry.transactionId(), draft);
    return entry.transactionId();
  }

  /**
   * Resolve the entry's counter-leg (register §3.5/§3.8): a <em>transfer</em> to a real own account
   * when {@link DockEntry#transferDirection()} is set (its currency fixed by the account, its
   * funding direction from {@code TO}/{@code FROM}), otherwise the picked category's per-currency
   * leaf (routed to the overridden currency, or the funding account's by default — §6.5). The
   * returned {@link Counterpart} carries the account plus the funding leg's default direction
   * (which an explicit {@code +}/{@code −} on the amount can still override, §3.8).
   */
  private Counterpart resolveCounterpart(DockEntry entry, Account fundingAccount) {
    String direction = blankToNull(entry.transferDirection());
    if (direction != null) {
      Account other =
          accountService
              .findById(entry.categoryId())
              .orElseThrow(
                  () -> new IllegalArgumentException("No account with id " + entry.categoryId()));
      if (other.accountId().equals(fundingAccount.accountId())) {
        throw new IllegalArgumentException("A transfer needs two different accounts");
      }
      return new Counterpart(other, TransferTarget.Direction.TO.name().equals(direction));
    }
    String override = blankToNull(entry.categoryCurrencyCode());
    String targetCurrency = override == null ? fundingAccount.currencyCode() : override;
    Account leaf = currencyLeafService.resolveCurrencyLeaf(entry.categoryId(), targetCurrency);
    return new Counterpart(leaf, "expense".equals(leaf.type()));
  }

  /**
   * The resolved counter-leg: the account it hits, and whether the funding leg defaults to an
   * outflow (an expense category, or a {@code TO} transfer). The sign convention is identical for
   * both kinds — only the source of the default direction differs (register §3.8).
   */
  private record Counterpart(Account account, boolean defaultOutflow) {}

  /**
   * Build the cross-currency pair (register §3.8a): the counterpart leg gets its own native
   * magnitude (opposite sign from the funding leg), and both legs' {@code baseAmount} are derived
   * so they always balance in base by construction (data-model §6.4) —
   *
   * <ul>
   *   <li>funding leg already base → its {@code baseAmount} is its own amount, the counterpart
   *       leg's is the negation ("no separate base field is shown", register §3.8a);
   *   <li>counterpart leg already base → the same, mirrored;
   *   <li>neither is base → the confirmed base-amount field is the funding leg's magnitude (signed
   *       to match its direction), the counterpart leg's is the negation.
   * </ul>
   *
   * <p>The counterpart is a category leaf or a real transfer account (both are just accounts here);
   * the frozen-base derivation is the same either way.
   */
  private List<PostingDraft> crossCurrencyLegs(
      DockEntry entry, Account fundingAccount, Account counterpart, BigDecimal fundingAmount) {
    BigDecimal counterpartMagnitude =
        requiredMagnitude(entry.categoryAmount(), counterpart.currencyCode());
    BigDecimal counterpartAmount =
        fundingAmount.signum() < 0 ? counterpartMagnitude : counterpartMagnitude.negate();

    String baseCurrency =
        settingsService
            .baseCurrency()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Base currency is not set; a cross-currency transaction needs it to "
                            + "balance (data-model §3.8)"));

    BigDecimal fundingBase;
    BigDecimal counterpartBase;
    if (fundingAccount.currencyCode().equals(baseCurrency)) {
      fundingBase = fundingAmount;
      counterpartBase = fundingAmount.negate();
    } else if (counterpart.currencyCode().equals(baseCurrency)) {
      counterpartBase = counterpartAmount;
      fundingBase = counterpartAmount.negate();
    } else {
      BigDecimal baseMagnitude = requiredMagnitude(entry.baseAmount(), baseCurrency);
      fundingBase = fundingAmount.signum() < 0 ? baseMagnitude.negate() : baseMagnitude;
      counterpartBase = fundingBase.negate();
    }

    return List.of(
        PostingDraft.ofCrossCurrency(fundingAccount.accountId(), fundingAmount, fundingBase),
        PostingDraft.ofCrossCurrency(counterpart.accountId(), counterpartAmount, counterpartBase));
  }

  /**
   * A plain (sign-free) required magnitude for the category-amount / base-amount fields — unlike
   * the funding leg, these carry no explicit-sign override semantics (their direction is always the
   * negation of the funding leg's, register §3.8a).
   */
  private static BigDecimal requiredMagnitude(String text, String currencyCode) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("A " + currencyCode + " amount is required");
    }
    return MoneyFormat.parse(text).abs();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /**
   * Void a transaction from the dock's edit mode (register §3.1): a reversible soft-delete
   * (data-model §3.5). Delegates straight to the engine — voiding upholds no dock-specific rule, so
   * this is a thin pass-through the dock controller calls beside {@link #commit}.
   *
   * @throws IllegalArgumentException if there is no live transaction with that id
   */
  @Transactional
  public void voidTransaction(long transactionId) {
    ledgerService.voidTransaction(transactionId);
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
    // The counterpart type fixes the default direction: expense is an outflow, income an inflow.
    return signedAmount(amountText, "expense".equals(counterpartType));
  }

  /**
   * The signed funding-leg amount (register §3.8): a bare magnitude signed by {@code
   * defaultOutflow} — the counterpart's default direction (an expense category, or a {@code TO}
   * transfer) — unless a leading {@code +}/{@code −} overrides it ({@code +} = funds enter, {@code
   * −} = funds leave), the refund/reversal case a sign-free scheme cannot otherwise express.
   *
   * @param amountText the typed amount, German-formatted, with an optional leading sign
   * @param defaultOutflow whether the counterpart makes the funding leg an outflow by default
   */
  static BigDecimal signedAmount(String amountText, boolean defaultOutflow) {
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
      outflow = defaultOutflow;
    }
    return outflow ? magnitude.negate() : magnitude;
  }
}
