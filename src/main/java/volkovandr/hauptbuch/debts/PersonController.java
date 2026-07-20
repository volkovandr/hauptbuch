package volkovandr.hauptbuch.debts;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.web.NavItem;

/**
 * The people screen (plan stage 8a): the roster of live persons the {@code debts} module tracks —
 * list, create, rename, soft-delete, names only. Per-person balances, entry (the {@code for}/{@code
 * by} counterpart shortcuts), and merge ride later sub-stages (8b–8f); this screen only manages the
 * roster.
 *
 * <p>Lives in the {@code debts} module, not {@code web}: feature screens' controllers belong to
 * their feature module (CLAUDE.md §3). Standard server-rendered forms, redirect after POST; no
 * bespoke JS.
 */
@Controller
class PersonController {

  private static final String BASE_PATH = "/people";
  private static final String LIST_VIEW = "people";
  private static final String EDIT_VIEW = "person-edit";
  private static final String REDIRECT_TO_LIST = "redirect:" + BASE_PATH;

  /**
   * htmx response header the person-account resolver raises on a successful resolve (register §3.3,
   * plan stage 8b) — mirrors {@code categories}' {@code counterpart-resolved} trigger so the dock
   * recomputes its amount fields against the newly-resolved funding account.
   */
  private static final String TRIGGER_AFTER_SWAP = "HX-Trigger-After-Swap";

  private static final String PERSON_ACCOUNT_RESOLVED = "person-account-resolved";

  private final PersonService personService;
  private final PersonAccountResolutionService personAccountResolutionService;

  PersonController(
      PersonService personService, PersonAccountResolutionService personAccountResolutionService) {
    this.personService = personService;
    this.personAccountResolutionService = personAccountResolutionService;
  }

  /** The people list plus the create-person form. */
  @GetMapping(BASE_PATH)
  String people(Model model) {
    model.addAttribute("people", personService.findAllLive());
    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("title", "People · Hauptbuch");
    return LIST_VIEW;
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

  /**
   * Resolve the entry dock's person sub-field (register §3.3, plan stage 8b): typed text names a
   * real account or an already-established person's per-currency debt leaf. Returns the {@code
   * personAccountResolved} fragment — the hidden account id the commit reads, plus a status; a name
   * that cannot be resolved (no such account/person, no leaf yet, or an unresolved currency
   * ambiguity) carries the error and no id, so nothing can commit an unresolved funding leg. On
   * success it raises {@link #TRIGGER_AFTER_SWAP} so the dock recomputes its amount fields against
   * the newly-resolved funding account (the same after-swap chain the transfer counterpart uses).
   */
  @PostMapping("/people/resolve-account")
  String resolveAccount(
      @RequestParam String personAccountText, Model model, HttpServletResponse response) {
    try {
      Account account = personAccountResolutionService.resolve(personAccountText);
      // A person's leaf displays their name, not its cosmetic internal account name.
      String displayName =
          personService.personNameForAccount(account.accountId()).orElse(account.name());
      model.addAttribute("accountId", account.accountId());
      model.addAttribute("accountName", displayName + " (" + account.currencyCode() + ")");
      model.addAttribute("error", null);
      response.setHeader(TRIGGER_AFTER_SWAP, PERSON_ACCOUNT_RESOLVED);
    } catch (IllegalArgumentException e) {
      model.addAttribute("accountId", "");
      model.addAttribute("accountName", null);
      model.addAttribute("error", e.getMessage());
    }
    return "fragments/entry-dock :: personAccountResolved(accountId=${accountId},"
        + " accountName=${accountName}, error=${error})";
  }
}
