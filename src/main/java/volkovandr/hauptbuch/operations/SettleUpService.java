package volkovandr.hauptbuch.operations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.joda.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.debts.SettleTarget;
import volkovandr.hauptbuch.ledger.CrossCurrencyFields;
import volkovandr.hauptbuch.ledger.CrossCurrencyFieldsQuery;
import volkovandr.hauptbuch.ledger.CrossCurrencyFieldsService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.ledger.TransferTarget;
import volkovandr.hauptbuch.operations.SettleUpView.AccountOption;
import volkovandr.hauptbuch.shared.MoneyFactory;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * The settle-up launcher's logic (plan stage 8e, data-model §7): assemble the per-person,
 * per-currency form, and commit the settle as a single dated transfer that zeroes the debt leaf.
 * There is <em>no new engine here</em> — a settle is an ordinary transfer between a real own
 * account and the person's per-currency debt leaf, committed through {@link DockCommitService}
 * exactly as a hand-entered transfer is. The launcher's only job is to pre-scope that transfer:
 * pick the leaf, derive the direction from the live balance sign, and default the amount to the
 * whole outstanding.
 *
 * <p>Direction is never a user choice (data-model §7): a positive balance means the person owes
 * you, so settling is money coming <em>in</em> (a transfer <em>from</em> the leaf); a negative
 * balance means you owe them, so it is money going <em>out</em> (a transfer <em>to</em> the leaf).
 * It is read from the balance at commit, not from a stale hidden field, so a position that changed
 * between launch and submit still settles the right way.
 *
 * <p>Targeting the leaf by its account id (a transfer counterpart) rather than by the person's name
 * (the dock's {@code for}/{@code by} path) sidesteps the duplicate-name ambiguity {@link
 * PersonService#matchExact} would otherwise raise, and needs no provisioning — the leaf already
 * exists, or there would be nothing to settle. The stored postings and the register display are
 * identical either way (stage 8c resolves any person leaf to its owner's name).
 */
@Service
class SettleUpService {

  /** Dock amounts are entered German-formatted to the minor unit; two places covers EUR/CHF. */
  private static final int AMOUNT_FRACTION_DIGITS = 2;

  private static final List<String> OWN_ACCOUNT_TYPES = List.of("asset", "liability");

  private final PersonService personService;
  private final AccountService accountService;
  private final SettingsService settingsService;
  private final CrossCurrencyFieldsService crossCurrencyFieldsService;
  private final DockCommitService dockCommitService;

  SettleUpService(
      PersonService personService,
      AccountService accountService,
      SettingsService settingsService,
      CrossCurrencyFieldsService crossCurrencyFieldsService,
      DockCommitService dockCommitService) {
    this.personService = personService;
    this.accountService = accountService;
    this.settingsService = settingsService;
    this.crossCurrencyFieldsService = crossCurrencyFieldsService;
    this.dockCommitService = dockCommitService;
  }

  /**
   * Assemble the settle-up form for one person and currency. Used by both the initial page render
   * and the funding-account htmx recompute — the same computation, so the amount-field layout the
   * user sees always matches what a commit would book. A change of funding account resets the
   * amount defaults (the debt figure into whichever field holds the debt-currency leg), rather than
   * preserving text across a currency change that would make it ambiguous.
   *
   * @param selectedAccountId the chosen funding account, or {@code null} to default it (an own
   *     account in the debt currency when one exists, else the first own account)
   * @param date the settle date, or {@code null} for today
   * @throws IllegalArgumentException if the person is not live or holds no leaf in that currency
   */
  SettleUpView assemble(
      long personId, String currencyCode, Long selectedAccountId, LocalDate date) {
    SettleTarget target = requireTarget(personId, currencyCode);
    LocalDate settleDate = date != null ? date : LocalDate.now();
    List<Account> pickable = pickableAccounts();
    Account funding = chooseFunding(pickable, selectedAccountId, currencyCode);

    boolean cross = !fundingCurrency(funding, currencyCode).equals(currencyCode);
    // The person-leg native amount defaults to the whole outstanding figure; in the single-currency
    // case that IS the one Amount field, so it seeds fundingAmountText instead. When
    // cross-currency,
    // what actually left the funding account is a real fact only the user knows, so it starts
    // blank.
    String outstandingText =
        MoneyFormat.number(target.signedBalance().abs(), AMOUNT_FRACTION_DIGITS);
    return build(
        personId,
        currencyCode,
        target,
        pickable,
        funding,
        settleDate,
        cross ? null : outstandingText,
        cross ? outstandingText : null,
        null);
  }

  /**
   * Re-assemble the form after a rejected commit (register §3.8a's redisplay), echoing the amounts
   * the user actually typed rather than resetting them to defaults, so a base-gap or bad-number
   * correction is one edit away.
   *
   * @throws IllegalArgumentException if the person is not live or holds no leaf in that currency
   */
  SettleUpView redisplay(
      long personId,
      String currencyCode,
      Long selectedAccountId,
      LocalDate date,
      String amount,
      String categoryAmount,
      String baseAmount) {
    SettleTarget target = requireTarget(personId, currencyCode);
    LocalDate settleDate = date != null ? date : LocalDate.now();
    List<Account> pickable = pickableAccounts();
    Account funding = chooseFunding(pickable, selectedAccountId, currencyCode);
    return build(
        personId,
        currencyCode,
        target,
        pickable,
        funding,
        settleDate,
        amount,
        categoryAmount,
        baseAmount);
  }

  /**
   * Assemble a settle-up view for an already-resolved funding account and an explicit set of
   * amount-field values — shared by the fresh form (defaulted amounts) and the error redisplay (the
   * user's typed amounts). The caller resolves the funding account once and hands it in, so the
   * pickable-account fetch and the choice are not repeated. The funding-amount field value is
   * {@code fundingAmountText}; the person-leg native amount and base amount are echoed through
   * {@link CrossCurrencyFieldsService#resolve}, which also decides whether they are shown.
   */
  private SettleUpView build(
      long personId,
      String currencyCode,
      SettleTarget target,
      List<Account> pickable,
      Account funding,
      LocalDate settleDate,
      String fundingAmountText,
      String categoryAmountText,
      String baseAmountText) {
    String personName =
        personService
            .findById(personId)
            .orElseThrow(() -> new IllegalArgumentException("No person with id " + personId))
            .name();

    String base = settingsService.baseCurrency().orElse("");
    BigDecimal signed = target.signedBalance();
    boolean youOwe = signed.signum() < 0;
    Money magnitude = MoneyFactory.of(signed.abs(), currencyCode);
    String summary =
        youOwe
            ? "You owe " + personName + " " + MoneyFormat.display(magnitude, base)
            : personName + " owes you " + MoneyFormat.display(magnitude, base);

    CrossCurrencyFields fields =
        crossCurrencyFieldsService.resolve(
            new CrossCurrencyFieldsQuery(
                fundingCurrency(funding, currencyCode),
                currencyCode,
                settleDate,
                fundingAmountText,
                categoryAmountText,
                baseAmountText));

    return new SettleUpView(
        personId,
        personName,
        currencyCode,
        summary,
        youOwe,
        options(pickable, funding),
        settleDate,
        fields,
        fundingAmountText == null ? "" : fundingAmountText);
  }

  /**
   * The settle target for a live person + currency, or a rejection when there is nothing to settle.
   */
  private SettleTarget requireTarget(long personId, String currencyCode) {
    return personService
        .settleTarget(personId, currencyCode)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Nothing to settle for this person in " + currencyCode));
  }

  /**
   * Commit the settle as a transfer between the funding account and the person's debt leaf, zeroing
   * the position (or reducing it, when the amount is less than outstanding — a partial settle is
   * free, data-model §7). The direction is derived from the current balance sign, so it is always
   * correct regardless of what the launcher last rendered.
   *
   * @return the recorded transaction's id
   * @throws IllegalArgumentException if the person holds no leaf in that currency
   */
  @Transactional
  long settle(
      long personId,
      String currencyCode,
      Long fundingAccountId,
      LocalDate date,
      String amount,
      String categoryAmount,
      String baseAmount) {
    SettleTarget target = requireTarget(personId, currencyCode);
    // Positive balance (they owe you) settles by money coming in — a transfer FROM the leaf.
    // Negative balance (you owe them) settles by money going out — a transfer TO the leaf.
    String direction =
        target.signedBalance().signum() < 0
            ? TransferTarget.Direction.TO.name()
            : TransferTarget.Direction.FROM.name();

    DockEntry entry =
        new DockEntry(
            null,
            date,
            fundingAccountId,
            null,
            null,
            null,
            null,
            null,
            target.accountId(),
            null,
            amount,
            categoryAmount,
            baseAmount,
            null,
            direction,
            null,
            null,
            null,
            List.of());
    return dockCommitService.commit(entry);
  }

  /**
   * The funding leg's currency: the chosen account's own, or the debt currency when there is no
   * pickable account at all (a same-currency, single-field degenerate case the template guards with
   * its "no account" note).
   */
  private static String fundingCurrency(Account funding, String currencyCode) {
    return funding != null ? funding.currencyCode() : currencyCode;
  }

  /** The open own accounts a settle can be funded from — person leaves excluded (data-model §7). */
  private List<Account> pickableAccounts() {
    return accountService.findLiveByTypes(OWN_ACCOUNT_TYPES).stream()
        .filter(a -> a.closedAt() == null)
        .filter(a -> !a.personLeaf())
        .toList();
  }

  /**
   * The funding account to pre-select: the explicitly chosen one when it is pickable, else an own
   * account already in the debt currency (so the common settle is single-field), else the first
   * pickable account, else null when there are none.
   */
  private Account chooseFunding(
      List<Account> pickable, Long selectedAccountId, String currencyCode) {
    if (selectedAccountId != null) {
      for (Account account : pickable) {
        if (account.accountId().equals(selectedAccountId)) {
          return account;
        }
      }
    }
    return pickable.stream()
        .filter(a -> a.currencyCode().equals(currencyCode))
        .findFirst()
        .orElse(pickable.isEmpty() ? null : pickable.get(0));
  }

  /** Label each pickable account and flag the chosen one for pre-selection. */
  private List<AccountOption> options(List<Account> pickable, Account funding) {
    List<AccountOption> options = new ArrayList<>();
    Long fundingId = funding != null ? funding.accountId() : null;
    for (Account account : pickable) {
      String label = account.name() + " (" + account.currencyCode() + ")";
      options.add(
          new AccountOption(account.accountId(), label, account.accountId().equals(fundingId)));
    }
    return options;
  }
}
