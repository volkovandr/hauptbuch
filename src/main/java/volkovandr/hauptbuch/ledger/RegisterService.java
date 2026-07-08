package volkovandr.hauptbuch.ledger;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.RegisterView.RegisterAccountOption;
import volkovandr.hauptbuch.ledger.RegisterView.RegisterCategoryOption;
import volkovandr.hauptbuch.ledger.RegisterView.RegisterPayeeOption;
import volkovandr.hauptbuch.ledger.repository.PayeeRepository;
import volkovandr.hauptbuch.ledger.repository.RegisterRepository;

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

  RegisterService(
      RegisterRepository registerRepository,
      PayeeRepository payeeRepository,
      AccountService accountService,
      SettingsService settingsService,
      RegisterRowRenderer rowRenderer) {
    this.registerRepository = registerRepository;
    this.payeeRepository = payeeRepository;
    this.accountService = accountService;
    this.settingsService = settingsService;
    this.rowRenderer = rowRenderer;
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

    List<RegisterAccountOption> accountOptions = accountOptions(ownAccounts, viewed);
    List<RegisterPayeeOption> payeeOptions = payeeOptions(filter.payeeId());
    List<RegisterCategoryOption> categoryOptions = categoryOptions();
    return new RegisterView(rows, accountOptions, payeeOptions, categoryOptions, filter);
  }

  /**
   * The open own accounts (asset/liability, not closed) — the default viewed set (register §2.3).
   */
  private List<Account> openOwnAccounts() {
    return accountService.findLiveByTypes(OWN_ACCOUNT_TYPES).stream()
        .filter(a -> a.closedAt() == null)
        .toList();
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
    return rowRenderer.render(rows, viewed);
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
   * (data-model §6.5), so both parents and leaves are offered as-is.
   */
  private List<RegisterCategoryOption> categoryOptions() {
    return accountService.findLiveByTypes(CATEGORY_TYPES).stream()
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
