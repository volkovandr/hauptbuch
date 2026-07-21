package volkovandr.hauptbuch.operations;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.ledger.UnbalancedTransactionException;
import volkovandr.hauptbuch.web.NavItem;

/**
 * The settle-up launcher's endpoints (plan stage 8e, data-model §7): a per-person, per-currency
 * form that zeroes one debt position through a single dated transfer, committed via the existing
 * engine path (no new engine). Lives in {@code operations} beside {@link SettleUpService} for the
 * same boundary reason the entry dock's controllers do — it reaches {@code debts}, {@code
 * accounts}, and {@code ledger} at once and commits through {@link DockCommitService} (plan stage 7
 * boundary note).
 *
 * <p>Endpoints: {@link #form} renders the launcher for a person+currency (linked from the People
 * page); {@link #amountFields} recomputes the amount-field layout when the funding account changes
 * (register §3.8a's htmx recompute); {@link #settle} commits and redirects back to the People page.
 * A rejected commit re-renders the form carrying the message and the typed amounts.
 */
@Controller
class SettleUpController {

  private static final String VIEW = "settle-up";
  private static final String AMOUNTS_FRAGMENT = "settle-up :: amounts(view=${view})";
  private static final String PEOPLE_PATH = "/people";
  private static final String REDIRECT_TO_PEOPLE = "redirect:" + PEOPLE_PATH;

  private final SettleUpService settleUpService;

  SettleUpController(SettleUpService settleUpService) {
    this.settleUpService = settleUpService;
  }

  /**
   * The settle-up form for one person and currency (linked per currency line from the People page).
   */
  @GetMapping("/people/{personId}/settle")
  String form(
      @PathVariable long personId,
      @RequestParam String currency,
      @RequestParam(required = false) Long accountId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      Model model) {
    SettleUpView view = settleUpService.assemble(personId, currency, accountId, date);
    model.addAttribute("view", view);
    model.addAttribute("nav", NavItem.sectionsFor(PEOPLE_PATH));
    model.addAttribute("title", "Settle up · " + view.personName() + " · Hauptbuch");
    return VIEW;
  }

  /**
   * Recompute the amount-field layout when the funding account changes (register §3.8a): a same- vs
   * cross-currency account swaps the field count, and the debt-currency figure re-defaults into the
   * field that carries the person leg. Returns just the amounts fragment for the htmx swap.
   */
  @PostMapping("/people/{personId}/settle/amount-fields")
  String amountFields(
      @PathVariable long personId,
      @RequestParam String currency,
      @RequestParam(required = false) Long accountId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      Model model) {
    model.addAttribute("view", settleUpService.assemble(personId, currency, accountId, date));
    return AMOUNTS_FRAGMENT;
  }

  /**
   * Commit the settle — a transfer that zeroes (or reduces) the debt — then redirect to the People
   * page, where the position now reads settled or smaller. A rejected commit (a cross-currency base
   * gap, a bad amount, a missing account) re-renders the form with the message and the typed
   * amounts, exactly as the entry dock does rather than 500-ing.
   */
  @PostMapping("/people/{personId}/settle")
  String settle(
      @PathVariable long personId,
      @RequestParam String currency,
      @RequestParam(required = false) Long accountId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(required = false) String amount,
      @RequestParam(required = false) String categoryAmount,
      @RequestParam(required = false) String baseAmount,
      Model model) {
    try {
      settleUpService.settle(
          personId, currency, accountId, date, amount, categoryAmount, baseAmount);
    } catch (IllegalArgumentException | IllegalStateException | UnbalancedTransactionException e) {
      model.addAttribute(
          "view",
          settleUpService.redisplay(
              personId, currency, accountId, date, amount, categoryAmount, baseAmount));
      model.addAttribute("error", e.getMessage());
      model.addAttribute("nav", NavItem.sectionsFor(PEOPLE_PATH));
      model.addAttribute("title", "Settle up · Hauptbuch");
      return VIEW;
    }
    return REDIRECT_TO_PEOPLE;
  }
}
