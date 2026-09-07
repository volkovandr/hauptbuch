package volkovandr.hauptbuch.ledger;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountNode;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.PersonBalanceSummary;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.ledger.repository.RegisterRepository;

/**
 * Resolves each {@link RegisterPicker} to its member account ids (issue transaction-register-ui/22)
 * — the accounts an all-ticked picker views, and the membership a partial tick selection is
 * intersected with. The rendering of the strip and panel is {@link RegisterFilterViewAssembler}'s.
 *
 * <p>Membership is always <em>leaves</em>: posting is leaves-only (data-model §5), so a parent
 * account has no register rows — it is a group toggle in the panel, never a filter target, and
 * never counted for the all-ticked encoding. The read set is every live own account
 * (asset/liability), <em>including closed accounts</em> — a closed account is viewable though not
 * bookable — and including per-person debt leaves, which are {@code asset} accounts (data-model
 * §7).
 *
 * <p>Orchestration over {@link AccountService}, {@link PersonService} and {@link
 * RegisterRepository} with those mocked in the unit tier (CLAUDE.md §6); the one SQL-resident
 * piece, the activity lookup for {@link RegisterPicker#LAST_USED}, is covered in {@code
 * RegisterActivitySqlLogicTest}.
 */
@Service
public class RegisterPickerService {

  /**
   * The register's own-account types (register §2.3) — per-person debt leaves are {@code asset}.
   */
  private static final List<String> OWN_ACCOUNT_TYPES = List.of("asset", "liability");

  private final AccountService accountService;
  private final PersonService personService;
  private final RegisterRepository registerRepository;

  RegisterPickerService(
      AccountService accountService,
      PersonService personService,
      RegisterRepository registerRepository) {
    this.accountService = accountService;
    this.personService = personService;
    this.registerRepository = registerRepository;
  }

  /**
   * The member account ids of {@code picker}, in display order (alphabetical depth-first for the
   * account pickers; unsettled people before settled for {@link RegisterPicker#PERSONS}). {@link
   * RegisterPicker#LAST_USED} is resolved against the given date range, so widening the range while
   * all-ticked admits newly-qualifying accounts.
   *
   * @param fromDate the applied filter's inclusive lower bound; null for unbounded
   * @param toDate the applied filter's inclusive upper bound; null for unbounded
   */
  public List<Long> membership(RegisterPicker picker, LocalDate fromDate, LocalDate toDate) {
    List<Account> leaves = ownLeaves();
    return switch (picker) {
      case OPEN ->
          realLeaves(leaves).filter(RegisterPickerService::isOpen).map(Account::accountId).toList();
      case CLOSED -> realLeaves(leaves).filter(a -> !isOpen(a)).map(Account::accountId).toList();
      case ALL -> leaves.stream().map(Account::accountId).toList();
      case PERSONS ->
          livePeopleUnsettledFirst().stream()
              .flatMap(summary -> summary.accountIds().stream())
              .toList();
      case LAST_USED -> lastUsed(leaves, fromDate, toDate);
    };
  }

  /**
   * The account ids to view for a submitted picker + tick selection (issue
   * transaction-register-ui/22): an empty {@code submitted} means "every member", and a selection
   * that ticks <em>every</em> member collapses back to empty — so the server re-resolves it against
   * the submitted date range and the entry dock's "don't serialise a defaulted filter" rule fires
   * untouched. A partial selection is intersected with the picker's membership and kept verbatim.
   */
  public List<Long> resolveSelection(
      RegisterPicker picker, List<Long> submitted, LocalDate fromDate, LocalDate toDate) {
    if (submitted == null || submitted.isEmpty()) {
      return List.of();
    }
    List<Long> membership = membership(picker, fromDate, toDate);
    Set<Long> members = new HashSet<>(membership);
    List<Long> ticked = submitted.stream().distinct().filter(members::contains).toList();
    if (!membership.isEmpty() && ticked.size() == membership.size()) {
      return List.of();
    }
    return ticked;
  }

  /**
   * The live-people roster with an unsettled leaf before fully settled people (a fully settled
   * person has an empty {@link PersonBalanceSummary#balances()}), alphabetical by name within each
   * group. Reuses {@link PersonService#balanceSummaries()} — the same roster the People screen
   * builds — rather than a new settled/unsettled query.
   */
  public List<PersonBalanceSummary> livePeopleUnsettledFirst() {
    List<PersonBalanceSummary> summaries = personService.balanceSummaries();
    return Stream.concat(
            summaries.stream().filter(s -> !s.balances().isEmpty()),
            summaries.stream().filter(s -> s.balances().isEmpty()))
        .toList();
  }

  /**
   * Every leaf of the register's own-account read set (asset/liability), alphabetical depth-first —
   * real accounts (open and closed) and per-person debt leaves alike. Parent accounts and the
   * auto-managed currency leaves (data-model §6.5) are dropped: neither is a filter target.
   */
  private List<Account> ownLeaves() {
    List<AccountNode> nodes = accountService.findLiveByTypesWithDepth(OWN_ACCOUNT_TYPES);
    Set<Long> parents = new HashSet<>();
    for (AccountNode node : nodes) {
      Account account = node.account();
      if (!account.currencyLeaf() && account.parentId() != null) {
        parents.add(account.parentId());
      }
    }
    return nodes.stream()
        .map(AccountNode::account)
        .filter(a -> !a.currencyLeaf())
        .filter(a -> !parents.contains(a.accountId()))
        .toList();
  }

  private static Stream<Account> realLeaves(List<Account> leaves) {
    return leaves.stream().filter(a -> !a.personLeaf());
  }

  private static boolean isOpen(Account account) {
    return account.closedAt() == null;
  }

  /**
   * "Last used": <em>open</em> real-account leaves and person leaves with a posting in the applied
   * date range (issue transaction-register-ui/22, spec table). A closed account is excluded even
   * with recent activity — the {@link RegisterPicker#CLOSED} and {@link RegisterPicker#ALL} pickers
   * are the way to reach one.
   */
  private List<Long> lastUsed(List<Account> leaves, LocalDate fromDate, LocalDate toDate) {
    Set<Long> active =
        new HashSet<>(registerRepository.findAccountIdsWithActivity(fromDate, toDate));
    return leaves.stream()
        .filter(a -> a.personLeaf() || isOpen(a))
        .map(Account::accountId)
        .filter(active::contains)
        .toList();
  }
}
