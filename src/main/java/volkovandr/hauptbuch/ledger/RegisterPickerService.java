package volkovandr.hauptbuch.ledger;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
   * The member account ids of {@code picker}, in display order — alphabetical throughout
   * (depth-first for the account pickers, by person name for {@link RegisterPicker#PERSONS}; owner
   * feedback 2026-09-07). {@link RegisterPicker#LAST_USED} is resolved against the given date
   * range, so widening the range while all-ticked admits newly-qualifying accounts.
   *
   * <p><strong>Invariant:</strong> a picker's membership is exactly the leaf ids its panel renders
   * as checkboxes ({@link RegisterFilterViewAssembler}); both are resolved from the same sources
   * ({@link #ownLeaves()}, {@link #personGroups}) and must not drift — a member with no row is an
   * invisible tick and a wrong tab count (issue transaction-register-ui/23).
   *
   * @param fromDate the applied filter's inclusive lower bound; null for unbounded
   * @param toDate the applied filter's inclusive upper bound; null for unbounded
   */
  public List<Long> membership(RegisterPicker picker, LocalDate fromDate, LocalDate toDate) {
    List<Account> leaves = ownLeaves();
    return switch (picker) {
      case OPEN ->
          realLeaves(leaves).filter(RegisterPickerService::isOpen).map(Account::accountId).toList();
      case CLOSED -> closedMembership(leaves);
      case ALL -> leaves.stream().map(Account::accountId).toList();
      case PERSONS ->
          personService.balanceSummaries().stream()
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
   * The person groups the register filter renders for {@code picker} — one name-ordered list under
   * the {@code Persons} umbrella (owner feedback 2026-09-07).
   *
   * <ul>
   *   <li>{@link RegisterPicker#PERSONS}, {@link RegisterPicker#LAST_USED} — live people only.
   *   <li>{@link RegisterPicker#CLOSED} — soft-deleted people who still own a live leaf only, each
   *       flagged {@link PersonGroup#deleted()} (issue transaction-register-ui/23).
   *   <li>{@link RegisterPicker#ALL} — both, merged alphabetically; a same-name live/deleted pair
   *       renders as two toggles (stable sort keeps the live one first).
   *   <li>{@link RegisterPicker#OPEN} — none (the panel shows no people column).
   * </ul>
   *
   * <p>Sorted here, not in the assembler: "which accounts, in what order" is this service's
   * concern.
   */
  List<PersonGroup> personGroups(RegisterPicker picker) {
    List<PersonGroup> groups = new ArrayList<>();
    if (picker == RegisterPicker.PERSONS
        || picker == RegisterPicker.LAST_USED
        || picker == RegisterPicker.ALL) {
      for (PersonBalanceSummary summary : personService.balanceSummaries()) {
        groups.add(
            new PersonGroup(summary.personId(), summary.name(), false, summary.accountIds()));
      }
    }
    if (picker == RegisterPicker.CLOSED || picker == RegisterPicker.ALL) {
      for (PersonBalanceSummary summary : personService.deletedPeople()) {
        groups.add(new PersonGroup(summary.personId(), summary.name(), true, summary.accountIds()));
      }
    }
    groups.sort(Comparator.comparing(PersonGroup::name));
    return groups;
  }

  /**
   * A person and their per-currency debt-leaf account ids as one filter group (issue
   * transaction-register-ui/22, /23) — a live person or a soft-deleted person who still owns a live
   * leaf. {@link #deleted()} drives the muted "deleted" marker on the person's toggle.
   *
   * @param personId the person's id (the {@code p<id>} group key)
   * @param name the person's current display name
   * @param deleted whether the person is soft-deleted
   * @param leafAccountIds the person's debt-leaf account ids, currency-ordered
   */
  public record PersonGroup(
      long personId, String name, boolean deleted, List<Long> leafAccountIds) {

    /** Defensively copy the account-id list to an immutable list. */
    public PersonGroup {
      leafAccountIds = List.copyOf(leafAccountIds);
    }
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
   * "Closed": closed real-account leaves <em>and</em> the still-live debt leaves of soft-deleted
   * people (issue transaction-register-ui/23), so a since-deleted person's settled transactions
   * stay reachable. The person leaves come after the closed real leaves, in person-name order.
   */
  private List<Long> closedMembership(List<Account> leaves) {
    Set<Long> deletedPersonLeaves = deletedPersonLeafIds();
    List<Long> ids = new ArrayList<>();
    realLeaves(leaves).filter(a -> !isOpen(a)).map(Account::accountId).forEach(ids::add);
    leaves.stream()
        .filter(a -> deletedPersonLeaves.contains(a.accountId()))
        .map(Account::accountId)
        .forEach(ids::add);
    return ids;
  }

  /**
   * "Last used": <em>open</em> real-account leaves and <em>live</em> people's leaves with a posting
   * in the applied date range (issue transaction-register-ui/22, spec table). A closed account is
   * excluded even with recent activity, and so is a soft-deleted person's leaf (issue
   * transaction-register-ui/23) — a just-settled-then-deleted person has recent activity but no row
   * on this tab; {@link RegisterPicker#CLOSED} and {@link RegisterPicker#ALL} are the way to reach
   * either.
   */
  private List<Long> lastUsed(List<Account> leaves, LocalDate fromDate, LocalDate toDate) {
    Set<Long> active =
        new HashSet<>(registerRepository.findAccountIdsWithActivity(fromDate, toDate));
    Set<Long> deletedPersonLeaves = deletedPersonLeafIds();
    return leaves.stream()
        .filter(a -> a.personLeaf() ? !deletedPersonLeaves.contains(a.accountId()) : isOpen(a))
        .map(Account::accountId)
        .filter(active::contains)
        .toList();
  }

  /** The account ids of every still-live leaf owned by a soft-deleted person. */
  private Set<Long> deletedPersonLeafIds() {
    return personService.deletedPeople().stream()
        .flatMap(summary -> summary.accountIds().stream())
        .collect(Collectors.toSet());
  }
}
