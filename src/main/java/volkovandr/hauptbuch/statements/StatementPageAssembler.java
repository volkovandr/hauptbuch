package volkovandr.hauptbuch.statements;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import volkovandr.hauptbuch.ledger.RegisterService;
import volkovandr.hauptbuch.web.NavItem;

/**
 * Puts the statement page's model together — the page itself, and the dock when one is open — for
 * the two controllers that render it: the page's own and the dock's, which re-renders the page with
 * the dock still open when a save is refused. The session is the injected request-scoped proxy, so
 * the remembered matching-table view is available wherever the page is rendered.
 */
@Component
class StatementPageAssembler {

  private static final String BASE_PATH = "/statements";
  private static final String SHOW_ATTENTION = "attention";
  private static final String SHOW_ALL = "all";
  private static final String SHOW_SESSION_KEY = "statementLinesShow";

  private final StatementService statementService;
  private final StatementProfileService profileService;
  private final StatementReviewService reviewService;
  private final StatementDockService dockService;
  private final StatementExtraDockService extraDockService;
  private final RegisterService registerService;
  private final HttpSession session;

  StatementPageAssembler(
      StatementService statementService,
      StatementProfileService profileService,
      StatementReviewService reviewService,
      StatementDockService dockService,
      StatementExtraDockService extraDockService,
      RegisterService registerService,
      HttpSession session) {
    this.statementService = statementService;
    this.profileService = profileService;
    this.reviewService = reviewService;
    this.dockService = dockService;
    this.extraDockService = extraDockService;
    this.registerService = registerService;
    this.session = session;
  }

  /**
   * Everything the statement page shows except the dock. {@code show} ({@code all} or {@code
   * attention}) picks the matching table's view and is remembered in the session; absent, the
   * remembered one applies.
   *
   * @throws StatementFormatException when the statement no longer exists
   */
  void addPage(long id, String show, Model model) {
    if (SHOW_ALL.equals(show) || SHOW_ATTENTION.equals(show)) {
      session.setAttribute(SHOW_SESSION_KEY, show);
    }
    model.addAttribute(
        "showAttentionOnly", SHOW_ATTENTION.equals(session.getAttribute(SHOW_SESSION_KEY)));
    Statement statement = statementService.get(id);
    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("statement", statement);
    model.addAttribute("accountName", statementService.accountName(statement.accountId()));
    model.addAttribute("profileName", profileService.nameOf(statement.statementProfileId()));
    model.addAttribute("opening", StatementController.number(statement.openingBalance()));
    model.addAttribute("closing", StatementController.number(statement.closingBalance()));
    model.addAttribute(
        "lines",
        statementService.lines(id).stream().map(StatementController.LineView::of).toList());
    StatementReview review = reviewService.review(id);
    model.addAttribute("review", review);
    List<StatementReviewViews.ReviewLineView> reviewLines =
        review.lines().stream().map(StatementReviewViews.ReviewLineView::of).toList();
    model.addAttribute("reviewLines", reviewLines);
    model.addAttribute(
        "attentionCount",
        reviewLines.stream().filter(StatementReviewViews.ReviewLineView::needsAttention).count());
    model.addAttribute(
        "reviewExtras", review.extras().stream().map(StatementReviewViews.ExtraView::of).toList());
    model.addAttribute("moveAccounts", statementService.statementAccounts());
  }

  /**
   * Open the dock the request asks for, or say why it cannot be (the line was matched meanwhile).
   * An {@code extra} posting opens that extra; a line with a {@code posting} opens that proposal
   * for amending; a line alone opens it for creating; nothing opens no dock.
   */
  void addDock(long id, Long lineId, Long postingId, Long extraPostingId, Model model) {
    try {
      DockPrefill dock;
      if (extraPostingId != null) {
        dock = extraDockService.prefill(id, extraPostingId);
      } else if (lineId != null && postingId != null) {
        dock = dockService.prefillAmend(id, lineId, postingId);
      } else if (lineId != null) {
        dock = dockService.prefill(id, lineId);
      } else {
        return;
      }
      addDock(dock, model);
    } catch (RegisterOnlyException e) {
      refuse(e, lineId, extraPostingId, model);
      model.addAttribute("refusalTransactionId", e.transactionId());
    } catch (StatementFormatException e) {
      refuse(e, lineId, extraPostingId, model);
    }
  }

  /** Show this dock, with the lists its pickers offer. */
  void addDock(DockPrefill dock, Model model) {
    model.addAttribute("dock", dock);
    model.addAttribute("keptLine", dock.lineId());
    model.addAttribute("lists", registerService.datalists());
  }

  /** Shown on the row the operator clicked: the top of the page may be scrolled out of view. */
  private static void refuse(
      StatementFormatException e, Long lineId, Long extraPostingId, Model model) {
    model.addAttribute("refusal", e.getMessage());
    model.addAttribute("refusedLine", lineId);
    model.addAttribute("keptLine", lineId);
    model.addAttribute("refusedExtra", extraPostingId);
  }
}
