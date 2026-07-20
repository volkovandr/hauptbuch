package volkovandr.hauptbuch.operations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.debts.PersonTarget;
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
 * <p>Edit mode covers: simple single-category transactions (one own account, one category, single
 * currency); cross-currency single-line transactions (frozen base-amount on the category leg); and
 * transfers (two own-account legs, same- or cross-currency). Opening balances (an equity leg),
 * splits, and splits with transfers are refused. The one piece of real logic is reconstructing the
 * sign-free amount text (register §3.8) and (for cross-currency/transfers) the category and base
 * amounts so a re-save maps back to the same legs; it is unit-tested.
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

  /** A transaction must have exactly two legs to be editable in the dock. */
  private static final int EXPECTED_LEG_COUNT = 2;

  private static final String NOT_EDITABLE =
      "This transaction cannot be edited in the dock yet — edit mode covers simple "
          + "single-category transactions, same/cross-currency transfers, and splits (with or "
          + "without transfer lines), but not opening balances.";

  private final LedgerService ledgerService;
  private final AccountService accountService;
  private final PayeeService payeeService;
  private final PersonService personService;

  DockEditService(
      LedgerService ledgerService,
      AccountService accountService,
      PayeeService payeeService,
      PersonService personService) {
    this.ledgerService = ledgerService;
    this.accountService = accountService;
    this.payeeService = payeeService;
    this.personService = personService;
  }

  /**
   * Reconstruct the dock's fields from a live transaction (register §3.1).
   *
   * @throws IllegalArgumentException if there is no live transaction with that id, or it is not an
   *     editable shape
   */
  public DockEditModel load(long transactionId) {
    Transaction txn =
        ledgerService
            .findTransaction(transactionId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "No live transaction with id " + transactionId + " to edit"));

    EditableLegs legs = classify(ledgerService.findPostings(transactionId));

    String payeeText =
        txn.payeeId() == null ? null : payeeService.entryValueFor(txn.payeeId()).orElse(null);

    String counterpartTypeForAmountText =
        legs.transferDirection() != null
            ? ("TO".equals(legs.transferDirection()) ? EXPENSE : INCOME)
            : legs.counterpartType();

    // Populate categoryCurrencyCode only when it differs from the funding account's currency
    String categoryCurrencyCode = null;
    if (legs.categoryAmount() != null) {
      // Cross-currency: the counterpart has a different currency
      categoryCurrencyCode = legs.counterpartCurrency();
    }

    return new DockEditModel(
        txn.transactionId(),
        txn.date(),
        legs.fundingAccount().accountId(),
        accountEntryText(legs),
        payeeText,
        amountText(legs.fundingLeg().amount(), counterpartTypeForAmountText),
        legs.counterpartId(),
        legs.counterpartName(),
        categoryCurrencyCode,
        legs.categoryAmount(),
        legs.baseAmount(),
        legs.transferDirection(),
        txn.note(),
        ledgerService.tagsForTransaction(transactionId));
  }

  /**
   * The pre-filled <em>new</em> dock a successful commit resets to (register §3.3, plan stage
   * 8b.1): the date and funding account just used echo back, so entering several transactions on
   * the same account and day needs no re-typing. Everything else — payee, amount, category, note,
   * tags — is deliberately left blank; only the two fields that are usually unchanged stick.
   *
   * <p>A <em>person</em> funding leg is never sticky: an unnoticed sticky person would silently
   * book a debt, and possibly provision a leaf, against the next transaction the user enters. Such
   * a commit returns {@code null} here and the caller falls back to the ordinary fresh default.
   *
   * @param accountId the funding account just committed, or {@code null} when it was a person
   * @param date the date just committed
   * @return the pre-filled dock model, or {@code null} to fall back to a bare reset
   */
  public DockEditModel stickyAfterCommit(Long accountId, LocalDate date) {
    if (accountId == null || date == null) {
      return null;
    }
    return accountService
        .findById(accountId)
        .filter(a -> !a.personLeaf())
        .map(
            a ->
                new DockEditModel(
                    null,
                    date,
                    a.accountId(),
                    a.name() + " (" + a.currencyCode() + ")",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of()))
        .orElse(null);
  }

  /**
   * The value the dock's Account input shows for a re-opened transaction (register §3.3, plan stage
   * 8b.1). An ordinary funding account shows {@code Name (CUR)}; a person's debt leaf shows the
   * {@code for}/{@code by} sigil instead, never the cosmetic {@code personal.<CUR>} leaf name —
   * both forms re-resolve through {@code /register/account/resolve}, so an untouched edit
   * round-trips to the same funding leg.
   *
   * <p>The sigil follows the leg's own sign, the same rule the register displays by (§3.5): the
   * person's leg on the <em>debit</em> side (positive) is {@code for} — they owe you — and on the
   * <em>credit</em> side (negative) is {@code by}.
   */
  private String accountEntryText(EditableLegs legs) {
    return personService
        .personNameForAccount(legs.fundingAccount().accountId())
        .map(
            name ->
                PersonTarget.option(
                    legs.fundingLeg().amount().signum() < 0
                        ? PersonTarget.Direction.BY
                        : PersonTarget.Direction.FOR,
                    name))
        .orElseGet(
            () -> legs.fundingAccount().name() + " (" + legs.fundingAccount().currencyCode() + ")");
  }

  /**
   * Split a transaction's postings into an editable shape: exactly two postings, one of which is an
   * own-account funding leg, and the other is either (a) a category leg (income/expense) making it
   * a simple single-category transaction, or (b) another own-account leg making it a transfer.
   * Refuse anything else (more/fewer than two legs, an equity leg) with the {@link #NOT_EDITABLE}
   * message.
   */
  private EditableLegs classify(List<Posting> postings) {
    if (postings.size() != EXPECTED_LEG_COUNT) {
      throw new IllegalArgumentException(NOT_EDITABLE);
    }
    List<LegAccount> legs =
        postings.stream().map(p -> new LegAccount(p, requireAccount(p.accountId()))).toList();

    ClassifiedLegs classified = classifyLegTypes(legs);
    if (classified.ownLeg2() != null && classified.categoryLeg() != null) {
      throw new IllegalArgumentException(NOT_EDITABLE); // More than one own + a category
    }
    if (classified.ownLeg2() != null) {
      return classifyTransfer(classified.ownLeg1(), classified.ownLeg2());
    }
    if (classified.categoryLeg() != null) {
      return classifyCategory(classified.ownLeg1(), classified.categoryLeg());
    }
    throw new IllegalArgumentException(NOT_EDITABLE); // No own account or no category
  }

  /**
   * Group legs by type (own-account or category). Returns at most one own-account leg in ownLeg1,
   * zero or one in ownLeg2, and zero or one category leg.
   */
  private ClassifiedLegs classifyLegTypes(List<LegAccount> legs) {
    LegAccount ownLeg1 = null;
    LegAccount ownLeg2 = null;
    LegAccount categoryLeg = null;

    for (LegAccount leg : legs) {
      if (OWN_TYPES.contains(leg.account().type())) {
        if (ownLeg1 == null) {
          ownLeg1 = leg;
        } else {
          ownLeg2 = leg;
        }
      } else if (CATEGORY_TYPES.contains(leg.account().type())) {
        categoryLeg = leg;
      }
    }
    return new ClassifiedLegs(ownLeg1, ownLeg2, categoryLeg);
  }

  private record ClassifiedLegs(LegAccount ownLeg1, LegAccount ownLeg2, LegAccount categoryLeg) {}

  private EditableLegs classifyTransfer(LegAccount own1, LegAccount own2) {
    // Determine which is the funding account (the one with negative amount).
    LegAccount funding;
    LegAccount counterpart;
    if (own1.posting().amount().signum() < 0) {
      funding = own1;
      counterpart = own2;
    } else {
      funding = own2;
      counterpart = own1;
    }
    String direction = "TO"; // TO = funding leg outflows (negative)

    String categoryAmount = null;
    String baseAmount = null;
    if (own1.posting().baseAmount() != null || own2.posting().baseAmount() != null) {
      // Cross-currency transfer: reconstruct amounts for the dock
      categoryAmount =
          MoneyFormat.number(counterpart.posting().amount().abs(), AMOUNT_FRACTION_DIGITS);
      BigDecimal base =
          funding.posting().baseAmount() != null
              ? funding.posting().baseAmount()
              : counterpart.posting().baseAmount();
      baseAmount = MoneyFormat.number(base.abs(), AMOUNT_FRACTION_DIGITS);
    }

    return new EditableLegs(
        funding.posting(),
        funding.account(),
        counterpart.account().accountId(),
        counterpart.account().name(),
        counterpart.account().type(),
        counterpart.account().currencyCode(),
        categoryAmount,
        baseAmount,
        direction);
  }

  /**
   * The category shape: the <em>own</em> leg funds it and the category leg is the counterpart,
   * whichever way the money ran. The sign is the direction, not the role — an expense credits the
   * own leg, income and a refund debit it — so picking the funding leg by sign would hand back the
   * category as the account (and the account as the category) for every inflow.
   */
  private EditableLegs classifyCategory(LegAccount own, LegAccount category) {
    Account semanticCategory = semanticCategory(category.account());

    String categoryAmount = null;
    String baseAmount = null;
    if (category.posting().baseAmount() != null) {
      // Cross-currency: the category leg has a frozen baseAmount
      categoryAmount =
          MoneyFormat.number(category.posting().amount().abs(), AMOUNT_FRACTION_DIGITS);
      BigDecimal base =
          own.posting().baseAmount() != null
              ? own.posting().baseAmount()
              : category.posting().baseAmount();
      baseAmount = MoneyFormat.number(base.abs(), AMOUNT_FRACTION_DIGITS);
    }

    return new EditableLegs(
        own.posting(),
        own.account(),
        semanticCategory.accountId(),
        semanticCategory.name(),
        semanticCategory.type(),
        category.account().currencyCode(),
        categoryAmount,
        baseAmount,
        null);
  }

  /** A posting paired with its (already-resolved) account. */
  private record LegAccount(Posting posting, Account account) {}

  /**
   * The classified legs of an editable transaction: the funding leg/account and the counterpart
   * (category or transfer account), along with cross-currency amounts if applicable.
   */
  private record EditableLegs(
      Posting fundingLeg,
      Account fundingAccount,
      Long counterpartId,
      String counterpartName,
      String counterpartType,
      String counterpartCurrency,
      String categoryAmount,
      String baseAmount,
      String transferDirection) {}

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
   * bare when its direction is the counterpart type's default (expense → outflow, income → inflow,
   * transfer → TO direction outflow), carrying an explicit {@code +}/{@code −} only for the
   * overriding (refund/reversal) direction so a re-save reproduces the same leg.
   */
  static String amountText(BigDecimal fundingAmount, String counterpartType) {
    String magnitude = MoneyFormat.number(fundingAmount.abs(), AMOUNT_FRACTION_DIGITS);
    boolean outflow = fundingAmount.signum() < 0;
    // For transfers, TO is the default outflow direction
    boolean defaultOutflow = EXPENSE.equals(counterpartType);
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
