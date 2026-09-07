package volkovandr.hauptbuch.ledger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountNode;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.RegisterFilterView.Marker;
import volkovandr.hauptbuch.ledger.RegisterFilterView.Panel;
import volkovandr.hauptbuch.ledger.RegisterFilterView.Row;
import volkovandr.hauptbuch.ledger.RegisterFilterView.Tab;
import volkovandr.hauptbuch.ledger.RegisterPickerService.PersonGroup;

/**
 * Builds the register's account-filter control (issue transaction-register-ui/22): the five-tab
 * strip and the active picker's panel of group toggles and checkboxes. The <em>which accounts</em>
 * question belongs to {@link RegisterPickerService}; this class only turns a resolved membership
 * into the flat list of rows the template iterates.
 *
 * <p>Each column is a flat {@link Row} list so an arbitrarily deep account hierarchy and the
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

  /** The tri-state umbrella group over every person's per-person sub-group. */
  private static final String PERSONS_UMBRELLA_KEY = "persons";

  private final RegisterPickerService pickerService;
  private final AccountService accountService;

  RegisterFilterViewAssembler(RegisterPickerService pickerService, AccountService accountService) {
    this.pickerService = pickerService;
    this.accountService = accountService;
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

    List<AccountNode> nodes = accountService.findLiveByTypesWithDepth(OWN_ACCOUNT_TYPES);
    boolean showAccounts = active != RegisterPicker.PERSONS;
    boolean showPeople =
        active == RegisterPicker.ALL
            || active == RegisterPicker.LAST_USED
            || active == RegisterPicker.PERSONS
            || active == RegisterPicker.CLOSED;
    List<Row> accountRows = showAccounts ? accountTreeRows(nodes, members, ticked) : List.of();
    List<Row> personRows = showPeople ? personRows(active, nodes, members, ticked) : List.of();
    return new RegisterFilterView(
        tabs, new Panel(active, active.param(), panelExpanded, accountRows, personRows));
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
                false,
                Marker.NONE));
      } else if (members.contains(account.accountId())) {
        rows.add(
            new Row(
                account.accountId(),
                account.name(),
                account.currencyCode(),
                account.hue(),
                node.depth(),
                false,
                null,
                memberOf,
                ticked.contains(account.accountId()),
                account.closedAt() != null ? Marker.CLOSED : Marker.NONE));
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

  /**
   * The per-person portion of a panel, three levels deep (issue transaction-register-ui/22, owner
   * feedback): a single {@code Persons} umbrella toggle, then one toggle per person that has a
   * member leaf (alphabetical by name, from {@link RegisterPickerService#personGroups}), then that
   * person's currency leaves as checkboxes labelled by the bare currency code (alphabetical by
   * code). A soft-deleted person's toggle carries {@link Marker#DELETED} (issue
   * transaction-register-ui/23). Empty when no person leaf is a member — so {@link
   * RegisterPicker#OPEN} shows nothing here, and so does {@link RegisterPicker#CLOSED} until a
   * person is soft-deleted while still owning a live leaf.
   */
  private List<Row> personRows(
      RegisterPicker picker, List<AccountNode> nodes, Set<Long> members, Set<Long> ticked) {
    Map<Long, Account> byId = byId(nodes);
    List<PersonGroup> groups = pickerService.personGroups(picker);
    boolean anyMemberLeaf =
        groups.stream().flatMap(g -> g.leafAccountIds().stream()).anyMatch(members::contains);
    if (!anyMemberLeaf) {
      return List.of();
    }
    List<Row> rows = new ArrayList<>();
    rows.add(groupToggleRow("Persons", PERSONS_UMBRELLA_KEY, null, 0, Marker.NONE));
    for (PersonGroup group : groups) {
      List<Account> leaves =
          group.leafAccountIds().stream()
              .filter(members::contains)
              .map(byId::get)
              .filter(Objects::nonNull)
              .sorted(Comparator.comparing(Account::currencyCode))
              .toList();
      if (leaves.isEmpty()) {
        continue;
      }
      String personKey = "p" + group.personId();
      rows.add(
          groupToggleRow(
              group.name(),
              personKey,
              PERSONS_UMBRELLA_KEY,
              1,
              group.deleted() ? Marker.DELETED : Marker.NONE));
      String memberOf = PERSONS_UMBRELLA_KEY + " " + personKey;
      for (Account leaf : leaves) {
        rows.add(
            new Row(
                leaf.accountId(),
                leaf.currencyCode(),
                null,
                leaf.hue(),
                2,
                false,
                null,
                memberOf,
                ticked.contains(leaf.accountId()),
                Marker.NONE));
      }
    }
    return rows;
  }

  private static Row groupToggleRow(
      String label, String key, String memberOf, int depth, Marker marker) {
    return new Row(0L, label, null, null, depth, true, key, memberOf, false, marker);
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
}
