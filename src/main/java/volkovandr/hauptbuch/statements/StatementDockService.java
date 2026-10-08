package volkovandr.hauptbuch.statements;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.Payee;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.ledger.Posting;
import volkovandr.hauptbuch.ledger.UnbalancedTransactionException;
import volkovandr.hauptbuch.operations.DockCommitService;
import volkovandr.hauptbuch.operations.DockEditModel;
import volkovandr.hauptbuch.operations.DockEditService;
import volkovandr.hauptbuch.operations.DockEntry;
import volkovandr.hauptbuch.operations.DockPrefillService;
import volkovandr.hauptbuch.operations.GhostSuggestion;
import volkovandr.hauptbuch.statements.DockPrefill.Kind;
import volkovandr.hauptbuch.statements.ProposedCandidate.Tier;

/**
 * The dock embedded on the statement page for a line (statements.md §6.4, plan slices d1–d2). A
 * missing line is pre-filled from the line and Save books a new transaction; a line whose proposal
 * is an amount-differs or wrong-account candidate opens that transaction, and Save amends it to the
 * bank's figures (and the statement's account). Either way {@code operations} commits and the leg
 * is matched in one database transaction — a save that fails matches nothing. The bank's amount is
 * the funding leg's amount, whatever the category's direction.
 */
@Service
public class StatementDockService {

  private static final String STALE = "That line is no longer missing. The page was reloaded.";
  private static final String NO_PROPOSAL =
      "That proposal is no longer available. The page was reloaded.";
  private static final String NOT_ON_ACCOUNT =
      "The statement's account is the other end of that transfer. Amend it in the register.";

  private final StatementService statementService;
  private final StatementMatchService matchService;
  private final PayeeService payeeService;
  private final DockPrefillService dockPrefillService;
  private final DockCommitService dockCommitService;
  private final DockEditService dockEditService;
  private final LedgerService ledgerService;

  StatementDockService(
      StatementService statementService,
      StatementMatchService matchService,
      PayeeService payeeService,
      DockPrefillService dockPrefillService,
      DockCommitService dockCommitService,
      DockEditService dockEditService,
      LedgerService ledgerService) {
    this.statementService = statementService;
    this.matchService = matchService;
    this.payeeService = payeeService;
    this.dockPrefillService = dockPrefillService;
    this.dockCommitService = dockCommitService;
    this.dockEditService = dockEditService;
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
    Payee payee = payeeService.longestNameIn(DockForms.bankText(line)).orElse(null);
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
            List.of(),
            null,
            null,
            null,
            null);
    return dock(line, Kind.CREATE, 0, "", input, null);
  }

  /**
   * The dock as the operator left it after a refused save: the typed fields, the tag chips and the
   * reason.
   *
   * @throws StatementFormatException when the line is no longer missing
   */
  public DockPrefill reopen(long statementId, long statementLineId, DockInput input, String error) {
    return dock(missingLine(statementId, statementLineId), Kind.CREATE, 0, "", input, error);
  }

  /**
   * The dock for correcting a proposed transaction to the line: the booked transaction's own
   * fields, and a notice saying what Save will change.
   *
   * @throws StatementFormatException when the posting is no longer an amount-differs or
   *     wrong-account proposal for the line, or its transaction cannot be edited in the dock
   */
  public DockPrefill prefillAmend(long statementId, long statementLineId, long postingId) {
    LineReview review = matchService.lineOf(statementId, statementLineId);
    ProposedCandidate proposed = amendable(review, postingId);
    DockEditModel model = amendableModel(proposed.candidate());
    String notice = notice(statementId, review.line(), proposed);
    DockInput input = DockForms.inputOf(model);
    if (DockForms.notBlank(input.baseAmount())
        && proposed.candidate().amount().abs().compareTo(review.line().amount().abs()) != 0) {
      // The frozen base amount belongs to the old figure; the operator confirms it for the new one.
      input = input.withBaseAmount("");
      notice += " Enter the base amount for the bank's figure.";
    }
    return dock(review.line(), Kind.AMEND, postingId, notice, input, null);
  }

  /** The amend dock as the operator left it after a refused save. */
  public DockPrefill reopenAmend(
      long statementId, long statementLineId, long postingId, DockInput input, String error) {
    LineReview review = matchService.lineOf(statementId, statementLineId);
    ProposedCandidate proposed = amendable(review, postingId);
    String notice = notice(statementId, review.line(), proposed);
    return dock(review.line(), Kind.AMEND, postingId, notice, input, error);
  }

  private DockPrefill dock(
      StatementLine line, Kind kind, long postingId, String notice, DockInput input, String error) {
    Map<Long, String> labels = ledgerService.labelsForTagIds(input.tagId());
    return new DockPrefill(
        kind,
        line.statementLineId(),
        postingId,
        StatementController.number(line.amount().abs()),
        DockForms.bankText(line),
        DockForms.orEmpty(line.bankCategory()),
        notice,
        input,
        DockForms.chips(input.tagId(), labels),
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
    requireCounterpart(input);
    long transactionId =
        commit(
            DockEntry.newOnAccount(
                    input.date(),
                    accountId,
                    input.payeeText(),
                    input.categoryId() == null ? 0 : input.categoryId(),
                    StatementController.number(line.amount()),
                    input.note())
                .withTransfer(input.transferDirection())
                .withPerson(input.personName(), input.personDirection(), input.personRevive())
                .withTags(input.tagId()),
            line);
    matchService.link(statementId, statementLineId, legOn(transactionId, accountId));
  }

  /**
   * Correct the proposed transaction to the bank's amount on the statement's account and match it,
   * reconciled, in one database transaction. A cross-currency transaction keeps its counterpart
   * amount, so its funding leg's real rate follows from the bank's figure (statements.md §6.4).
   *
   * @throws StatementFormatException when the proposal is gone, or the ledger refuses the edit
   *     (nothing is changed or matched then)
   */
  @Transactional
  public void amend(long statementId, long statementLineId, long postingId, DockInput input) {
    LineReview review = matchService.lineOf(statementId, statementLineId);
    ProposedCandidate proposed = amendable(review, postingId);
    amendableModel(proposed.candidate());
    long accountId = statementService.get(statementId).accountId();
    requireCounterpart(input);
    long transactionId = proposed.candidate().transactionId();
    commit(
        DockForms.editEntry(
            input, transactionId, accountId, StatementController.number(review.line().amount())),
        review.line());
    matchService.link(statementId, statementLineId, legOn(transactionId, accountId));
  }

  private long commit(DockEntry entry, StatementLine line) {
    try {
      return dockCommitService.commitWithFundingAmount(entry, line.amount());
    } catch (IllegalArgumentException | IllegalStateException | UnbalancedTransactionException e) {
      throw new StatementFormatException(e.getMessage(), e);
    }
  }

  private static void requireCounterpart(DockInput input) {
    if (DockForms.noCounterpart(input)) {
      throw new StatementFormatException(
          "A category, transfer target, or person is required (pick, create, or resolve one).");
    }
  }

  private StatementLine missingLine(long statementId, long statementLineId) {
    LineReview review = matchService.lineOf(statementId, statementLineId);
    if (review.status() != LineStatus.MISSING) {
      throw new StatementFormatException(STALE);
    }
    return review.line();
  }

  /** The line's non-exact proposal for this posting; exact ones are accepted, not amended. */
  private static ProposedCandidate amendable(LineReview review, long postingId) {
    return review.candidates().stream()
        .filter(
            p ->
                review.status() != LineStatus.MATCHED
                    && p.tier() != Tier.EXACT
                    && p.candidate().postingId() == postingId)
        .findFirst()
        .orElseThrow(() -> new StatementFormatException(NO_PROPOSAL));
  }

  /** The booked transaction as the dock edits it; its funding leg must be the candidate's. */
  private DockEditModel amendableModel(StatementCandidate candidate) {
    DockEditModel model;
    try {
      model = dockEditService.load(candidate.transactionId());
    } catch (IllegalArgumentException e) {
      throw new StatementFormatException(e.getMessage(), e);
    }
    if (model.accountId() != candidate.accountId()) {
      throw new StatementFormatException(NOT_ON_ACCOUNT);
    }
    return model;
  }

  private String notice(long statementId, StatementLine line, ProposedCandidate proposed) {
    StatementCandidate c = proposed.candidate();
    String text;
    if (proposed.tier() == Tier.WRONG_ACCOUNT) {
      text =
          "Booked on "
              + c.accountName()
              + "; saving moves it to "
              + statementService.accountName(statementService.get(statementId).accountId())
              + ".";
    } else {
      text =
          "Booked as "
              + StatementController.number(c.amount())
              + "; saving sets it to the bank's "
              + StatementController.number(line.amount())
              + ".";
    }
    return c.matchedElsewhere()
        ? text + " It is matched on another statement, which the change removes."
        : text;
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
}
