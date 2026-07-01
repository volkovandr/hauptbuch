package volkovandr.hauptbuch.ledger;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The landing page (plan stage 5) — the "Hello, %name%" greeting that reads the display name from
 * the book's {@link Settings}.
 *
 * <p>Lives in {@code ledger}, not {@code web}, because it reads settings and the shell module
 * ({@code web}) must not depend on feature modules — feature controllers depend on the shell's
 * {@code NavItem}, never the reverse (that would be a cycle {@code verify()} forbids). On a fresh
 * book the display name is unset; the greeting falls back to a neutral welcome and points at the
 * settings screen, which also carries the first-run base-currency gate.
 */
@Controller
class LandingController {

  private final SettingsService settingsService;

  LandingController(SettingsService settingsService) {
    this.settingsService = settingsService;
  }

  /** The greeting landing, rendered inside the base layout. */
  @GetMapping("/")
  String landing(Model model) {
    Settings settings = settingsService.get();
    model.addAttribute("displayName", settings.displayName());
    model.addAttribute("baseCurrencySet", settings.baseCurrency() != null);
    model.addAttribute("nav", volkovandr.hauptbuch.web.NavItem.sectionsFor("/"));
    model.addAttribute("title", "Hauptbuch");
    return "landing";
  }
}
