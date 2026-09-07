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
 * — the accounts an all-ticked picker views, and the membership the controller intersects a partial
 * tick selection against.
 *
 * <p>Membership is always <em>leaves</em>: posting is leaves-only (data-model §5), so a parent
 * account has no postings and no register rows — it is a group toggle in the panel, never a filter
 * target, and never counted for the all-ticked encoding. The read set is every live own account
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
      case PERSONS -> personLeavesUnsettledFirst();
      case LAST_USED -> lastUsed(leaves, fromDate, toDate);
    };
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

  /** The real-account leaves (person leaves excluded — they have their own pickers). */
  private static Stream<Account> realLeaves(List<Account> leaves) {
    return leaves.stream().filter(a -> !a.personLeaf());
  }

  private static boolean isOpen(Account account) {
    return account.closedAt() == null;
  }

  /**
   * "Last used": the open real-account leaves <em>and</em> person leaves that carry a posting
   * inside the applied date range. Person leaves are included — a person-funded transaction's only
   * own leg can be the debt leaf, and dropping it would leave that transaction with no register row
   * at all (plan stage 8b.1, reverted after owner testing 2026-07-20). Ordering follows {@link
   * #ownLeaves()}.
   */
  private List<Long> lastUsed(List<Account> leaves, LocalDate fromDate, LocalDate toDate) {
    Set<Long> active =
        new HashSet<>(registerRepository.findAccountIdsWithActivity(fromDate, toDate));
    return leaves.stream().map(Account::accountId).filter(active::contains).toList();
  }

  /**
   * Every live person's per-currency debt leaves, people with an unsettled leaf before fully
   * settled people (a fully settled person has an empty {@link PersonBalanceSummary#balances()}),
   * alphabetical by name within each group. Reuses {@link PersonService#balanceSummaries()} — the
   * same roster the People screen builds — rather than a new settled/unsettled query.
   */
  private List<Long> personLeavesUnsettledFirst() {
    List<PersonBalanceSummary> summaries = personService.balanceSummaries();
    return Stream.concat(
            summaries.stream().filter(s -> !s.balances().isEmpty()),
            summaries.stream().filter(s -> s.balances().isEmpty()))
        .flatMap(s -> s.accountIds().stream())
        .toList();
  }
}
