package volkovandr.hauptbuch.statements;

import java.time.LocalDate;
import java.util.function.Function;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The saves of the dock on the statement page (statements.md §6.4, plan slices d1–d2): create a
 * missing line, amend a proposed transaction to the bank's figures, and an extra's Edit, Move and
 * Void. A refused save re-renders the page with the dock still open, holding what was typed and
 * saying why beside Save; a save that succeeds redirects back to the page.
 */
@Controller
class StatementDockController {

  private static final String BASE_PATH = "/statements";
  private static final String REDIRECT_BASE = "redirect:" + BASE_PATH + "/";
  private static final String NOTICE = "notice";
  private static final String ERROR = "error";

  private final StatementDockService dockService;
  private final StatementExtraDockService extraDockService;
  private final StatementPageAssembler pageAssembler;
  private final StatementCrossCurrencyService crossCurrencyService;

  StatementDockController(
      StatementDockService dockService,
      StatementExtraDockService extraDockService,
      StatementPageAssembler pageAssembler,
      StatementCrossCurrencyService crossCurrencyService) {
    this.crossCurrencyService = crossCurrencyService;
    this.dockService = dockService;
    this.extraDockService = extraDockService;
    this.pageAssembler = pageAssembler;
  }

  /**
   * The counterpart-amount fields for a target the dock just resolved: a transfer into another
   * currency (issue statements/09) or a category on a line with a foreign charge (issue
   * statements/12); nothing otherwise.
   */
  @GetMapping(BASE_PATH + "/{id}/lines/{lineId}/cross-currency")
  String crossCurrency(
      @PathVariable long id,
      @PathVariable long lineId,
      @RequestParam(required = false) Long categoryId,
      @RequestParam(required = false) String transferDirection,
      @RequestParam(required = false) LocalDate date,
      Model model) {
    model.addAttribute(
        "cross",
        crossCurrencyService
            .forTarget(id, lineId, categoryId, transferDirection, date)
            .orElse(null));
    return "fragments/statement-dock :: crossFieldsFor(cross=${cross})";
  }

  /** Book a missing line through the dock and match it. */
  @PostMapping(BASE_PATH + "/{id}/lines/{lineId}/create")
  String createMissing(
      @PathVariable long id,
      @PathVariable long lineId,
      @ModelAttribute DockInput input,
      Model model,
      RedirectAttributes redirectAttributes) {
    return save(
        id,
        () -> dockService.createMissing(id, lineId, input),
        "Transaction created and matched.",
        reason -> dockService.reopen(id, lineId, input, reason),
        model,
        redirectAttributes);
  }

  /** Correct a proposed transaction to the line's figures and match it. */
  @PostMapping(BASE_PATH + "/{id}/lines/{lineId}/amend/{posting}")
  String amend(
      @PathVariable long id,
      @PathVariable long lineId,
      @PathVariable long posting,
      @ModelAttribute DockInput input,
      Model model,
      RedirectAttributes redirectAttributes) {
    return save(
        id,
        () -> dockService.amend(id, lineId, posting, input),
        "Transaction amended and matched.",
        reason -> dockService.reopenAmend(id, lineId, posting, input, reason),
        model,
        redirectAttributes);
  }

  /** Save the dock's edit of an extra's transaction. */
  @PostMapping(BASE_PATH + "/{id}/extras/{posting}/edit")
  String editExtra(
      @PathVariable long id,
      @PathVariable long posting,
      @ModelAttribute DockInput input,
      Model model,
      RedirectAttributes redirectAttributes) {
    return save(
        id,
        () -> extraDockService.edit(id, posting, input),
        "Transaction saved.",
        reason -> extraDockService.reopen(id, posting, input, reason),
        model,
        redirectAttributes);
  }

  /** Move an extra's transaction to another account of the same currency. */
  @PostMapping(BASE_PATH + "/{id}/extras/{posting}/move")
  String moveExtra(
      @PathVariable long id,
      @PathVariable long posting,
      @RequestParam long account,
      RedirectAttributes redirectAttributes) {
    return flash(
        id,
        () -> extraDockService.move(id, posting, account),
        "Transaction moved.",
        redirectAttributes);
  }

  /** Void an extra's transaction. */
  @PostMapping(BASE_PATH + "/{id}/extras/{posting}/void")
  String voidExtra(
      @PathVariable long id, @PathVariable long posting, RedirectAttributes redirectAttributes) {
    return flash(
        id,
        () -> extraDockService.voidExtra(id, posting),
        "Transaction voided.",
        redirectAttributes);
  }

  private String flash(
      long id, Runnable action, String done, RedirectAttributes redirectAttributes) {
    try {
      action.run();
      redirectAttributes.addFlashAttribute(NOTICE, done);
    } catch (StatementFormatException e) {
      redirectAttributes.addFlashAttribute(ERROR, e.getMessage());
    }
    return REDIRECT_BASE + id;
  }

  /**
   * Run a dock save. A refusal re-renders the page with the dock reopened from what was typed — the
   * reason is put on the dock — unless the dock itself is gone.
   */
  private String save(
      long id,
      Runnable action,
      String done,
      Function<String, DockPrefill> reopen,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      action.run();
      redirectAttributes.addFlashAttribute(NOTICE, done);
      return REDIRECT_BASE + id;
    } catch (StatementFormatException refused) {
      return reopened(id, refused.getMessage(), reopen, model, redirectAttributes);
    }
  }

  private String reopened(
      long id,
      String reason,
      Function<String, DockPrefill> reopen,
      Model model,
      RedirectAttributes redirectAttributes) {
    try {
      pageAssembler.addPage(id, null, model);
      pageAssembler.addDock(reopen.apply(reason), model);
      return "statement";
    } catch (StatementFormatException gone) {
      // The line or extra itself is no longer there: there is no dock to keep open.
      redirectAttributes.addFlashAttribute(ERROR, gone.getMessage());
      return REDIRECT_BASE + id;
    }
  }
}
