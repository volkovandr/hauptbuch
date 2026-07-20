package volkovandr.hauptbuch.ledger;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.debts.PersonTarget;
import volkovandr.hauptbuch.ledger.RegisterView.RegisterAccountOption;
import volkovandr.hauptbuch.ledger.RegisterView.RegisterCategoryOption;
import volkovandr.hauptbuch.ledger.RegisterView.RegisterPayeeOption;
import volkovandr.hauptbuch.ledger.repository.PayeeRepository;
import volkovandr.hauptbuch.ledger.repository.RegisterRepository;
import volkovandr.hauptbuch.ledger.repository.TagReadRepository;

/**
 * The read-side of the transaction register (plan stage 7a): resolves the viewed accounts and
 * filters, fetches the rows, and hands them to {@link RegisterRowRenderer} for display, returning
 * the assembled {@link RegisterView}.
 *
 * <p>This is orchestration over {@link RegisterRepository}: the SQL owns the windowed running
 * balance and the leg lookups; the display rules (Category-cell summarisation, the same-hue zebra,
 * money formatting) live in the renderer. Both are unit-tested with the repositories mocked
 * (CLAUDE.md §6). Writing arrives at 7b — 7a is read-only.
 */
@Service
public class RegisterService {

  /** The default account set's types: your own real accounts (register §2.3). */
  private static final List<String> OWN_ACCOUNT_TYPES = List.of("asset", "liability");

  /** The category types the dock offers in its category picker (data-model §6.5). */
  private static final List<String> CATEGORY_TYPES = List.of("income", "expense");

  private final RegisterRepository registerRepository;
  private final PayeeRepository payeeRepository;
  private final AccountService accountService;
  private final SettingsService settingsService;
  private final RegisterRowRenderer rowRenderer;
  private final TagReadRepository tagReadRepository;
  private final PersonService personService;

  RegisterService(
      RegisterRepository registerRepository,
      PayeeRepository payeeRepository,
      AccountService accountService,
      SettingsService settingsService,
      RegisterRowRenderer rowRenderer,
      TagReadRepository tagReadRepository,
      PersonService personService) {
    this.registerRepository = registerRepository;
    this.payeeRepository = payeeRepository;
    this.accountService = accountService;
    this.settingsService = settingsService;
    this.rowRenderer = rowRenderer;
    this.tagReadRepository = tagReadRepository;
    this.personService = personService;
  }

  /**
   * Build the register screen for the given filter. An empty account selection is resolved to the
   * default set — every open own account (register §2.3). Until the book's base currency is set
   * (data-model §3.8) the register is empty by construction (there are no accounts and no rows), so
   * a fresh book renders a clean, empty screen rather than failing.
   *
   * @param filter the applied filter; a null or empty {@code accountIds} means "the default set"
   */
  public RegisterView view(RegisterFilter filter) {
    Optional<String> baseCurrency = settingsService.baseCurrency();
    List<Account> ownAccounts = openOwnAccounts();

    List<Long> viewed = resolveViewedAccounts(filter, ownAccounts);
    List<RegisterRowView> rows =
        baseCurrency.map(base -> renderRows(viewed, filter, base)).orElseGet(List::of);

    List<Account> pickable = pickable(ownAccounts);
    List<RegisterAccountOption> accountOptions = accountOptions(pickable, viewed);
    List<RegisterPayeeOption> payeeOptions = payeeOptions(filter.payeeId());
    List<RegisterCategoryOption> categoryOptions = categoryOptions();
    List<String> transferTargets = transferTargets(pickable);
    List<String> personTargets = personTargets();
    List<String> tagOptions = tagReadRepository.liveTagLabels();
    return new RegisterView(
        rows,
        accountOptions,
        payeeOptions,
        categoryOptions,
        transferTargets,
        personTargets,
        tagOptions,
        filter);
  }

  /**
   * The transfer targets the Category datalist offers alongside categories (register §3.5, plan
   * stage 7d.3): {@code To → <account>} and {@code From ← <account>} for every open own account, so
   * picking one routes the counter-leg to that real account instead of a category. Self-transfer is
   * refused at commit, so an account's own two options are offered even in its own register view.
   */
  private List<String> transferTargets(List<Account> ownAccounts) {
    return ownAccounts.stream()
        .flatMap(
            a ->
                Stream.of(
                    TransferTarget.option(TransferTarget.Direction.TO, a.name()),
                    TransferTarget.option(TransferTarget.Direction.FROM, a.name())))
        .toList();
  }

  /**
   * The person-attribution targets the Category datalist offers alongside categories and transfer
   * targets (register §3.5, plan stage 8b, data-model §7): {@code for <name>} and {@code by <name>}
   * for every live person, so picking one routes the counter-leg to that person's per-currency debt
   * leaf instead of a category. An unlisted (new) name is still accepted by typing it — this is
   * autocomplete convenience, not the only way in.
   */
  private List<String> personTargets() {
    return personService.findAllLive().stream()
        .flatMap(
            p ->
                Stream.of(
                    PersonTarget.option(PersonTarget.Direction.FOR, p.name()),
                    PersonTarget.option(PersonTarget.Direction.BY, p.name())))
        .toList();
  }

  /**
   * The open own accounts (asset/liability, not closed) — the default viewed set (register §2.3).
   * Per-person debt leaves are {@code asset} accounts (data-model §7) and belong here: Q-UI-1 is
   * resolved <em>surfaced</em>, so a person-funded transaction appears in the register with the
   * person on the Account side, its running balance a real balance like a credit card's.
   *
   * <p>They are excluded from the <em>pickers</em> instead — see {@link #pickable} — because a
   * person is reached by the {@code for}/{@code by} sigils, never by their leaf's cosmetic name.
   * Until stage 8c teaches {@link RegisterRowRenderer} to resolve a person's name, such a row
   * displays that cosmetic {@code personal.<CUR>} name; visible-but-cosmetically-named beats
   * invisible, since an invisible row reads as a transaction that failed to book.
   */
  private List<Account> openOwnAccounts() {
    return accountService.findLiveByTypes(OWN_ACCOUNT_TYPES).stream()
        .filter(a -> a.closedAt() == null)
        .toList();
  }

  /**
   * The subset of {@link #openOwnAccounts()} the dock's Account picker, the register's account
   * filter, and the transfer targets offer (plan stage 8b.1): everything except per-person debt
   * leaves. Stage 8c gives people their own place in the filter, listed as {@code Max (EUR)}.
   */
  private static List<Account> pickable(List<Account> ownAccounts) {
    return ownAccounts.stream().filter(a -> !a.personLeaf()).toList();
  }

  private List<Long> resolveViewedAccounts(RegisterFilter filter, List<Account> ownAccounts) {
    if (!filter.accountIds().isEmpty()) {
      return filter.accountIds();
    }
    return ownAccounts.stream().map(Account::accountId).toList();
  }

  private List<RegisterRowView> renderRows(
      List<Long> viewed, RegisterFilter filter, String baseCurrency) {
    List<RegisterRow> rows =
        registerRepository.findRows(
            viewed, filter.fromDate(), filter.toDate(), filter.payeeId(), baseCurrency);
    return rowRenderer.render(rows);
  }

  private List<RegisterAccountOption> accountOptions(List<Account> ownAccounts, List<Long> viewed) {
    return ownAccounts.stream()
        .map(
            a ->
                new RegisterAccountOption(
                    a.accountId(),
                    a.name(),
                    a.hue(),
                    a.currencyCode(),
                    viewed.contains(a.accountId())))
        .toList();
  }

  /**
   * The categories the dock offers (register §3.5): live income/expense accounts, listed by name.
   * The user picks semantically; the currency leaf is resolved from the paying account at commit
   * (data-model §6.5), so real categories — parents and never-subdivided leaves alike — are offered
   * as-is. {@code CurrencyLeafService}'s auto-managed per-currency leaves are excluded: they are
   * never individually selectable, only ever reached by routing through their semantic parent.
   */
  private List<RegisterCategoryOption> categoryOptions() {
    return accountService.findLiveByTypes(CATEGORY_TYPES).stream()
        .filter(a -> !a.currencyLeaf())
        .map(a -> new RegisterCategoryOption(a.accountId(), a.name()))
        .sorted((x, y) -> x.name().compareToIgnoreCase(y.name()))
        .toList();
  }

  private List<RegisterPayeeOption> payeeOptions(Long selectedPayeeId) {
    // Composed "Name · City · Country" labels so same-named payees are distinguishable (§3.4).
    return payeeRepository.findFilterOptions().stream()
        .map(
            p ->
                new RegisterPayeeOption(
                    p.payeeId(),
                    p.label(),
                    p.entryValue(),
                    Long.valueOf(p.payeeId()).equals(selectedPayeeId)))
        .toList();
  }
}
