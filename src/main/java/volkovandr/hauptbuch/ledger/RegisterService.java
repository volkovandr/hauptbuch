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

  /**
   * The Category field's hierarchy separator (register §3.5): a nested leaf shows as {@code Food -
   * Milk}. The same string the categories resolver accepts to create a {@code Parent - Child} leaf,
   * so display, selection, and creation all speak one syntax.
   */
  private static final String CATEGORY_PATH_SEPARATOR = " - ";

  private final RegisterRepository registerRepository;
  private final PayeeRepository payeeRepository;
  private final AccountService accountService;
  private final SettingsService settingsService;
  private final RegisterRowRenderer rowRenderer;
  private final TagReadRepository tagReadRepository;
  private final PersonService personService;
  private final RegisterPickerService registerPickerService;

  RegisterService(
      RegisterRepository registerRepository,
      PayeeRepository payeeRepository,
      AccountService accountService,
      SettingsService settingsService,
      RegisterRowRenderer rowRenderer,
      TagReadRepository tagReadRepository,
      PersonService personService,
      RegisterPickerService registerPickerService) {
    this.registerRepository = registerRepository;
    this.payeeRepository = payeeRepository;
    this.accountService = accountService;
    this.settingsService = settingsService;
    this.rowRenderer = rowRenderer;
    this.tagReadRepository = tagReadRepository;
    this.personService = personService;
    this.registerPickerService = registerPickerService;
  }

  /**
   * Build the register screen for the given filter. An empty tick selection is resolved to the
   * active picker's whole membership ({@link RegisterPickerService}, register §2.3). Until the
   * book's base currency is set (data-model §3.8) the register is empty by construction (there are
   * no accounts and no rows), so a fresh book renders a clean, empty screen rather than failing.
   *
   * @param filter the applied filter; a null or empty {@code accountIds} means "the whole picker"
   */
  public RegisterView view(RegisterFilter filter) {
    Optional<String> baseCurrency = settingsService.baseCurrency();
    List<Account> pickable = pickable(openOwnAccounts());

    List<Long> viewed = resolveViewedAccounts(filter);
    List<RegisterRowView> rows =
        baseCurrency.map(base -> renderRows(viewed, filter, base)).orElseGet(List::of);

    List<RegisterAccountOption> accountOptions = accountOptions(pickable);
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
   * The live own accounts (asset/liability), from which {@link #pickable} takes the post-to set.
   */
  private List<Account> openOwnAccounts() {
    return accountService.findLiveByTypes(OWN_ACCOUNT_TYPES).stream()
        .filter(a -> a.closedAt() == null)
        .toList();
  }

  /**
   * The <em>post-to set</em>: the accounts the dock's Account datalist, the transfer targets, and
   * the fresh-dock default may name (plan stage 8b.1, issue transaction-register-ui/22) — open real
   * accounts only. Per-person debt leaves are excluded (a person is reached by the {@code
   * for}/{@code by} sigils, never by the leaf's cosmetic name); closed accounts are excluded
   * ({@link #openOwnAccounts} already dropped them) since a closed account is viewable but not
   * bookable. The <em>read</em> set — which does include both — is {@link RegisterPickerService}'s.
   */
  private static List<Account> pickable(List<Account> ownAccounts) {
    return ownAccounts.stream().filter(a -> !a.personLeaf()).toList();
  }

  /**
   * The accounts the register rows are read for: the explicit tick selection when there is one,
   * else every member of the active picker resolved against the applied date range (register §2.3,
   * issue transaction-register-ui/22). An empty selection means "the whole picker" — the same
   * contract the entry dock relies on to avoid freezing a defaulted filter.
   */
  private List<Long> resolveViewedAccounts(RegisterFilter filter) {
    if (!filter.accountIds().isEmpty()) {
      return filter.accountIds();
    }
    return registerPickerService.membership(filter.picker(), filter.fromDate(), filter.toDate());
  }

  private List<RegisterRowView> renderRows(
      List<Long> viewed, RegisterFilter filter, String baseCurrency) {
    List<RegisterRow> rows =
        registerRepository.findRows(
            viewed, filter.fromDate(), filter.toDate(), filter.payeeId(), baseCurrency);
    return rowRenderer.render(rows);
  }

  private List<RegisterAccountOption> accountOptions(List<Account> ownAccounts) {
    return ownAccounts.stream()
        .map(a -> new RegisterAccountOption(a.accountId(), a.name(), a.hue(), a.currencyCode()))
        .toList();
  }

  /**
   * The categories the dock offers (register §3.5): the live income/expense <em>posting
   * leaves</em>, each shown by its full {@code Parent - Child} path so the datalist conveys
   * hierarchy it cannot indent (issue 03), sorted by that path. Only genuine leaves are offered — a
   * semantic parent is not directly postable (data-model §5), so offering it only to have the
   * commit reject it read as a broken Save; {@link AccountService#findPostableLeafPaths} filters it
   * out (along with the auto-managed per-currency leaves) at the source. The user still picks
   * semantically; the currency leaf is resolved from the paying account at commit (data-model
   * §6.5).
   */
  private List<RegisterCategoryOption> categoryOptions() {
    return accountService.findPostableLeafPaths(CATEGORY_TYPES, CATEGORY_PATH_SEPARATOR).stream()
        .map(p -> new RegisterCategoryOption(p.accountId(), p.path()))
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
