package volkovandr.hauptbuch.operations;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import volkovandr.hauptbuch.web.NavItem;

/**
 * The People-balances screen (plan stage 8d): the roster of live persons with their per-currency
 * debt positions and a supplementary base-currency total, plus a "view in register" link per
 * person. Read-only; the create form and per-person lifecycle (rename, soft-delete) POST to {@code
 * debts}' own {@code PersonController}, which owns those pure-{@code debts} operations.
 *
 * <p>The list <em>view</em> lives in {@code operations} rather than {@code debts} because its base
 * total is a mark-to-market valuation needing {@code ledger}'s rates, and {@code debts} may not
 * reach {@code ledger} (the module edge already runs {@code ledger} → {@code debts}). This mirrors
 * the register split: the plain read stays in the feature module, the cross-module read moves here
 * (plan stage 7 boundary note).
 */
@Controller
class PeopleOverviewController {

  private static final String BASE_PATH = "/people";
  private static final String VIEW = "people";

  private final PeopleOverviewService peopleOverviewService;

  PeopleOverviewController(PeopleOverviewService peopleOverviewService) {
    this.peopleOverviewService = peopleOverviewService;
  }

  /** The people list with balances plus the create-person form. */
  @GetMapping(BASE_PATH)
  String people(Model model) {
    model.addAttribute("overview", peopleOverviewService.overview());
    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("title", "People · Hauptbuch");
    return VIEW;
  }
}
