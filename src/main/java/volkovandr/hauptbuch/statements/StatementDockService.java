package volkovandr.hauptbuch.statements;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.Payee;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.ledger.Posting;
import volkovandr.hauptbuch.ledger.UnbalancedTransactionException;
import volkovandr.hauptbuch.operations.DockCommitService;
import volkovandr.hauptbuch.operations.DockEntry;
import volkovandr.hauptbuch.operations.DockPrefillService;
import volkovandr.hauptbuch.operations.GhostSuggestion;

/**
 * The dock embedded on the statement page (statements.md §6.4, plan slice d1): a missing line is
 * pre-filled from the line, and Save books the transaction through {@code operations} and matches
 * the new leg to the line in one database transaction — a save that fails matches nothing. The
 * bank's amount is the funding leg's amount, whatever the category's direction.
 */
@Service
public class StatementDockService {

  private static final String STALE = "That line is no longer missing. The page was reloaded.";

  private final StatementReviewService reviewService;
  private final StatementService statementService;
  private final StatementMatchService matchService;
  private final PayeeService payeeService;
  private final DockPrefillService dockPrefillService;
  private final DockCommitService dockCommitService;
  private final LedgerService ledgerService;

  StatementDockService(
      StatementReviewService reviewService,
      StatementService statementService,
      StatementMatchService matchService,
      PayeeService payeeService,
      DockPrefillService dockPrefillService,
      DockCommitService dockCommitService,
      LedgerService ledgerService) {
    this.reviewService = reviewService;
    this.statementService = statementService;
    this.matchService = matchService;
    this.payeeService = payeeService;
    this.dockPrefillService = dockPrefillService;
    this.dockCommitService = dockCommitService;
    this.ledgerService = ledgerService;
  }

  /**
   * The dock's pre-fill for a missing line: the booking date, the account, the amount, the longest
   * payee name found in the bank's text and that payee's last category.
   *
   * @throws StatementFormatException when the line is not (or no longer) missing
   */
  public DockPrefill prefill(long statementId, long statementLineId) {
    StatementLine line = missingLine(statementId, statementLineId);
    String bankText = bankText(line);
    Payee payee = payeeService.longestNameIn(bankText).orElse(null);
    String payeeText =
        payee == null ? "" : payeeService.entryValueFor(payee.payeeId()).orElse(payee.name());
    GhostSuggestion category =
        payee == null ? null : dockPrefillService.lastCategoryOf(payee.payeeId()).orElse(null);
    return new DockPrefill(
        statementLineId,
        line.bookingDate().toString(),
        statementService.accountName(statementService.get(statementId).accountId()),
        StatementController.number(line.amount()),
        bankText,
        payeeText,
        category == null ? "" : category.categoryName(),
        category == null ? null : category.categoryId(),
        line.bankCategory() == null ? "" : line.bankCategory());
  }

  /**
   * Book the transaction for a missing line and match it, reconciled, in one database transaction.
   *
   * @throws StatementFormatException when the line is no longer missing or the ledger refuses the
   *     transaction (nothing is booked or matched then)
   */
  @Transactional
  public void createMissing(long statementId, long statementLineId, DockInput input) {
    StatementLine line = missingLine(statementId, statementLineId);
    long accountId = statementService.get(statementId).accountId();
    boolean person = notBlank(input.personName()) && notBlank(input.personDirection());
    if (input.categoryId() == null && !person) {
      throw new StatementFormatException(
          "A category, transfer target, or person is required (pick, create, or resolve one).");
    }
    long transactionId;
    try {
      transactionId =
          dockCommitService.commitWithFundingAmount(entry(input, accountId, line), line.amount());
    } catch (IllegalArgumentException | IllegalStateException | UnbalancedTransactionException e) {
      throw new StatementFormatException(e.getMessage(), e);
    }
    matchService.link(statementId, statementLineId, legOn(transactionId, accountId));
  }

  private StatementLine missingLine(long statementId, long statementLineId) {
    LineReview review = matchService.lineOf(statementId, statementLineId);
    if (review.status() != LineStatus.MISSING) {
      throw new StatementFormatException(STALE);
    }
    return review.line();
  }

  private long legOn(long transactionId, long accountId) {
    List<Posting> legs =
        ledgerService.findPostings(transactionId).stream()
            .filter(p -> p.accountId() == accountId)
            .toList();
    if (legs.size() != 1) {
      throw new StatementFormatException("The booked transaction has no single leg to match.");
    }
    return legs.get(0).postingId();
  }

  private static DockEntry entry(DockInput input, long accountId, StatementLine line) {
    return new DockEntry(
        null,
        input.date(),
        accountId,
        null,
        null,
        null,
        null,
        input.payeeText(),
        input.categoryId() == null ? 0 : input.categoryId(),
        null,
        StatementController.number(line.amount()),
        null,
        null,
        input.note(),
        input.transferDirection(),
        input.personName(),
        input.personDirection(),
        input.personRevive(),
        List.of());
  }

  private static String bankText(StatementLine line) {
    return ((line.counterparty() == null ? "" : line.counterparty())
            + " "
            + (line.description() == null ? "" : line.description()))
        .strip();
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }
}
