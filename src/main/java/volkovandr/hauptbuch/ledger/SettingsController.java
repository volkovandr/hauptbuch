package volkovandr.hauptbuch.ledger;

import java.math.BigDecimal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * The settings screen (plan stage 5) — the smallest real screen and the first-run base-currency
 * gate.
 *
 * <p>Lives in the {@code ledger} module, not {@code web}: feature screens' controllers belong to
 * their feature module, and settings is {@link SettingsService}'s screen (CLAUDE.md §3, web
 * package-info). It presents the two settings the model exposes: the write-once base currency —
 * chosen from the seeded currencies on a fresh book, then shown read-only once locked — and the
 * freely-editable display name backing the "Hello, %name%" greeting.
 *
 * <p>The write-once guard is upheld by {@link SettingsService#setBaseCurrency}; this controller
 * only offers the choice while the base currency is unset, so the locked value can never be posted
 * back.
 */
@Controller
class SettingsController {

  private static final String VIEW = "settings";

  private final SettingsService settingsService;

  SettingsController(SettingsService settingsService) {
    this.settingsService = settingsService;
  }

  /** The settings screen: base-currency gate (or the locked value) and the display-name field. */
  @GetMapping("/settings")
  String settings(Model model) {
    populate(model);
    return VIEW;
  }

  /**
   * Lock the base currency on first run. The write-once guard in {@link SettingsService} rejects an
   * attempt to change a locked currency; re-rendering the (now read-only) screen afterwards makes
   * such an attempt a no-op the user simply cannot make through the UI.
   */
  @PostMapping("/settings/base-currency")
  String lockBaseCurrency(@RequestParam String baseCurrency, Model model) {
    settingsService.setBaseCurrency(baseCurrency);
    populate(model);
    return VIEW;
  }

  /** Save the freely-editable display name backing the greeting. */
  @PostMapping("/settings/display-name")
  String saveDisplayName(@RequestParam(required = false) String displayName, Model model) {
    settingsService.setDisplayName(displayName);
    populate(model);
    return VIEW;
  }

  /** Save the AI model id (data-model §3.8) — the model the receipt parser calls. */
  @PostMapping("/settings/ai-model")
  String saveAiModel(@RequestParam(required = false) String aiModel, Model model) {
    settingsService.setAiModel(aiModel);
    populate(model);
    return VIEW;
  }

  /**
   * Save (or clear) the DB-stored API key — write-only (data-model §3.8): a blank value with {@code
   * clear} unset leaves the stored key untouched, so re-saving the page never wipes it.
   */
  @PostMapping("/settings/ai-key")
  String saveAiKey(
      @RequestParam(required = false) String aiApiKey,
      @RequestParam(required = false, defaultValue = "false") boolean clear,
      Model model) {
    settingsService.setAiApiKey(aiApiKey, clear);
    populate(model);
    return VIEW;
  }

  /**
   * Save the four per-million-token USD price rates a parse's frozen cost is computed from. The
   * fields are plain text parsed leniently through {@link MoneyFormat#parse} — the same
   * both-separators tolerance the register's amount inputs have (owner feedback 2026-08-02), so
   * {@code 2,00} and {@code 2.00} both work rather than a comma throwing a 400. A blank or
   * unparseable field clears that rate.
   */
  @PostMapping("/settings/ai-prices")
  String saveAiPrices(
      @RequestParam(required = false) String priceIn,
      @RequestParam(required = false) String priceOut,
      @RequestParam(required = false) String priceCacheWrite,
      @RequestParam(required = false) String priceCacheRead,
      Model model) {
    settingsService.setAiPrices(
        price(priceIn), price(priceOut), price(priceCacheWrite), price(priceCacheRead));
    populate(model);
    return VIEW;
  }

  /** Parse a price field leniently (blank or unparseable ⇒ null, which clears the stored rate). */
  private static BigDecimal price(String input) {
    if (input == null || input.isBlank()) {
      return null;
    }
    try {
      return MoneyFormat.parse(input);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private void populate(Model model) {
    Settings settings = settingsService.get();
    model.addAttribute("settings", settings);
    model.addAttribute("baseCurrencyLocked", settings.baseCurrency() != null);
    model.addAttribute("currencies", settingsService.availableCurrencies());
    model.addAttribute("ai", settingsService.aiSettingsView());
    model.addAttribute("nav", volkovandr.hauptbuch.web.NavItem.sectionsFor("/settings"));
    model.addAttribute("title", "Settings · Hauptbuch");
  }
}
