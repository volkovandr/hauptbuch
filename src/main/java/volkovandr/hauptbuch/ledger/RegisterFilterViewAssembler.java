package volkovandr.hauptbuch.ledger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountEntryLabel;
import volkovandr.hauptbuch.accounts.AccountNode;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.PersonBalanceSummary;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.ledger.RegisterFilterView.Panel;
import volkovandr.hauptbuch.ledger.RegisterFilterView.Row;
import volkovandr.hauptbuch.ledger.RegisterFilterView.Tab;

/**
 * Builds the register's account-filter control (issue transaction-register-ui/22): the five-tab
 * strip and the active picker's panel of group toggles and checkboxes. The <em>which accounts</em>
 * question belongs to {@link RegisterPickerService}; this class only turns a resolved membership
 * into the flat list of rows the template iterates.
 *
 * <p>The panel is one flat {@link Row} list so an arbitrarily deep account hierarchy and the
 * per-person groups render through a single loop. A parent account renders as a tri-state group
 * toggle keyed {@code g<id>}; a person renders as one keyed {@code p<id>}; every member row carries
 * the space-separated keys of the groups it belongs to, which is all the {@code filter-groups.js}
 * leaf needs (CLAUDE.md §1.6).
 */
@Component
class RegisterFilterViewAssembler {

  /**
   * The register's own-account types (register §2.3) — per-person debt leaves are {@code asset}.
   */
  private static final List<String> OWN_ACCOUNT_TYPES = List.of("asset", "liability");

  /** The umbrella group over the person leaves in the {@link RegisterPicker#LAST_USED} panel. */
  private static final String PERSONS_UMBRELLA_KEY = "persons";

  private final RegisterPickerService pickerService;
  private final AccountService accountService;
  private final PersonService personService;

  RegisterFilterViewAssembler(
      RegisterPickerService pickerService,
      AccountService accountService,
      PersonService personService) {
    this.pickerService = pickerService;
    this.accountService = accountService;
    this.personService = personService;
  }

  /**
   * The tab strip (active picker emphasised and counted, others uncounted) plus that picker's
   * panel.
   *
   * @param panelExpanded whether the {@code <details>} renders open — {@code true} for a tab switch
   *     (the user is picking), {@code false} for a full page render and the post-Apply render
   */
  RegisterFilterView filterView(RegisterFilter filter, boolean panelExpanded) {
    RegisterPicker active = filter.picker();
    List<Long> membership = pickerService.membership(active, filter.fromDate(), filter.toDate());
    Set<Long> members = new LinkedHashSet<>(membership);
    Set<Long> ticked = new LinkedHashSet<>();
    if (filter.accountIds().isEmpty()) {
      ticked.addAll(members);
    } else {
      for (Long id : filter.accountIds()) {
        if (members.contains(id)) {
          ticked.add(id);
        }
      }
    }

    List<Tab> tabs = new ArrayList<>();
    for (RegisterPicker picker : RegisterPicker.values()) {
      boolean isActive = picker == active;
      tabs.add(new Tab(picker.param(), label(picker), isActive, isActive ? ticked.size() : null));
    }
    return new RegisterFilterView(
        tabs, new Panel(active, active.param(), panelExpanded, panelRows(active, members, ticked)));
  }

  private List<Row> panelRows(RegisterPicker picker, Set<Long> members, Set<Long> ticked) {
    List<AccountNode> nodes = accountService.findLiveByTypesWithDepth(OWN_ACCOUNT_TYPES);
    return switch (picker) {
      case OPEN, CLOSED -> accountTreeRows(nodes, members, ticked);
      case ALL ->
          concat(accountTreeRows(nodes, members, ticked), perPersonRows(nodes, members, ticked));
      case PERSONS -> perPersonRows(nodes, members, ticked);
      case LAST_USED ->
          concat(
              accountTreeRows(nodes, members, ticked), personsUmbrellaRows(nodes, members, ticked));
    };
  }

  /**
   * The real-account portion of a panel: every member real-account leaf, preceded by each of its
   * ancestor parents rendered as a tri-state group toggle. A parent with no member descendant in
   * this picker is not shown; a parent's own id is never a member row (leaves-only).
   */
  private List<Row> accountTreeRows(List<AccountNode> nodes, Set<Long> members, Set<Long> ticked) {
    Map<Long, Account> byId = byId(nodes);
    Set<Long> renderedParents = ancestorsOf(members, byId);
    List<Row> rows = new ArrayList<>();
    for (AccountNode node : nodes) {
      Account account = node.account();
      if (account.personLeaf() || account.currencyLeaf()) {
        continue;
      }
      String memberOf = ancestorGroupKeys(account, byId, renderedParents);
      if (renderedParents.contains(account.accountId())) {
        rows.add(
            new Row(
                account.accountId(),
                account.name(),
                null,
                account.hue(),
                node.depth(),
                true,
                "g" + account.accountId(),
                memberOf,
                false));
      } else if (members.contains(account.accountId())) {
        rows.add(
            memberRow(
                account, account.name(), account.currencyCode(), node.depth(), memberOf, ticked));
      }
    }
    return rows;
  }

  /**
   * The space-separated {@code g<id>} keys of {@code account}'s ancestor parents that are being
   * rendered as group toggles — outermost first — or null when it sits under no rendered group.
   */
  private static String ancestorGroupKeys(
      Account account, Map<Long, Account> byId, Set<Long> renderedParents) {
    List<String> keys = new ArrayList<>();
    Long parentId = account.parentId();
    while (parentId != null) {
      if (renderedParents.contains(parentId)) {
        keys.add(0, "g" + parentId);
      }
      Account parent = byId.get(parentId);
      parentId = parent == null ? null : parent.parentId();
    }
    return keys.isEmpty() ? null : String.join(" ", keys);
  }

  /** One tri-state group per live person over their member debt leaves, unsettled people first. */
  private List<Row> perPersonRows(List<AccountNode> nodes, Set<Long> members, Set<Long> ticked) {
    Map<Long, Account> byId = byId(nodes);
    Map<Long, String> names = personService.personNamesForAccounts(members);
    List<Row> rows = new ArrayList<>();
    for (PersonBalanceSummary person : pickerService.livePeopleUnsettledFirst()) {
      List<Long> leaves = person.accountIds().stream().filter(members::contains).toList();
      if (leaves.isEmpty()) {
        continue;
      }
      String key = "p" + person.personId();
      rows.add(groupToggleRow(person.name(), key));
      for (Long leafId : leaves) {
        Account leaf = byId.get(leafId);
        if (leaf != null) {
          rows.add(personLeafRow(leaf, names.getOrDefault(leafId, person.name()), key, ticked));
        }
      }
    }
    return rows;
  }

  /** The single "Persons" umbrella group the {@link RegisterPicker#LAST_USED} panel shows. */
  private List<Row> personsUmbrellaRows(
      List<AccountNode> nodes, Set<Long> members, Set<Long> ticked) {
    List<Account> personLeaves =
        nodes.stream()
            .map(AccountNode::account)
            .filter(Account::personLeaf)
            .filter(a -> members.contains(a.accountId()))
            .toList();
    if (personLeaves.isEmpty()) {
      return List.of();
    }
    Map<Long, String> names =
        personService.personNamesForAccounts(
            personLeaves.stream().map(Account::accountId).toList());
    List<Row> rows = new ArrayList<>();
    rows.add(groupToggleRow("Persons", PERSONS_UMBRELLA_KEY));
    for (Account leaf : personLeaves) {
      rows.add(
          personLeafRow(
              leaf,
              names.getOrDefault(leaf.accountId(), leaf.name()),
              PERSONS_UMBRELLA_KEY,
              ticked));
    }
    return rows;
  }

  private static Row groupToggleRow(String label, String key) {
    return new Row(0L, label, null, null, 0, true, key, null, false);
  }

  private static Row memberRow(
      Account account,
      String label,
      String currencyCode,
      int depth,
      String memberOf,
      Set<Long> ticked) {
    return new Row(
        account.accountId(),
        label,
        currencyCode,
        account.hue(),
        depth,
        false,
        null,
        memberOf,
        ticked.contains(account.accountId()));
  }

  /** A person's debt-leaf row, labelled {@code Name (CUR)} (register §2.6) — one combined label. */
  private static Row personLeafRow(
      Account leaf, String personName, String memberOf, Set<Long> ticked) {
    return memberRow(
        leaf, AccountEntryLabel.format(personName, leaf.currencyCode()), null, 1, memberOf, ticked);
  }

  /** Every ancestor account id of any id in {@code members}, walked up {@code parent_id}. */
  private static Set<Long> ancestorsOf(Set<Long> members, Map<Long, Account> byId) {
    Set<Long> ancestors = new HashSet<>();
    for (Long memberId : members) {
      Account account = byId.get(memberId);
      Long parentId = account == null ? null : account.parentId();
      while (parentId != null && ancestors.add(parentId)) {
        Account parent = byId.get(parentId);
        parentId = parent == null ? null : parent.parentId();
      }
    }
    return ancestors;
  }

  private static Map<Long, Account> byId(List<AccountNode> nodes) {
    Map<Long, Account> byId = new HashMap<>();
    for (AccountNode node : nodes) {
      byId.put(node.account().accountId(), node.account());
    }
    return byId;
  }

  private static String label(RegisterPicker picker) {
    return switch (picker) {
      case LAST_USED -> "Last used";
      case OPEN -> "Open";
      case PERSONS -> "Persons";
      case CLOSED -> "Closed";
      case ALL -> "All";
    };
  }

  private static <T> List<T> concat(List<T> first, List<T> second) {
    List<T> both = new ArrayList<>(first);
    both.addAll(second);
    return both;
  }
}
