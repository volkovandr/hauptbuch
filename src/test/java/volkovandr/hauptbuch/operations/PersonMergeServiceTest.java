package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.debts.CurrencyBalance;
import volkovandr.hauptbuch.debts.Person;
import volkovandr.hauptbuch.debts.PersonLeaf;
import volkovandr.hauptbuch.debts.PersonProvisioningService;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.operations.PersonMergeView.TargetOption;
import volkovandr.hauptbuch.operations.repository.PostingReassignmentRepository;

/**
 * Unit tier (plan §1.5): the person-merge orchestration with {@code debts} provisioning and the
 * reassignment repository mocked. It proves what merge <em>decides</em> — each source leaf's
 * postings move onto the target's leaf in the same currency (provisioned on demand), the source is
 * retired only after every fold, and a self-merge or a non-live party is refused — leaving the
 * actual posting move and the end-state to {@code PersonMergeScreenIntegrationTest} against real
 * Postgres.
 */
@ExtendWith(MockitoExtension.class)
class PersonMergeServiceTest {

  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final long SOURCE = 1L;
  private static final long TARGET = 2L;

  @Mock private PersonService personService;
  @Mock private PersonProvisioningService personProvisioningService;
  @Mock private PostingReassignmentRepository postingReassignmentRepository;
  @Mock private SettingsService settingsService;

  private PersonMergeService service;

  @BeforeEach
  void setUp() {
    service =
        new PersonMergeService(
            personService,
            personProvisioningService,
            postingReassignmentRepository,
            settingsService);
  }

  private static Account leaf(long id, String currency) {
    return new Account(
        id, "personal." + currency, "asset", null, currency, null, null, null, null, false, true);
  }

  private void stubLive(long personId, String name) {
    when(personService.findById(personId))
        .thenReturn(Optional.of(new Person(personId, name, null)));
  }

  @Test
  void foldsEachSourceLeafOntoTheTargetsMatchingCurrencyLeafThenRetiresTheSource() {
    stubLive(SOURCE, "Max");
    stubLive(TARGET, "Alex");
    when(personService.leavesOf(SOURCE))
        .thenReturn(List.of(new PersonLeaf(10L, EUR), new PersonLeaf(11L, CHF)));
    when(personProvisioningService.ensureLeaf(TARGET, EUR)).thenReturn(leaf(20L, EUR));
    when(personProvisioningService.ensureLeaf(TARGET, CHF)).thenReturn(leaf(21L, CHF));

    service.merge(SOURCE, TARGET);

    verify(postingReassignmentRepository).reassignPostings(10L, 20L);
    verify(postingReassignmentRepository).reassignPostings(11L, 21L);
    // The source is retired only after the fold — the zero-balance guard is a genuine
    // post-condition.
    InOrder inOrder = inOrder(postingReassignmentRepository, personService);
    inOrder.verify(postingReassignmentRepository).reassignPostings(11L, 21L);
    inOrder.verify(personService).softDeleteIfZeroBalance(SOURCE);
  }

  @Test
  void rejectsMergingIntoThemselves() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> service.merge(SOURCE, SOURCE))
        .withMessageContaining("themselves");
  }

  @Test
  void rejectsWhenTheSourceIsNotLive() {
    when(personService.findById(SOURCE)).thenReturn(Optional.empty());

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> service.merge(SOURCE, TARGET))
        .withMessageContaining("source");
  }

  @Test
  void rejectsWhenTheTargetIsNotLive() {
    stubLive(SOURCE, "Max");
    when(personService.findById(TARGET)).thenReturn(Optional.empty());

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> service.merge(SOURCE, TARGET))
        .withMessageContaining("target");
  }

  @Test
  void assembleListsLivePersonsExceptTheSourceAndTheSourcesPositions() {
    stubLive(SOURCE, "Max");
    when(personService.findAllLive())
        .thenReturn(
            List.of(
                new Person(SOURCE, "Max", null),
                new Person(TARGET, "Alex", null),
                new Person(3L, "Bob", null)));
    when(personService.balancesOf(SOURCE))
        .thenReturn(List.of(new CurrencyBalance(CHF, new BigDecimal("-10.00"))));
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));

    PersonMergeView view = service.assemble(SOURCE);

    assertThat(view.personName()).isEqualTo("Max");
    assertThat(view.targets())
        .extracting(TargetOption::personId, TargetOption::name)
        .containsExactly(tuple(TARGET, "Alex"), tuple(3L, "Bob"));
    assertThat(view.positions()).singleElement().asString().contains("You owe Max");
  }
}
