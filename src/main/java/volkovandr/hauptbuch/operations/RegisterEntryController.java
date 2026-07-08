package volkovandr.hauptbuch.operations;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.ledger.Payee;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.ledger.RegisterFilter;
import volkovandr.hauptbuch.ledger.RegisterService;
import volkovandr.hauptbuch.ledger.RegisterView;
import volkovandr.hauptbuch.operations.repository.GhostSuggestionRepository;

/**
 * The entry dock's htmx endpoints (register §3, plan stage 7b). Lives in {@code operations}, beside
 * {@link DockCommitService}: committing a dock entry orchestrates {@code categories}-resolved
 * category ids, {@code ledger} payee resolution, per-currency-leaf routing, and {@code
 * recordTransaction} — a dock controller in {@code ledger} could reach none of those without
 * closing a module cycle (the 6d precedent — plan stage 7 boundary note). It composes the commit
 * with {@code ledger}'s {@link RegisterService} read to repaint the rows.
 *
 * <p>Two endpoints: {@link #commit} records the transaction and re-renders the rows body for the
 * active filter (a bounded re-fetch, so a backdated insert re-threads every affected balance below
 * it — §2.2, Q-UI-5); {@link #ghost} returns the payee's most-common category as a single ghost
 * suggestion (§3.9). Category <em>create-new</em> is not here — the browser resolves it through the
 * {@code categories} module first (see {@code CategoriesController.resolveCategory}).
 */
@Controller
class RegisterEntryController {

  private static final String DOCK_FRAGMENT = "fragments/entry-dock";

  private final DockCommitService dockCommitService;
  private final RegisterService registerService;
  private final PayeeService payeeService;
  private final GhostSuggestionRepository ghostSuggestionRepository;

  RegisterEntryController(
      DockCommitService dockCommitService,
      RegisterService registerService,
      PayeeService payeeService,
      GhostSuggestionRepository ghostSuggestionRepository) {
    this.dockCommitService = dockCommitService;
    this.registerService = registerService;
    this.payeeService = payeeService;
    this.ghostSuggestionRepository = ghostSuggestionRepository;
  }

  /**
   * Commit a new simple transaction from the dock, then re-render the rows body for the active
   * filter (carried as {@code view*} hidden fields) so the new (or backdated) row threads
   * correctly. The dock is reset via an out-of-band swap of its fragment. On a validation error the
   * rows are left untouched and the dock re-renders carrying the message.
   */
  @PostMapping("/register/entry")
  String commit(@ModelAttribute DockEntryForm form, Model model) {
    RegisterFilter filter =
        new RegisterFilter(
            form.viewAccountId() == null ? List.of() : form.viewAccountId(),
            form.viewFromDate(),
            form.viewToDate(),
            form.viewPayeeId());

    try {
      dockCommitService.commit(
          new DockEntry(
              form.date(),
              form.accountId(),
              null,
              blankToNull(form.payeeText()),
              form.categoryId(),
              form.amount(),
              form.note()));
    } catch (IllegalArgumentException e) {
      RegisterView view = registerService.view(filter);
      model.addAttribute("register", view);
      model.addAttribute("entryError", e.getMessage());
      // Error: OOB-replace only the dock (carrying the message); the rows region is left untouched.
      return DOCK_FRAGMENT + " :: dock(register=${register}, oob=true)";
    }

    RegisterView view = registerService.view(filter);
    model.addAttribute("register", view);
    // The rows body (primary, outerHTML into #register-rows) repaints the affected thread; the dock
    // resets via its out-of-band fragment — one response does both (§2.2).
    return DOCK_FRAGMENT + " :: committed(register=${register})";
  }

  /**
   * The ghost category suggestion for the typed payee (register §3.9): if the text names an
   * existing payee with a most-common category, return it as a single suggestion the dock shows and
   * pre-fills; otherwise return nothing (a create-new payee has no history yet).
   */
  @GetMapping("/register/ghost")
  String ghost(@RequestParam(required = false) String payeeText, Model model) {
    Optional<String> suggestion =
        payeeService
            .findExisting(payeeText)
            .map(Payee::payeeId)
            .flatMap(ghostSuggestionRepository::suggestFor)
            .map(GhostSuggestion::categoryName);
    model.addAttribute("categoryName", suggestion.orElse(null));
    return DOCK_FRAGMENT + " :: ghost(categoryName=${categoryName})";
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
