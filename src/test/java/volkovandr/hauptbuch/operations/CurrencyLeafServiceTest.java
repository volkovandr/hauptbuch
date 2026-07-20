package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.operations.repository.PostingReassignmentRepository;

/**
 * Unit tier (plan §1.5): the lazy per-currency-leaf routing of data-model §6.5 with the DB mocked —
 * the three cases the dock's commit path relies on: a matching-currency leaf is posted to directly;
 * a differing-currency leaf subdivides into per-currency leaves (postings moved); an
 * already-subdivided parent reuses or creates its currency child. Every created leaf is marked
 * {@code currencyLeaf} and named after the bare currency code (plan stage 7d.1 follow-up) — hidden
 * from every picker, never named after its parent.
 */
@ExtendWith(MockitoExtension.class)
class CurrencyLeafServiceTest {

  private static final long FOOD_ID = 10L;
  private static final long OWN_LEAF_ID = 11L;
  private static final long NEW_LEAF_ID = 12L;
  private static final String EXPENSE = "expense";
  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final String FOOD = "Food";

  @Mock private AccountService accountService;
  @Mock private PostingReassignmentRepository postingReassignmentRepository;

  private CurrencyLeafService currencyLeafService;

  @BeforeEach
  void setUp() {
    currencyLeafService = new CurrencyLeafService(accountService, postingReassignmentRepository);
  }

  private static Account account(long id, String name, Long parentId, String currency) {
    return new Account(id, name, EXPENSE, parentId, currency, null, null, null, null, false, false);
  }

  private static Account currencyLeaf(long id, String currencyCode, long parentId) {
    return new Account(
        id, currencyCode, EXPENSE, parentId, currencyCode, null, null, null, null, true, false);
  }

  @Test
  void postsDirectlyToLeafAlreadyInTheTargetCurrency() {
    Account food = account(FOOD_ID, FOOD, null, EUR);
    when(accountService.findById(FOOD_ID)).thenReturn(Optional.of(food));
    when(accountService.findChildrenOf(FOOD_ID)).thenReturn(List.of());

    Account resolved = currencyLeafService.resolveCurrencyLeaf(FOOD_ID, EUR);

    assertThat(resolved).isEqualTo(food);
    // No leaf created, no postings moved — the leaf already fits (§6.5).
    verify(accountService, never()).insertCurrencyLeaf(any(), any(), anyLong());
    verify(postingReassignmentRepository, never()).reassignPostings(anyLong(), anyLong());
  }

  @Test
  void subdividesPostedLeafOnFirstSpendInNewCurrency() {
    Account food = account(FOOD_ID, FOOD, null, EUR);
    when(accountService.findById(FOOD_ID)).thenReturn(Optional.of(food));
    when(accountService.findChildrenOf(FOOD_ID)).thenReturn(List.of());
    when(accountService.hasPostings(FOOD_ID)).thenReturn(true);
    Account ownLeaf = currencyLeaf(OWN_LEAF_ID, EUR, FOOD_ID);
    Account chfLeaf = currencyLeaf(NEW_LEAF_ID, CHF, FOOD_ID);
    when(accountService.insertCurrencyLeaf(EUR, EXPENSE, FOOD_ID)).thenReturn(ownLeaf);
    when(accountService.insertCurrencyLeaf(CHF, EXPENSE, FOOD_ID)).thenReturn(chfLeaf);

    Account resolved = currencyLeafService.resolveCurrencyLeaf(FOOD_ID, CHF);

    // The incoming CHF posting lands on the new CHF leaf; the leaf's old EUR postings move to the
    // new EUR leaf, so the promoted Food is a pure rollup (leaves-only, §6.5).
    assertThat(resolved).isEqualTo(chfLeaf);
    verify(postingReassignmentRepository).reassignPostings(FOOD_ID, OWN_LEAF_ID);
  }

  @Test
  void promotesAnUnpostedLeafWithoutManufacturingAnEmptyOwnCurrencyLeaf() {
    // A never-posted EUR leaf getting its first spend in CHF: it becomes a parent of a CHF leaf,
    // but no empty EUR leaf is created (leaves appear only when spent — §6.5).
    Account food = account(FOOD_ID, FOOD, null, EUR);
    when(accountService.findById(FOOD_ID)).thenReturn(Optional.of(food));
    when(accountService.findChildrenOf(FOOD_ID)).thenReturn(List.of());
    when(accountService.hasPostings(FOOD_ID)).thenReturn(false);
    Account chfLeaf = currencyLeaf(NEW_LEAF_ID, CHF, FOOD_ID);
    when(accountService.insertCurrencyLeaf(CHF, EXPENSE, FOOD_ID)).thenReturn(chfLeaf);

    Account resolved = currencyLeafService.resolveCurrencyLeaf(FOOD_ID, CHF);

    assertThat(resolved).isEqualTo(chfLeaf);
    verify(accountService, never()).insertCurrencyLeaf(EUR, EXPENSE, FOOD_ID);
    verify(postingReassignmentRepository, never()).reassignPostings(anyLong(), anyLong());
  }

  @Test
  void reusesAnExistingCurrencyChildUnderAnAlreadySubdividedParent() {
    Account food = account(FOOD_ID, FOOD, null, EUR);
    Account eurChild = currencyLeaf(OWN_LEAF_ID, EUR, FOOD_ID);
    Account chfChild = currencyLeaf(NEW_LEAF_ID, CHF, FOOD_ID);
    when(accountService.findById(FOOD_ID)).thenReturn(Optional.of(food));
    when(accountService.findChildrenOf(FOOD_ID)).thenReturn(List.of(eurChild, chfChild));

    Account resolved = currencyLeafService.resolveCurrencyLeaf(FOOD_ID, CHF);

    assertThat(resolved).isEqualTo(chfChild);
    verify(accountService, never()).insertCurrencyLeaf(any(), any(), anyLong());
  }

  @Test
  void createsMissingCurrencyChildUnderAnAlreadySubdividedParent() {
    Account food = account(FOOD_ID, FOOD, null, EUR);
    Account eurChild = currencyLeaf(OWN_LEAF_ID, EUR, FOOD_ID);
    when(accountService.findById(FOOD_ID)).thenReturn(Optional.of(food));
    when(accountService.findChildrenOf(FOOD_ID)).thenReturn(List.of(eurChild));
    Account chfChild = currencyLeaf(NEW_LEAF_ID, CHF, FOOD_ID);
    when(accountService.insertCurrencyLeaf(CHF, EXPENSE, FOOD_ID)).thenReturn(chfChild);

    Account resolved = currencyLeafService.resolveCurrencyLeaf(FOOD_ID, CHF);

    assertThat(resolved).isEqualTo(chfChild);
    // No postings to move — the parent's other currency children keep their own.
    verify(postingReassignmentRepository, never()).reassignPostings(anyLong(), anyLong());
  }

  @Test
  void refusesAnUnknownCategory() {
    when(accountService.findById(FOOD_ID)).thenReturn(Optional.empty());

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> currencyLeafService.resolveCurrencyLeaf(FOOD_ID, CHF))
        .withMessageContaining("No category");
  }
}
