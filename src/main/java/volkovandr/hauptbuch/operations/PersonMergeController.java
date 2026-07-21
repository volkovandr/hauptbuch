package volkovandr.hauptbuch.operations;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.web.NavItem;

/**
 * The person-merge endpoints (plan stage 8f, data-model §7): pick another live person to fold this
 * one into, then execute the merge — the only way to remove a person who is owed or owes money. It
 * lives in {@code operations} beside {@link PersonMergeService} because the merge is a structural
 * reassignment of postings (CLAUDE.md §3), reaching {@code debts} provisioning and the {@code
 * posting} table at once; the pure-{@code debts} lifecycle (create, rename, soft-delete) stays on
 * {@code debts}' own {@code PersonController}, which links here from its "remove" section.
 *
 * <p>Standard server-rendered form, redirect after POST; no bespoke JS. A rejected merge (into
 * themselves, or a since-removed target) re-renders the form with the message rather than 500-ing,
 * exactly as the settle-up and entry-dock forms do.
 */
@Controller
class PersonMergeController {

  private static final String VIEW = "merge";
  private static final String PEOPLE_PATH = "/people";
  private static final String REDIRECT_TO_PEOPLE = "redirect:" + PEOPLE_PATH;

  private final PersonMergeService personMergeService;

  PersonMergeController(PersonMergeService personMergeService) {
    this.personMergeService = personMergeService;
  }

  /** The merge form for one source person: their positions and the targets to fold them into. */
  @GetMapping("/people/{personId}/merge")
  String form(@PathVariable long personId, Model model) {
    PersonMergeView view = personMergeService.assemble(personId);
    model.addAttribute("view", view);
    model.addAttribute("nav", NavItem.sectionsFor(PEOPLE_PATH));
    model.addAttribute("title", "Merge " + view.personName() + " · Hauptbuch");
    return VIEW;
  }

  /**
   * Execute the merge, then redirect to the People page, where the source is gone and the target
   * carries the folded position. A rejected merge re-renders the form with the message.
   */
  @PostMapping("/people/{personId}/merge")
  String merge(@PathVariable long personId, @RequestParam long targetPersonId, Model model) {
    try {
      personMergeService.merge(personId, targetPersonId);
    } catch (IllegalArgumentException | IllegalStateException e) {
      model.addAttribute("view", personMergeService.assemble(personId));
      model.addAttribute("error", e.getMessage());
      model.addAttribute("nav", NavItem.sectionsFor(PEOPLE_PATH));
      model.addAttribute("title", "Merge · Hauptbuch");
      return VIEW;
    }
    return REDIRECT_TO_PEOPLE;
  }
}
