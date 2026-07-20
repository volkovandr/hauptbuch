package volkovandr.hauptbuch.debts;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.web.NavItem;

/**
 * The person-lifecycle endpoints (plan stage 8a): create, rename, and soft-delete a person, plus
 * the per-person edit page — the pure-{@code debts} operations behind the People screen. The list
 * <em>view</em> itself (GET {@code /people}, with balances) moved to {@code operations}' {@code
 * PeopleOverviewController} at stage 8d, because its base-currency total needs {@code ledger}'s
 * rates and {@code debts} may not reach {@code ledger}.
 *
 * <p>Lives in the {@code debts} module: these operations need only {@code debts} itself, so the
 * feature module owns them (CLAUDE.md §3). Standard server-rendered forms, redirect after POST; no
 * bespoke JS.
 */
@Controller
class PersonController {

  private static final String BASE_PATH = "/people";
  private static final String EDIT_VIEW = "person-edit";
  private static final String REDIRECT_TO_LIST = "redirect:" + BASE_PATH;

  private final PersonService personService;

  PersonController(PersonService personService) {
    this.personService = personService;
  }

  /** Create a new person. */
  @PostMapping(BASE_PATH)
  String createPerson(@RequestParam String name) {
    personService.create(name);
    return REDIRECT_TO_LIST;
  }

  /** The edit page for one person: rename and soft-delete. */
  @GetMapping("/people/{personId}")
  String editPerson(@PathVariable long personId, Model model) {
    Person person =
        personService
            .findById(personId)
            .orElseThrow(() -> new IllegalArgumentException("No person with id " + personId));
    model.addAttribute("person", person);
    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("title", person.name() + " · Hauptbuch");
    return EDIT_VIEW;
  }

  /** Rename the person. */
  @PostMapping("/people/{personId}")
  String renamePerson(@PathVariable long personId, @RequestParam String name) {
    personService.rename(personId, name);
    return REDIRECT_TO_LIST;
  }

  /**
   * Soft-delete the person. Refused if any of their per-currency accounts carries a non-zero
   * balance — a non-zero person is removed only by merge (stage 8f), not deletion.
   */
  @PostMapping("/people/{personId}/delete")
  String deletePerson(@PathVariable long personId) {
    personService.softDeleteIfZeroBalance(personId);
    return REDIRECT_TO_LIST;
  }
}
