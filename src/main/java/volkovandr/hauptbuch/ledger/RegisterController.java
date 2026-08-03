package volkovandr.hauptbuch.ledger;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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
  private final RegisterJumpService registerJumpService;
  private final CurrencyService currencyService;

  RegisterController(
      RegisterService registerService,
      RegisterJumpService registerJumpService,
      CurrencyService currencyService) {
    this.registerService = registerService;
    this.registerJumpService = registerJumpService;
    this.currencyService = currencyService;
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
   * @param selected jump to this transaction (register §7, plan stage 9g) — the committed receipt's
   *     "Edit transaction". The filter is then derived from the transaction and every other param
   *     is discarded, so the row is guaranteed visible; the view marks it selected and loads it
   *     into the dock
   */
  @GetMapping(BASE_PATH)
  String register(
      @RequestParam(name = "accountId", required = false) List<Long> accountId,
      @RequestParam(required = false) LocalDate fromDate,
      @RequestParam(required = false) LocalDate toDate,
      @RequestParam(required = false) Long payeeId,
      @RequestParam(required = false) Long selected,
      Model model) {
    Optional<RegisterFilter> jump = jumpFilter(selected);
    RegisterFilter filter =
        jump.orElseGet(
            () ->
                new RegisterFilter(
                    accountId == null ? List.of() : accountId,
                    defaultFrom(fromDate, toDate),
                    toDate,
                    payeeId));
    RegisterView register = registerService.view(filter);

    model.addAttribute("register", register);
    // Only a jump that actually resolved marks and docks a row. A voided or unknown id falls back
    // to the default view — and must not then dock a transaction the register cannot show.
    model.addAttribute("selectedTransactionId", jump.isPresent() ? selected : null);
    model.addAttribute("currencies", currencyService.findAll());
    model.addAttribute("amountFields", defaultAmountFields(register));
    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("title", "Register · Hauptbuch");
    return VIEW;
  }

  /** The transaction-derived filter for a {@code selected=} jump; empty when there is no jump. */
  private Optional<RegisterFilter> jumpFilter(Long selected) {
    return selected == null ? Optional.empty() : registerJumpService.filterForTransaction(selected);
  }

  /**
   * The dock's initial amount-field state (register §3.5/§3.8a): no currency override has been made
   * yet, so it is single-currency in the funding account the dock implicitly pre-selects — the
   * first of the viewed own accounts, mirroring the account {@code <select>}'s own
   * no-explicit-selection default. A book with no accounts yet has nothing to default to.
   */
  private static CrossCurrencyFields defaultAmountFields(RegisterView register) {
    return register.accounts().stream()
        .findFirst()
        .map(a -> CrossCurrencyFields.singleCurrency(a.currencyCode()))
        .orElseGet(() -> CrossCurrencyFields.singleCurrency(""));
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
