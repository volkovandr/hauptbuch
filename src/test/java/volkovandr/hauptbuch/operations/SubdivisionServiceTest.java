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
 * Unit tier (plan §1.5): the subdivision operation's decision logic with the DB mocked away — a
 * posted-to leaf gains a child and a catch-all sibling with the postings moved onto it; a leaf with
 * no postings just gains the child; a leaf whose only children are auto-managed currency leaves
 * (data-model §6.5, plan stage 7d.1 follow-up) is still subdividable, its currency leaves
 * re-parented onto the new catch-all; a leaf with a real child refuses (data-model §5).
 */
@ExtendWith(MockitoExtension.class)
class SubdivisionServiceTest {

  private static final long LEAF_ID = 10L;
  private static final long CHILD_ID = 11L;
  private static final long CATCH_ALL_ID = 12L;
  private static final long CURRENCY_LEAF_EUR_ID = 20L;
  private static final long CURRENCY_LEAF_CHF_ID = 21L;
  private static final String EXPENSE = "expense";
  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final String FOOD = "Food";
  private static final String MILK = "Milk";
  private static final String UNCATEGORIZED = "Uncategorized";

  @Mock private AccountService accountService;
  @Mock private PostingReassignmentRepository postingReassignmentRepository;

  private SubdivisionService subdivisionService;

  @BeforeEach
  void setUp() {
    subdivisionService = new SubdivisionService(accountService, postingReassignmentRepository);
  }

  private static Account account(long id, String name, String type, Long parentId) {
    return new Account(id, name, type, parentId, EUR, null, null, null, null, false);
  }

  private static Account currencyLeaf(long id, String currencyCode, String type, long parentId) {
    return new Account(
        id, currencyCode, type, parentId, currencyCode, null, null, null, null, true);
  }

  @Test
  void subdividesPostedLeafIntoChildAndCatchAll() {
    Account leaf = account(LEAF_ID, FOOD, EXPENSE, null);
    when(accountService.findById(LEAF_ID)).thenReturn(Optional.of(leaf));
    when(accountService.findChildrenOf(LEAF_ID)).thenReturn(List.of());
    when(accountService.hasPostings(LEAF_ID)).thenReturn(true);
    Account child = account(CHILD_ID, MILK, EXPENSE, LEAF_ID);
    Account catchAll = account(CATCH_ALL_ID, UNCATEGORIZED, EXPENSE, LEAF_ID);
    when(accountService.insertLeaf(MILK, EXPENSE, LEAF_ID, EUR)).thenReturn(child);
    when(accountService.insertLeaf(UNCATEGORIZED, EXPENSE, LEAF_ID, EUR)).thenReturn(catchAll);

    SubdivisionResult result = subdivisionService.subdivideAccount(LEAF_ID, MILK, UNCATEGORIZED);

    assertThat(result.child()).isEqualTo(child);
    assertThat(result.catchAll()).isEqualTo(catchAll);
    verify(postingReassignmentRepository).reassignPostings(LEAF_ID, CATCH_ALL_ID);
  }

  @Test
  void leafWithNoPostingsJustGainsChildNoCatchAll() {
    Account leaf = account(LEAF_ID, FOOD, EXPENSE, null);
    when(accountService.findById(LEAF_ID)).thenReturn(Optional.of(leaf));
    when(accountService.findChildrenOf(LEAF_ID)).thenReturn(List.of());
    when(accountService.hasPostings(LEAF_ID)).thenReturn(false);
    Account child = account(CHILD_ID, MILK, EXPENSE, LEAF_ID);
    when(accountService.insertLeaf(MILK, EXPENSE, LEAF_ID, EUR)).thenReturn(child);

    SubdivisionResult result = subdivisionService.subdivideAccount(LEAF_ID, MILK, UNCATEGORIZED);

    assertThat(result.child()).isEqualTo(child);
    assertThat(result.catchAll()).isNull();
    verify(accountService, never()).insertLeaf(UNCATEGORIZED, EXPENSE, LEAF_ID, EUR);
    verify(postingReassignmentRepository, never()).reassignPostings(anyLong(), anyLong());
  }

  @Test
  void leafWithOnlyCurrencyLeafChildrenIsStillSubdividable() {
    // The bug scenario: "Food" itself was never posted to directly — its EUR/CHF spends already
    // routed onto auto-managed currency leaves (data-model §6.5) — so it must still be treated as
    // a leaf, and those currency leaves must move under the new catch-all, not be left stranded.
    Account leaf = account(LEAF_ID, FOOD, EXPENSE, null);
    Account eurLeaf = currencyLeaf(CURRENCY_LEAF_EUR_ID, EUR, EXPENSE, LEAF_ID);
    Account chfLeaf = currencyLeaf(CURRENCY_LEAF_CHF_ID, CHF, EXPENSE, LEAF_ID);
    when(accountService.findById(LEAF_ID)).thenReturn(Optional.of(leaf));
    when(accountService.findChildrenOf(LEAF_ID)).thenReturn(List.of(eurLeaf, chfLeaf));
    when(accountService.hasPostings(LEAF_ID)).thenReturn(false);
    Account child = account(CHILD_ID, "Restaurants", EXPENSE, LEAF_ID);
    Account catchAll = account(CATCH_ALL_ID, UNCATEGORIZED, EXPENSE, LEAF_ID);
    when(accountService.insertLeaf("Restaurants", EXPENSE, LEAF_ID, EUR)).thenReturn(child);
    when(accountService.insertLeaf(UNCATEGORIZED, EXPENSE, LEAF_ID, EUR)).thenReturn(catchAll);

    SubdivisionResult result =
        subdivisionService.subdivideAccount(LEAF_ID, "Restaurants", UNCATEGORIZED);

    assertThat(result.child()).isEqualTo(child);
    assertThat(result.catchAll()).isEqualTo(catchAll);
    // No direct postings on the leaf itself, so nothing to reassign — only the currency leaves
    // move.
    verify(postingReassignmentRepository, never()).reassignPostings(anyLong(), anyLong());
    verify(accountService).reparent(CURRENCY_LEAF_EUR_ID, CATCH_ALL_ID);
    verify(accountService).reparent(CURRENCY_LEAF_CHF_ID, CATCH_ALL_ID);
  }

  @Test
  void refusesToSubdivideAnAccountThatAlreadyHasRealChild() {
    Account parent = account(LEAF_ID, FOOD, EXPENSE, null);
    when(accountService.findById(LEAF_ID)).thenReturn(Optional.of(parent));
    when(accountService.findChildrenOf(LEAF_ID))
        .thenReturn(List.of(account(CHILD_ID, "Restaurants", EXPENSE, LEAF_ID)));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> subdivisionService.subdivideAccount(LEAF_ID, MILK, UNCATEGORIZED))
        .withMessageContaining("already has children");

    verify(accountService, never()).insertLeaf(any(), any(), any(), any());
  }

  @Test
  void refusesUnknownAccount() {
    when(accountService.findById(LEAF_ID)).thenReturn(Optional.empty());

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> subdivisionService.subdivideAccount(LEAF_ID, MILK, UNCATEGORIZED))
        .withMessageContaining("No account");
  }
}
