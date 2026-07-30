package volkovandr.hauptbuch.ledger;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

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

  /**
   * The greeting landing, rendered inside the base layout — except a phone (a {@code Mobi}
   * User-Agent) is sent straight to the receipt capture surface, since the phone is a capture
   * device (receipt doc §4, plan stage 9b). {@code /?desktop} is the escape hatch that skips the
   * redirect.
   */
  @GetMapping("/")
  String landing(
      @RequestHeader(value = "User-Agent", required = false) String userAgent,
      @RequestParam(value = "desktop", required = false) String desktop,
      Model model) {
    if (desktop == null && userAgent != null && userAgent.contains("Mobi")) {
      return "redirect:/receipts/capture";
    }
    Settings settings = settingsService.get();
    model.addAttribute("displayName", settings.displayName());
    model.addAttribute("baseCurrencySet", settings.baseCurrency() != null);
    model.addAttribute("nav", volkovandr.hauptbuch.web.NavItem.sectionsFor("/"));
    model.addAttribute("title", "Hauptbuch");
    return "landing";
  }
}
