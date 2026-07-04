package volkovandr.hauptbuch.operations;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.ledger.Currency;
import volkovandr.hauptbuch.ledger.CurrencyService;

/**
 * The htmx endpoints behind the shared currency-picker fragment (plan stage 6d). Lives in {@code
 * operations}, the home of structural domain operations called by both the UI and the MCP server
 * (CLAUDE.md §3): adding a currency is the {@link CurrencyProvisioningService#createCurrency}
 * operation, and this controller composes it with {@code ledger}'s currency read to re-render the
 * picker. (It cannot live in {@code ledger}: {@code ledger → operations} would close a module cycle
 * with {@code operations → ledger}.) The picker <em>template</em> still lives with the currency in
 * {@code ledger}'s template dir — templates are module-agnostic resources.
 *
 * <p>No bespoke JS (CLAUDE.md §1.6): the button hx-gets the {@code dialog} fragment; the form
 * hx-posts here, and on success this returns the {@code created} fragment — an out-of-band swap of
 * the picker (new currency pre-selected) whose empty target content dismisses the dialog. On a
 * validation error it re-renders the dialog with the message. The picker's field id and name ride
 * through every request so the response re-renders the right picker.
 */
@Controller
class CurrencyController {

  private static final String FRAGMENTS = "fragments/currency-picker";

  private final CurrencyProvisioningService currencyProvisioningService;
  private final CurrencyService currencyService;

  CurrencyController(
      CurrencyProvisioningService currencyProvisioningService, CurrencyService currencyService) {
    this.currencyProvisioningService = currencyProvisioningService;
    this.currencyService = currencyService;
  }

  /** Open the add-currency dialog for a given picker. */
  @GetMapping("/currencies/new")
  String openDialog(@RequestParam String fieldId, @RequestParam String fieldName, Model model) {
    model.addAttribute("fieldId", fieldId);
    model.addAttribute("fieldName", fieldName);
    return FRAGMENTS + " :: dialog(fieldId=${fieldId}, fieldName=${fieldName})";
  }

  /** Dismiss the dialog without adding — clears the dialog mount. */
  @GetMapping("/currencies/new/cancel")
  String cancelDialog(@RequestParam String fieldId, Model model) {
    model.addAttribute("fieldId", fieldId);
    return FRAGMENTS + " :: dialogMount(fieldId=${fieldId})";
  }

  /**
   * Add a currency and provision its system leaves, then re-render the picker with the new currency
   * pre-selected (the {@code created} fragment) — the OOB picker swap plus an empty dialog target,
   * so the dialog dismisses. On a validation error, re-render the dialog carrying the message.
   */
  @PostMapping("/currencies")
  String addCurrency(
      @RequestParam String fieldId,
      @RequestParam String fieldName,
      @RequestParam String code,
      @RequestParam String name,
      @RequestParam(required = false) String symbol,
      @RequestParam int minorUnits,
      Model model) {
    model.addAttribute("fieldId", fieldId);
    model.addAttribute("fieldName", fieldName);
    try {
      Currency created = currencyProvisioningService.createCurrency(code, minorUnits, symbol, name);
      model.addAttribute("currencies", currencyService.findAll());
      model.addAttribute("selected", created.code());
      return FRAGMENTS
          + " :: created(fieldId=${fieldId}, fieldName=${fieldName}, selected=${selected})";
    } catch (IllegalArgumentException e) {
      model.addAttribute("currencies", currencyService.findAll());
      model.addAttribute("error", e.getMessage());
      return FRAGMENTS + " :: dialog(fieldId=${fieldId}, fieldName=${fieldName})";
    }
  }
}
