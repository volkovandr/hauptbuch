package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountNode;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.CurrencyBalance;
import volkovandr.hauptbuch.debts.PersonBalanceSummary;
import volkovandr.hauptbuch.ledger.RegisterFilterView.Row;
import volkovandr.hauptbuch.ledger.RegisterFilterView.Tab;

/**
 * Unit tier (plan §1.5): {@link RegisterFilterViewAssembler} turning a resolved picker membership
 * into the tab strip + panel rows, with its collaborators mocked. Membership resolution itself is
 * {@link RegisterPickerServiceTest}'s.
 */
@ExtendWith(MockitoExtension.class)
class RegisterFilterViewAssemblerTest {

  private static final String EUR = "EUR";
  private static final String ASSET = "asset";
  private static final List<String> OWN_TYPES = List.of("asset", "liability");

  @Mock private RegisterPickerService pickerService;
  @Mock private AccountService accountService;

  private RegisterFilterViewAssembler assembler;

  @BeforeEach
  void setUp() {
    assembler = new RegisterFilterViewAssembler(pickerService, accountService);
    lenient().when(pickerService.livePeopleUnsettledFirst()).thenReturn(List.of());
    lenient().when(accountService.findLiveByTypesWithDepth(OWN_TYPES)).thenReturn(List.of());
  }

  private static Account account(long id, String name, Long parentId) {
    return new Account(
        id, name, ASSET, parentId, EUR, 200, LocalDate.now(), null, null, false, false, false);
  }

  private static Account personLeaf(long id) {
    return new Account(
        id,
        "personal.EUR",
        ASSET,
        null,
        EUR,
        null,
        LocalDate.now(),
        null,
        null,
        false,
        true,
        false);
  }

  private static AccountNode node(Account a, int depth) {
    return new AccountNode(a, depth);
  }

  private RegisterFilter filter(RegisterPicker picker, Long... ticked) {
    return new RegisterFilter(List.of(ticked), picker, null, null, null);
  }

  @Test
  void openPanelRendersEachMemberLeafAsCheckedCheckbox() {
    when(accountService.findLiveByTypesWithDepth(OWN_TYPES))
        .thenReturn(
            List.of(node(account(10, "Cash", null), 0), node(account(11, "Giro", null), 0)));
    when(pickerService.membership(RegisterPicker.OPEN, null, null)).thenReturn(List.of(10L, 11L));

    List<Row> rows = assembler.filterView(filter(RegisterPicker.OPEN), false).panel().rows();

    assertThat(rows)
        .extracting(Row::accountId, Row::label, Row::group, Row::ticked)
        .containsExactly(tuple(10L, "Cash", false, true), tuple(11L, "Giro", false, true));
  }

  @Test
  void parentAccountRendersAsGroupToggleAboveItsMemberChildren() {
    when(accountService.findLiveByTypesWithDepth(OWN_TYPES))
        .thenReturn(
            List.of(
                node(account(20, "Banks", null), 0),
                node(account(21, "Giro", 20L), 1),
                node(account(22, "Savings", 20L), 1)));
    when(pickerService.membership(RegisterPicker.OPEN, null, null)).thenReturn(List.of(21L, 22L));

    List<Row> rows = assembler.filterView(filter(RegisterPicker.OPEN), false).panel().rows();

    assertThat(rows)
        .extracting(Row::label, Row::group, Row::groupKey, Row::memberOf)
        .containsExactly(
            tuple("Banks", true, "g20", null),
            tuple("Giro", false, null, "g20"),
            tuple("Savings", false, null, "g20"));
  }

  @Test
  void personsPanelIsThreeDeepUmbrellaThenPersonThenCurrencyUnsettledFirst() {
    when(accountService.findLiveByTypesWithDepth(OWN_TYPES))
        .thenReturn(
            List.of(node(personLeaf(101), 0), node(personLeaf(201), 0), node(personLeaf(202), 0)));
    when(pickerService.membership(RegisterPicker.PERSONS, null, null))
        .thenReturn(List.of(201L, 202L, 101L));
    when(pickerService.livePeopleUnsettledFirst())
        .thenReturn(
            List.of(
                new PersonBalanceSummary(
                    2L,
                    "Bob",
                    List.of(new CurrencyBalance(EUR, new BigDecimal("5"))),
                    List.of(201L, 202L)),
                new PersonBalanceSummary(1L, "Alice", List.of(), List.of(101L))));

    List<Row> rows = assembler.filterView(filter(RegisterPicker.PERSONS), false).panel().rows();

    // Umbrella (depth 0) → person toggle (depth 1, under 'persons') → currency leaves (depth 2),
    // each labelled by the bare currency code.
    assertThat(rows)
        .extracting(Row::label, Row::group, Row::groupKey, Row::memberOf, Row::depth)
        .containsExactly(
            tuple("Persons", true, "persons", null, 0),
            tuple("Bob", true, "p2", "persons", 1),
            tuple(EUR, false, null, "persons p2", 2),
            tuple(EUR, false, null, "persons p2", 2),
            tuple("Alice", true, "p1", "persons", 1),
            tuple(EUR, false, null, "persons p1", 2));
  }

  @Test
  void lastUsedPutsPersonLeavesUnderThePersonsUmbrella() {
    when(accountService.findLiveByTypesWithDepth(OWN_TYPES))
        .thenReturn(List.of(node(account(10, "Cash", null), 0), node(personLeaf(101), 0)));
    when(pickerService.membership(RegisterPicker.LAST_USED, null, null))
        .thenReturn(List.of(10L, 101L));
    when(pickerService.livePeopleUnsettledFirst())
        .thenReturn(List.of(new PersonBalanceSummary(3L, "Max", List.of(), List.of(101L))));

    List<Row> rows = assembler.filterView(filter(RegisterPicker.LAST_USED), false).panel().rows();

    assertThat(rows)
        .extracting(Row::label, Row::group, Row::groupKey, Row::memberOf)
        .containsExactly(
            tuple("Cash", false, null, null),
            tuple("Persons", true, "persons", null),
            tuple("Max", true, "p3", "persons"),
            tuple(EUR, false, null, "persons p3"));
  }

  @Test
  void tabStripCountsOnlyTheActiveTab() {
    when(pickerService.membership(RegisterPicker.OPEN, null, null)).thenReturn(List.of(10L, 11L));

    List<Tab> tabs = assembler.filterView(filter(RegisterPicker.OPEN), false).tabs();

    assertThat(tabs)
        .extracting(Tab::param, Tab::active, Tab::tickedCount)
        .contains(
            tuple("open", true, 2), tuple("last-used", false, null), tuple("closed", false, null));
  }

  @Test
  void partialSelectionTicksOnlyTheSubmittedAccounts() {
    when(accountService.findLiveByTypesWithDepth(OWN_TYPES))
        .thenReturn(
            List.of(node(account(10, "Cash", null), 0), node(account(11, "Giro", null), 0)));
    when(pickerService.membership(RegisterPicker.OPEN, null, null)).thenReturn(List.of(10L, 11L));

    RegisterFilterView view = assembler.filterView(filter(RegisterPicker.OPEN, 11L), false);

    assertThat(view.panel().rows())
        .extracting(Row::accountId, Row::ticked)
        .containsExactly(tuple(10L, false), tuple(11L, true));
    assertThat(view.tabs()).filteredOn(Tab::active).extracting(Tab::tickedCount).containsExactly(1);
  }
}
