package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.operations.repository.PostingReassignmentRepository;

/**
 * Unit tier (plan §1.5): the category-deletion operation's decision logic with the DB mocked away.
 * The whole subtree is soft-deleted and its postings converge onto the chosen target — one
 * reassignment per currency the subtree actually carries, each landing on the target's leaf for
 * that currency (issue category-management/05), because a posting is denominated in its account's
 * currency and must never change what it means.
 *
 * <p>The target must be a live category of the subtree root's own type, outside the subtree, and
 * free of <em>real</em> subcategories once the deletion has happened — auto-managed currency leaves
 * don't disqualify it, since the routing goes through them rather than around them.
 */
@ExtendWith(MockitoExtension.class)
class DeletionServiceTest {

  private static final long FOOD_ID = 10L;
  private static final long MILK_ID = 11L;
  private static final long BREAD_ID = 12L;
  private static final long TARGET_ID = 20L;
  private static final long TARGET_EUR_LEAF_ID = 21L;
  private static final long TARGET_CHF_LEAF_ID = 22L;
  private static final String EXPENSE = "expense";
  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final String GROCERIES = "Groceries";

  @Mock private AccountService accountService;
  @Mock private CurrencyLeafService currencyLeafService;
  @Mock private PostingReassignmentRepository postingReassignmentRepository;

  private DeletionService deletionService;

  @BeforeEach
  void setUp() {
    deletionService =
        new DeletionService(accountService, currencyLeafService, postingReassignmentRepository);
  }

  private static Account account(long id, String name, Long parentId) {
    return account(id, name, parentId, EUR);
  }

  private static Account account(long id, String name, Long parentId, String currencyCode) {
    return new Account(
        id, name, EXPENSE, parentId, currencyCode, null, null, null, null, false, false, false);
  }

  private static Account currencyLeaf(long id, String currencyCode, long parentId) {
    return new Account(
        id,
        currencyCode,
        EXPENSE,
        parentId,
        currencyCode,
        null,
        null,
        null,
        null,
        true,
        false,
        false);
  }

  /** A childless expense leaf in EUR, offered as the destination. */
  private void targetIsChildlessLeaf() {
    when(accountService.findById(TARGET_ID))
        .thenReturn(Optional.of(account(TARGET_ID, GROCERIES, null)));
    when(accountService.findChildrenOf(TARGET_ID)).thenReturn(List.of());
  }

  @Test
  void deletesSubtreeAndMovesEveryPostingToTheTargetsLeafForItsCurrency() {
    List<Account> subtree =
        List.of(
            account(FOOD_ID, "Food", null),
            account(MILK_ID, "Milk", FOOD_ID),
            account(BREAD_ID, "Bread", FOOD_ID));
    List<Long> subtreeIds = List.of(FOOD_ID, MILK_ID, BREAD_ID);
    when(accountService.findSubtreeAccounts(FOOD_ID)).thenReturn(subtree);
    targetIsChildlessLeaf();
    when(accountService.hasAnyPostings(subtreeIds)).thenReturn(true);
    when(currencyLeafService.resolveCurrencyLeaf(TARGET_ID, EUR))
        .thenReturn(account(TARGET_ID, GROCERIES, null));

    deletionService.deleteCategory(FOOD_ID, TARGET_ID);

    verify(postingReassignmentRepository).reassignPostings(subtreeIds, TARGET_ID);
    verify(accountService).softDelete(subtreeIds);
  }

  @Test
  void filesEachCurrencysPostingsOnTheTargetsLeafForThatCurrency() {
    // The subtree spans two currencies. A CHF posting moved onto a EUR leaf would silently become
    // a EUR amount — the whole point of routing per currency (issue category-management/05).
    List<Account> subtree =
        List.of(
            account(FOOD_ID, "Food", null),
            currencyLeaf(MILK_ID, EUR, FOOD_ID),
            account(BREAD_ID, CHF, FOOD_ID, CHF));
    when(accountService.findSubtreeAccounts(FOOD_ID)).thenReturn(subtree);
    targetIsChildlessLeaf();
    when(accountService.hasAnyPostings(List.of(BREAD_ID))).thenReturn(true);
    when(accountService.hasAnyPostings(List.of(FOOD_ID, MILK_ID))).thenReturn(true);
    when(currencyLeafService.resolveCurrencyLeaf(TARGET_ID, CHF))
        .thenReturn(currencyLeaf(TARGET_CHF_LEAF_ID, CHF, TARGET_ID));
    when(currencyLeafService.resolveCurrencyLeaf(TARGET_ID, EUR))
        .thenReturn(currencyLeaf(TARGET_EUR_LEAF_ID, EUR, TARGET_ID));

    deletionService.deleteCategory(FOOD_ID, TARGET_ID);

    verify(postingReassignmentRepository).reassignPostings(List.of(BREAD_ID), TARGET_CHF_LEAF_ID);
    verify(postingReassignmentRepository)
        .reassignPostings(List.of(FOOD_ID, MILK_ID), TARGET_EUR_LEAF_ID);
  }

  @Test
  void resolvesAndReassignsOneCurrencyBeforeResolvingTheNext() {
    // Order is correctness, not taste: resolving the second currency can subdivide the target,
    // which invalidates a leaf id resolved earlier. Done in sequence, the later subdivision
    // carries the already-reassigned postings with it (data-model §6.5).
    List<Account> subtree =
        List.of(account(FOOD_ID, "Food", null), account(BREAD_ID, CHF, FOOD_ID, CHF));
    when(accountService.findSubtreeAccounts(FOOD_ID)).thenReturn(subtree);
    targetIsChildlessLeaf();
    when(accountService.hasAnyPostings(List.of(BREAD_ID))).thenReturn(true);
    when(accountService.hasAnyPostings(List.of(FOOD_ID))).thenReturn(true);
    when(currencyLeafService.resolveCurrencyLeaf(TARGET_ID, CHF))
        .thenReturn(currencyLeaf(TARGET_CHF_LEAF_ID, CHF, TARGET_ID));
    when(currencyLeafService.resolveCurrencyLeaf(TARGET_ID, EUR))
        .thenReturn(currencyLeaf(TARGET_EUR_LEAF_ID, EUR, TARGET_ID));

    deletionService.deleteCategory(FOOD_ID, TARGET_ID);

    InOrder inOrder = inOrder(currencyLeafService, postingReassignmentRepository);
    inOrder.verify(currencyLeafService).resolveCurrencyLeaf(TARGET_ID, CHF);
    inOrder
        .verify(postingReassignmentRepository)
        .reassignPostings(List.of(BREAD_ID), TARGET_CHF_LEAF_ID);
    inOrder.verify(currencyLeafService).resolveCurrencyLeaf(TARGET_ID, EUR);
    inOrder
        .verify(postingReassignmentRepository)
        .reassignPostings(List.of(FOOD_ID), TARGET_EUR_LEAF_ID);
  }

  @Test
  void provisionsNothingForCurrencyLeavesThatCarryNoPostings() {
    // A leaf appears only when spent (data-model §6.5): an empty CHF leaf in the subtree must not
    // conjure a CHF leaf on the target.
    List<Account> subtree =
        List.of(account(FOOD_ID, "Food", null), currencyLeaf(BREAD_ID, CHF, FOOD_ID));
    when(accountService.findSubtreeAccounts(FOOD_ID)).thenReturn(subtree);
    targetIsChildlessLeaf();
    when(accountService.hasAnyPostings(List.of(BREAD_ID))).thenReturn(false);
    when(accountService.hasAnyPostings(List.of(FOOD_ID))).thenReturn(true);
    when(currencyLeafService.resolveCurrencyLeaf(TARGET_ID, EUR))
        .thenReturn(account(TARGET_ID, GROCERIES, null));

    deletionService.deleteCategory(FOOD_ID, TARGET_ID);

    verify(currencyLeafService, never()).resolveCurrencyLeaf(TARGET_ID, CHF);
    verify(postingReassignmentRepository, never()).reassignPostings(List.of(BREAD_ID), TARGET_ID);
  }

  @Test
  void deletesTheSubtreeBeforeRoutingSoTheTargetsChildrenAreOnlySurvivors() {
    // Deleting M&Ms leaves its parent Sweets childless, so Sweets is a valid target. The routing
    // reads live children, so the subtree must already be stamped when it runs — otherwise M&Ms,
    // about to disappear, is picked as Sweets' EUR child and the postings vanish with it.
    long mmsId = 30L;
    long sweetsId = 31L;
    when(accountService.findSubtreeAccounts(mmsId))
        .thenReturn(List.of(account(mmsId, "M&Ms", sweetsId)));
    when(accountService.findById(sweetsId))
        .thenReturn(Optional.of(account(sweetsId, "Sweets", null)));
    when(accountService.findChildrenOf(sweetsId))
        .thenReturn(List.of(account(mmsId, "M&Ms", sweetsId)));
    when(accountService.hasAnyPostings(List.of(mmsId))).thenReturn(true);
    when(currencyLeafService.resolveCurrencyLeaf(sweetsId, EUR))
        .thenReturn(account(sweetsId, "Sweets", null));

    deletionService.deleteCategory(mmsId, sweetsId);

    InOrder inOrder = inOrder(accountService, currencyLeafService);
    inOrder.verify(accountService).softDelete(List.of(mmsId));
    inOrder.verify(currencyLeafService).resolveCurrencyLeaf(sweetsId, EUR);
  }

  @Test
  void acceptsTargetWhoseOnlyChildrenAreAutoManagedCurrencyLeaves() {
    // The reported case (issue category-management/05): every category posted to in some currency
    // has hidden currency leaves. Excluding them all leaves nothing to offer, so the category type
    // becomes undeletable. The routing files the postings on those leaves, not on the parent.
    when(accountService.findSubtreeAccounts(FOOD_ID))
        .thenReturn(List.of(account(FOOD_ID, "Food", null)));
    when(accountService.findById(TARGET_ID))
        .thenReturn(Optional.of(account(TARGET_ID, GROCERIES, null)));
    when(accountService.findChildrenOf(TARGET_ID))
        .thenReturn(List.of(currencyLeaf(TARGET_EUR_LEAF_ID, EUR, TARGET_ID)));
    when(accountService.hasAnyPostings(List.of(FOOD_ID))).thenReturn(true);
    when(currencyLeafService.resolveCurrencyLeaf(TARGET_ID, EUR))
        .thenReturn(currencyLeaf(TARGET_EUR_LEAF_ID, EUR, TARGET_ID));

    deletionService.deleteCategory(FOOD_ID, TARGET_ID);

    verify(postingReassignmentRepository).reassignPostings(List.of(FOOD_ID), TARGET_EUR_LEAF_ID);
    verify(accountService).softDelete(List.of(FOOD_ID));
  }

  @Test
  void rejectsTargetThatStillHasRealSubcategories() {
    when(accountService.findSubtreeAccounts(FOOD_ID))
        .thenReturn(List.of(account(FOOD_ID, "Food", null)));
    when(accountService.findById(TARGET_ID))
        .thenReturn(Optional.of(account(TARGET_ID, GROCERIES, null)));
    // A real child that is not in the subtree — the target stays a genuine group.
    when(accountService.findChildrenOf(TARGET_ID))
        .thenReturn(List.of(account(MILK_ID, "Milk", TARGET_ID)));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> deletionService.deleteCategory(FOOD_ID, TARGET_ID))
        .withMessageContaining("leaf");

    verifyNothingHappened();
  }

  @Test
  void acceptsTargetWhoseOnlyRealChildIsInTheSubtree() {
    // Deleting M&Ms (the whole subtree) leaves its parent Sweets childless — Sweets is a valid
    // target even though it is a parent *now*, because its only child is being deleted.
    long mmsId = 30L;
    long sweetsId = 31L;
    when(accountService.findSubtreeAccounts(mmsId))
        .thenReturn(List.of(account(mmsId, "M&Ms", sweetsId)));
    when(accountService.findById(sweetsId))
        .thenReturn(Optional.of(account(sweetsId, "Sweets", null)));
    when(accountService.findChildrenOf(sweetsId))
        .thenReturn(List.of(account(mmsId, "M&Ms", sweetsId)));
    when(accountService.hasAnyPostings(List.of(mmsId))).thenReturn(false);

    deletionService.deleteCategory(mmsId, sweetsId);

    verify(accountService).softDelete(List.of(mmsId));
  }

  @Test
  void rejectsTargetInsideTheSubtree() {
    when(accountService.findSubtreeAccounts(FOOD_ID))
        .thenReturn(List.of(account(FOOD_ID, "Food", null), account(MILK_ID, "Milk", FOOD_ID)));
    when(accountService.findById(MILK_ID))
        .thenReturn(Optional.of(account(MILK_ID, "Milk", FOOD_ID)));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> deletionService.deleteCategory(FOOD_ID, MILK_ID))
        .withMessageContaining("within the subtree");

    verifyNothingHappened();
  }

  @Test
  void rejectsTargetThatIsNotCategory() {
    // Validated here rather than trusted from the offer list: the same operation is reachable from
    // a hand-crafted POST and, later, the MCP surface (CLAUDE.md §1.7).
    when(accountService.findSubtreeAccounts(FOOD_ID))
        .thenReturn(List.of(account(FOOD_ID, "Food", null)));
    when(accountService.findById(TARGET_ID))
        .thenReturn(
            Optional.of(
                new Account(
                    TARGET_ID, "Cash", "asset", null, EUR, null, null, null, null, false, false,
                    false)));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> deletionService.deleteCategory(FOOD_ID, TARGET_ID))
        .withMessageContaining("asset");

    verifyNothingHappened();
  }

  @Test
  void rejectsTargetOfTheWrongCategoryType() {
    when(accountService.findSubtreeAccounts(FOOD_ID))
        .thenReturn(List.of(account(FOOD_ID, "Food", null)));
    when(accountService.findById(TARGET_ID))
        .thenReturn(
            Optional.of(
                new Account(
                    TARGET_ID, "Salary", "income", null, EUR, null, null, null, null, false, false,
                    false)));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> deletionService.deleteCategory(FOOD_ID, TARGET_ID))
        .withMessageContaining("income");

    verifyNothingHappened();
  }

  @Test
  void rejectsTargetThatIsAutoManagedCurrencyLeaf() {
    // A currency leaf passes every other check — it is an expense account, live, childless, and
    // outside the subtree — but it is one currency's slot under a category, not a category. The
    // routing would subdivide it into nested currency leaves, a shape data-model §6.5 has no room
    // for and no screen shows. Only reachable by hand or from MCP; the offer list never shows one.
    when(accountService.findSubtreeAccounts(FOOD_ID))
        .thenReturn(List.of(account(FOOD_ID, "Food", null)));
    when(accountService.findById(TARGET_EUR_LEAF_ID))
        .thenReturn(Optional.of(currencyLeaf(TARGET_EUR_LEAF_ID, EUR, TARGET_ID)));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> deletionService.deleteCategory(FOOD_ID, TARGET_EUR_LEAF_ID))
        .withMessageContaining("currency leaf");

    verifyNothingHappened();
  }

  @Test
  void rejectsTargetThatHasItselfBeenDeleted() {
    when(accountService.findSubtreeAccounts(FOOD_ID))
        .thenReturn(List.of(account(FOOD_ID, "Food", null)));
    Account deleted =
        new Account(
            TARGET_ID,
            GROCERIES,
            EXPENSE,
            null,
            EUR,
            null,
            null,
            null,
            OffsetDateTime.now(),
            false,
            false,
            false);
    when(accountService.findById(TARGET_ID)).thenReturn(Optional.of(deleted));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> deletionService.deleteCategory(FOOD_ID, TARGET_ID))
        .withMessageContaining("deleted");

    verifyNothingHappened();
  }

  @Test
  void rejectsSubtreeRootThatIsNotCategory() {
    when(accountService.findSubtreeAccounts(FOOD_ID))
        .thenReturn(
            List.of(
                new Account(
                    FOOD_ID, "Cash", "asset", null, EUR, null, null, null, null, false, false,
                    false)));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> deletionService.deleteCategory(FOOD_ID, TARGET_ID))
        .withMessageContaining("not a category");

    verifyNothingHappened();
  }

  @Test
  void deletesPostinglessSubtreeWithNoTarget() {
    // The reported case (category-management/06): a category that has never been posted to has
    // nothing to reassign, so it deletes outright with no destination asked for.
    List<Account> subtree =
        List.of(account(FOOD_ID, "Food", null), account(MILK_ID, "Milk", FOOD_ID));
    List<Long> subtreeIds = List.of(FOOD_ID, MILK_ID);
    when(accountService.findSubtreeAccounts(FOOD_ID)).thenReturn(subtree);
    when(accountService.hasAnyPostings(subtreeIds)).thenReturn(false);

    deletionService.deleteCategory(FOOD_ID, null);

    verify(postingReassignmentRepository, never()).reassignPostings(anyList(), anyLong());
    verify(accountService).softDelete(subtreeIds);
  }

  @Test
  void rejectsMissingTargetWhenTheSubtreeCarriesPostings() {
    // Postings are never dropped: without a target there is nowhere for them to go.
    List<Account> subtree =
        List.of(account(FOOD_ID, "Food", null), account(MILK_ID, "Milk", FOOD_ID));
    when(accountService.findSubtreeAccounts(FOOD_ID)).thenReturn(subtree);
    when(accountService.hasAnyPostings(List.of(FOOD_ID, MILK_ID))).thenReturn(true);

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> deletionService.deleteCategory(FOOD_ID, null))
        .withMessageContaining("postings");

    verifyNothingHappened();
  }

  @Test
  void rejectsSubtreeRootThatIsNoLongerLive() {
    // The walk is scoped to live rows, so an empty subtree means the root is already deleted — a
    // double-submit or a back-button re-post. It must not report success having changed nothing.
    when(accountService.findSubtreeAccounts(FOOD_ID)).thenReturn(List.of());

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> deletionService.deleteCategory(FOOD_ID, null))
        .withMessageContaining("No live category");

    verifyNothingHappened();
  }

  @Test
  void rejectsUnknownTarget() {
    when(accountService.findSubtreeAccounts(FOOD_ID))
        .thenReturn(List.of(account(FOOD_ID, "Food", null)));
    when(accountService.findById(TARGET_ID)).thenReturn(Optional.empty());

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> deletionService.deleteCategory(FOOD_ID, TARGET_ID))
        .withMessageContaining("No account");

    verifyNothingHappened();
  }

  private void verifyNothingHappened() {
    verify(postingReassignmentRepository, never()).reassignPostings(anyList(), anyLong());
    verify(accountService, never()).softDelete(anyList());
    verify(currencyLeafService, never()).resolveCurrencyLeaf(anyLong(), anyString());
  }
}
