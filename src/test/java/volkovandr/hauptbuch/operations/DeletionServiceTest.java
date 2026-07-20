package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyList;
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
 * Unit tier (plan §1.5): the category-deletion operation's decision logic with the DB mocked away.
 * The whole subtree's postings converge onto the chosen target, then every subtree row is
 * soft-deleted; the target must be a live leaf outside the subtree (data-model §5).
 */
@ExtendWith(MockitoExtension.class)
class DeletionServiceTest {

  private static final long FOOD_ID = 10L;
  private static final long MILK_ID = 11L;
  private static final long BREAD_ID = 12L;
  private static final long TARGET_ID = 20L;
  private static final String EXPENSE = "expense";
  private static final String EUR = "EUR";

  @Mock private AccountService accountService;
  @Mock private PostingReassignmentRepository postingReassignmentRepository;

  private DeletionService deletionService;

  @BeforeEach
  void setUp() {
    deletionService = new DeletionService(accountService, postingReassignmentRepository);
  }

  private static Account account(long id, String name, Long parentId) {
    return new Account(id, name, EXPENSE, parentId, EUR, null, null, null, null, false, false);
  }

  @Test
  void deletesSubtreeAfterMovingEveryPostingToTarget() {
    List<Long> subtree = List.of(FOOD_ID, MILK_ID, BREAD_ID);
    when(accountService.findById(TARGET_ID))
        .thenReturn(Optional.of(account(TARGET_ID, "Groceries", null)));
    when(accountService.findChildrenOf(TARGET_ID)).thenReturn(List.of());
    when(accountService.findSubtreeAccountIds(FOOD_ID)).thenReturn(subtree);

    deletionService.deleteCategory(FOOD_ID, TARGET_ID);

    verify(postingReassignmentRepository).reassignPostings(subtree, TARGET_ID);
    verify(accountService).softDelete(subtree);
  }

  @Test
  void rejectsTargetInsideTheSubtree() {
    List<Long> subtree = List.of(FOOD_ID, MILK_ID);
    when(accountService.findById(MILK_ID))
        .thenReturn(Optional.of(account(MILK_ID, "Milk", FOOD_ID)));
    when(accountService.findSubtreeAccountIds(FOOD_ID)).thenReturn(subtree);

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> deletionService.deleteCategory(FOOD_ID, MILK_ID))
        .withMessageContaining("within the subtree");

    verify(postingReassignmentRepository, never()).reassignPostings(anyList(), anyLong());
    verify(accountService, never()).softDelete(anyList());
  }

  @Test
  void rejectsTargetThatStillHasSurvivingChildren() {
    when(accountService.findById(TARGET_ID))
        .thenReturn(Optional.of(account(TARGET_ID, "Food", null)));
    when(accountService.findSubtreeAccountIds(FOOD_ID)).thenReturn(List.of(FOOD_ID));
    // The target still has a child that is not in the subtree, so it stays a parent.
    when(accountService.findChildrenOf(TARGET_ID))
        .thenReturn(List.of(account(MILK_ID, "Milk", TARGET_ID)));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> deletionService.deleteCategory(FOOD_ID, TARGET_ID))
        .withMessageContaining("leaf");

    verify(postingReassignmentRepository, never()).reassignPostings(anyList(), anyLong());
    verify(accountService, never()).softDelete(anyList());
  }

  @Test
  void acceptsTargetWhoseOnlyChildIsInTheSubtree() {
    // Deleting M&Ms (the whole subtree) leaves its parent Sweets childless — Sweets is a valid
    // target even though it is a parent *now*, because its only child is being deleted.
    long mmsId = 30L;
    long sweetsId = 31L;
    List<Long> subtree = List.of(mmsId);
    when(accountService.findById(sweetsId))
        .thenReturn(Optional.of(account(sweetsId, "Sweets", null)));
    when(accountService.findSubtreeAccountIds(mmsId)).thenReturn(subtree);
    when(accountService.findChildrenOf(sweetsId))
        .thenReturn(List.of(account(mmsId, "M&Ms", sweetsId)));

    deletionService.deleteCategory(mmsId, sweetsId);

    verify(postingReassignmentRepository).reassignPostings(subtree, sweetsId);
    verify(accountService).softDelete(subtree);
  }

  @Test
  void rejectsUnknownTarget() {
    when(accountService.findById(TARGET_ID)).thenReturn(Optional.empty());

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> deletionService.deleteCategory(FOOD_ID, TARGET_ID))
        .withMessageContaining("No account");

    verify(accountService, never()).softDelete(anyList());
  }
}
