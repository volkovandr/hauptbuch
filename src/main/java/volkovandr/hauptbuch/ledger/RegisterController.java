package volkovandr.hauptbuch.ledger;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.web.NavItem;

/**
 * The transaction register screen (plan stage 7a) — the newest-at-bottom list of postings to the
 * viewed accounts, with per-account running balances and the date-range / account / payee filters
 * (register §2). Read-only at 7a; the entry/edit dock arrives at 7b+.
 *
 * <p>Lives in {@code ledger}, not {@code web}: the register needs only {@code ledger} + {@code
 * accounts} reads, so the feature module owns its screen (plan stage 7, boundary note) — a dock
 * controller that must also reach {@code categories}/{@code operations} lands in {@code operations}
 * at 7b to avoid a module cycle. Standard server-rendered GET; the filter is submitted as query
 * params and the same view re-renders.
 */
@Controller
class RegisterController {

  private static final String BASE_PATH = "/register";
  private static final String VIEW = "register";

  private final RegisterService registerService;

  RegisterController(RegisterService registerService) {
    this.registerService = registerService;
  }

  /**
   * The register, filtered by the query params. All are optional: no accounts means the default set
   * (every open own account), and the date range defaults to the last 12 months (register §2.3)
   * when neither bound is given.
   *
   * @param accountId the viewed accounts (repeatable); empty for the default set
   * @param fromDate inclusive lower date bound; defaults to 12 months ago when both bounds are
   *     blank
   * @param toDate inclusive upper date bound
   * @param payeeId show only this payee's rows; null for all
   */
  @GetMapping(BASE_PATH)
  String register(
      @RequestParam(name = "accountId", required = false) List<Long> accountId,
      @RequestParam(required = false) LocalDate fromDate,
      @RequestParam(required = false) LocalDate toDate,
      @RequestParam(required = false) Long payeeId,
      Model model) {
    LocalDate effectiveFrom = defaultFrom(fromDate, toDate);
    RegisterFilter filter =
        new RegisterFilter(
            accountId == null ? List.of() : accountId, effectiveFrom, toDate, payeeId);
    RegisterView register = registerService.view(filter);

    model.addAttribute("register", register);
    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("title", "Register · Hauptbuch");
    return VIEW;
  }

  /**
   * The default lower bound: last 12 months (register §2.3). Applied only when the user gave
   * neither bound — an explicit upper-only bound is honoured as an open-ended lower range.
   */
  private static LocalDate defaultFrom(LocalDate fromDate, LocalDate toDate) {
    if (fromDate != null || toDate != null) {
      return fromDate;
    }
    return LocalDate.now().minusMonths(12);
  }
}
