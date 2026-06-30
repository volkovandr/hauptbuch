package volkovandr.hauptbuch.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the UI shell and the stage-4 demo page (plan stage 4).
 *
 * <p>The demo page proves the scaffold works end to end: it renders inside the base layout, its
 * htmx fragment swap returns a server-rendered fragment, and the keyboard leaf can move focus over
 * its rows. It is a throwaway demonstration surface, not a product screen — real screens arrive
 * with their stages and live in their own feature modules.
 */
@Controller
class ShellController {

  /** The stage-4 demo page, rendered inside the base layout. */
  @GetMapping("/")
  String home(Model model) {
    model.addAttribute("nav", NavItem.sectionsFor("/"));
    model.addAttribute("title", "Hauptbuch");
    return "home";
  }

  /**
   * htmx fragment endpoint backing the demo page's swap. Returns only the fragment markup (no
   * layout), proving {@code hx-get} → fragment swap works.
   */
  @GetMapping("/demo/fragment")
  String demoFragment(Model model) {
    model.addAttribute("swappedAt", java.time.LocalTime.now().withNano(0).toString());
    return "fragments/demo :: swapped";
  }
}
