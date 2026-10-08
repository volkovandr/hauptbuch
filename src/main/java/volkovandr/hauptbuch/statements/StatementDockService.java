package volkovandr.hauptbuch.statements;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.Payee;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.ledger.Posting;
import volkovandr.hauptbuch.ledger.TransactionTag;
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

  private final StatementService statementService;
  private final StatementMatchService matchService;
  private final PayeeService payeeService;
  private final DockPrefillService dockPrefillService;
  private final DockCommitService dockCommitService;
  private final LedgerService ledgerService;

  StatementDockService(
      StatementService statementService,
      StatementMatchService matchService,
      PayeeService payeeService,
      DockPrefillService dockPrefillService,
      DockCommitService dockCommitService,
      LedgerService ledgerService) {
    this.statementService = statementService;
    this.matchService = matchService;
    this.payeeService = payeeService;
    this.dockPrefillService = dockPrefillService;
    this.dockCommitService = dockCommitService;
    this.ledgerService = ledgerService;
  }

  /**
   * The dock's pre-fill for a missing line: the booking date, the amount, the longest payee name
   * found in the bank's text and that payee's last category.
   *
   * @throws StatementFormatException when the line is not (or no longer) missing
   */
  public DockPrefill prefill(long statementId, long statementLineId) {
    StatementLine line = missingLine(statementId, statementLineId);
    Payee payee = payeeService.longestNameIn(bankText(line)).orElse(null);
    String payeeText =
        payee == null ? "" : payeeService.entryValueFor(payee.payeeId()).orElse(payee.name());
    GhostSuggestion category =
        payee == null ? null : dockPrefillService.lastCategoryOf(payee.payeeId()).orElse(null);
    DockInput input =
        new DockInput(
            line.bookingDate(),
            payeeText,
            category == null ? null : category.categoryId(),
            category == null ? "" : category.categoryName(),
            null,
            null,
            null,
            null,
            null,
            List.of());
    return dock(line, input, null);
  }

  /**
   * The dock as the operator left it after a refused save: the typed fields, the tag chips and the
   * reason.
   *
   * @throws StatementFormatException when the line is no longer missing
   */
  public DockPrefill reopen(long statementId, long statementLineId, DockInput input, String error) {
    return dock(missingLine(statementId, statementLineId), input, error);
  }

  private DockPrefill dock(StatementLine line, DockInput input, String error) {
    Map<Long, String> labels = ledgerService.labelsForTagIds(input.tagId());
    List<TransactionTag> tags =
        input.tagId().stream().map(id -> new TransactionTag(id, labels.get(id))).toList();
    return new DockPrefill(
        line.statementLineId(),
        StatementController.number(line.amount().abs()),
        bankText(line),
        line.bankCategory() == null ? "" : line.bankCategory(),
        input,
        tags,
        error);
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
    return DockEntry.newOnAccount(
            input.date(),
            accountId,
            input.payeeText(),
            input.categoryId() == null ? 0 : input.categoryId(),
            StatementController.number(line.amount()),
            input.note())
        .withTransfer(input.transferDirection())
        .withPerson(input.personName(), input.personDirection(), input.personRevive())
        .withTags(input.tagId());
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
