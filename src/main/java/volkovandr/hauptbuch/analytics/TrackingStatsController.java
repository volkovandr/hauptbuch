package volkovandr.hauptbuch.analytics;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the landing-page tracking-stats line (CONTEXT.md "Tracking stats") as an htmx fragment.
 * {@code landing.html} lazy-loads it with {@code hx-get} on load; a book with no live transactions
 * yields an empty body (no {@code stats} attribute, the fragment renders nothing).
 */
@Controller
class TrackingStatsController {

  private static final String FRAGMENT = "fragments/tracking-stats :: stats";

  private final TrackingStatsService trackingStatsService;

  TrackingStatsController(TrackingStatsService trackingStatsService) {
    this.trackingStatsService = trackingStatsService;
  }

  /** The tracking-stats line, or an empty body when there is nothing to summarise. */
  @GetMapping("/overview/tracking-stats")
  String trackingStats(Model model) {
    trackingStatsService.current().ifPresent(stats -> model.addAttribute("stats", stats));
    return FRAGMENT;
  }
}
