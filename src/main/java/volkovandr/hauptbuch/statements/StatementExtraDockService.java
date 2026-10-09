package volkovandr.hauptbuch.statements;

import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.UnbalancedTransactionException;
import volkovandr.hauptbuch.operations.DockCommitService;
import volkovandr.hauptbuch.operations.DockEditModel;
import volkovandr.hauptbuch.operations.DockEditService;
import volkovandr.hauptbuch.operations.DockEntry;
import volkovandr.hauptbuch.statements.DockPrefill.Kind;

/**
 * Edit, Move to account and Void for the extras of a statement (statements.md §6.3–6.4, plan slice
 * d2) — postings on the statement's account that the statement does not account for. They go
 * through the same dock and commit path as the register, and nothing is matched: an extra stays an
 * extra until the operator makes it something else. Each action is checked against the live review,
 * never against what the page showed.
 */
@Service
public class StatementExtraDockService {

  private static final String STALE = "That extra is no longer open. The page was reloaded.";
  private static final String CROSS_CURRENCY =
      "A cross-currency transaction cannot be edited here.";
  private static final String OTHER_END =
      "The statement's account is the other end of that transfer.";

  private static final String NOT_SIMPLE =
      "Only a simple two-leg transaction can be changed here — this one is a split or otherwise"
          + " not editable in the dock.";

  private final StatementService statementService;
  private final StatementReviewService reviewService;
  private final AccountService accountService;
  private final DockEditService dockEditService;
  private final DockCommitService dockCommitService;
  private final LedgerService ledgerService;

  StatementExtraDockService(
      StatementService statementService,
      StatementReviewService reviewService,
      AccountService accountService,
      DockEditService dockEditService,
      DockCommitService dockCommitService,
      LedgerService ledgerService) {
    this.statementService = statementService;
    this.reviewService = reviewService;
    this.accountService = accountService;
    this.dockEditService = dockEditService;
    this.dockCommitService = dockCommitService;
    this.ledgerService = ledgerService;
  }

  /**
   * The dock for an extra, filled from its transaction.
   *
   * @throws StatementFormatException when the extra is gone or its transaction cannot be edited
   *     here
   */
  public DockPrefill prefill(long statementId, long postingId) {
    DockEditModel model = editableModel(statementId, extraOf(statementId, postingId));
    return dock(postingId, DockForms.inputOf(model), null);
  }

  /** The extra's dock as the operator left it after a refused save. */
  public DockPrefill reopen(long statementId, long postingId, DockInput input, String error) {
    extraOf(statementId, postingId);
    return dock(postingId, input, error);
  }

  private DockPrefill dock(long postingId, DockInput input, String error) {
    Map<Long, String> labels = ledgerService.labelsForTagIds(input.tagId());
    return new DockPrefill(
        Kind.EXTRA,
        0,
        postingId,
        DockForms.orEmpty(input.amount()),
        "",
        "",
        "",
        input,
        DockForms.chips(input.tagId(), labels),
        error);
  }

  /**
   * Save the dock's edit of the extra's transaction.
   *
   * @throws StatementFormatException when the extra is gone or the ledger refuses the edit
   */
  @Transactional
  public void edit(long statementId, long postingId, DockInput input) {
    StatementExtra extra = extraOf(statementId, postingId);
    DockEditModel model = editableModel(statementId, extra);
    if (DockForms.noCounterpart(input)) {
      throw new StatementFormatException(
          "A category, transfer target, or person is required (pick, create, or resolve one).");
    }
    commit(DockForms.editEntry(input, extra.transactionId(), model.accountId(), input.amount()));
  }

  /**
   * Move the extra's transaction to another open own account of the same currency, keeping
   * everything else.
   *
   * @throws StatementFormatException when the extra is gone, the target is not such an account, or
   *     the ledger refuses the move
   */
  @Transactional
  public void move(long statementId, long postingId, long targetAccountId) {
    StatementExtra extra = extraOf(statementId, postingId);
    DockEditModel model = editableModel(statementId, extra);
    Account source = accountService.findById(model.accountId()).orElseThrow();
    Account target =
        statementService.statementAccounts().stream()
            .filter(a -> a.accountId() == targetAccountId)
            .findFirst()
            .orElseThrow(
                () -> new StatementFormatException("Choose an open asset or liability account."));
    if (target.accountId().equals(source.accountId())) {
      throw new StatementFormatException("It is on that account already.");
    }
    if (!target.currencyCode().equals(source.currencyCode())) {
      throw new StatementFormatException(
          "Move it to an account in " + source.currencyCode() + ", or edit it in the register.");
    }
    commit(
        DockForms.editEntry(
            DockForms.inputOf(model), extra.transactionId(), target.accountId(), model.amount()));
  }

  /**
   * Void the extra's transaction — a reversible soft-delete.
   *
   * @throws StatementFormatException when the extra is gone
   */
  @Transactional
  public void voidExtra(long statementId, long postingId) {
    StatementExtra extra = extraOf(statementId, postingId);
    try {
      dockCommitService.voidTransaction(extra.transactionId());
    } catch (IllegalArgumentException e) {
      throw new StatementFormatException(e.getMessage(), e);
    }
  }

  private void commit(DockEntry entry) {
    try {
      dockCommitService.commit(entry);
    } catch (IllegalArgumentException | IllegalStateException | UnbalancedTransactionException e) {
      throw new StatementFormatException(e.getMessage(), e);
    }
  }

  private StatementExtra extraOf(long statementId, long postingId) {
    return reviewService.review(statementId).extras().stream()
        .map(ExtraReview::extra)
        .filter(e -> e.postingId() == postingId)
        .findFirst()
        .orElseThrow(() -> new StatementFormatException(STALE));
  }

  /** The extra's transaction as the dock edits it: single-currency, funded from the statement. */
  private DockEditModel editableModel(long statementId, StatementExtra extra) {
    DockEditModel model;
    try {
      model = dockEditService.load(extra.transactionId());
    } catch (IllegalArgumentException e) {
      throw new RegisterOnlyException(NOT_SIMPLE, extra.transactionId(), e);
    }
    if (model.categoryAmount() != null) {
      throw new RegisterOnlyException(CROSS_CURRENCY, extra.transactionId());
    }
    if (model.accountId() != statementService.get(statementId).accountId()) {
      throw new RegisterOnlyException(OTHER_END, extra.transactionId());
    }
    return model;
  }
}
