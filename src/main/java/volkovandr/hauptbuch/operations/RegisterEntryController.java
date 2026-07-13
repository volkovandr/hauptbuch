package volkovandr.hauptbuch.operations;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.ledger.CrossCurrencyFields;
import volkovandr.hauptbuch.ledger.Payee;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.ledger.RegisterFilter;
import volkovandr.hauptbuch.ledger.RegisterService;
import volkovandr.hauptbuch.ledger.RegisterView;
import volkovandr.hauptbuch.operations.repository.GhostSuggestionRepository;

/**
 * The entry dock's htmx endpoints (register §3, plan stages 7b/7d.1). Lives in {@code operations},
 * beside {@link DockCommitService}: committing a dock entry orchestrates {@code
 * categories}-resolved category ids, {@code ledger} payee resolution, per-currency-leaf routing,
 * and {@code recordTransaction} — a dock controller in {@code ledger} could reach none of those
 * without closing a module cycle (the 6d precedent — plan stage 7 boundary note). It composes the
 * commit with {@code ledger}'s {@link RegisterService} read to repaint the rows.
 *
 * <p>Endpoints: {@link #commit} records a new transaction <em>or</em> re-threads an existing one
 * (edit mode, register §3.1) and re-renders the rows body for the active filter (a bounded
 * re-fetch, so a backdated insert or a re-threaded edit corrects every affected balance below it —
 * §2.2, Q-UI-5); {@link #edit} loads a live transaction into the dock's edit mode; {@link
 * #voidTransaction} soft-deletes the edited transaction; {@link #ghost} returns the payee's
 * most-common category as a single ghost suggestion (§3.9); {@link #currencyFields} recomputes the
 * cross-currency amount-field layout when the category-currency selector or the funding account
 * changes (§3.5/§3.8a). Category <em>create-new</em> is not here — the browser resolves it through
 * the {@code categories} module first (see {@code CategoriesController.resolveCategory}).
 */
@Controller
class RegisterEntryController {

  private static final String DOCK_FRAGMENT = "fragments/entry-dock";
  private static final String REGISTER = "register";
  private static final String AMOUNT_FIELDS = "amountFields";
  private static final String CURRENCIES = "currencies";

  /** OOB-replace only the dock (carrying an error); the rows region is left untouched. */
  private static final String DOCK_ERROR =
      DOCK_FRAGMENT
          + " :: dock(register=${register}, oob=true, edit=null,"
          + " amountFields=${amountFields})";

  /** Repaint the rows (primary) and reset the dock to new mode (out-of-band) in one response. */
  private static final String COMMITTED =
      DOCK_FRAGMENT + " :: committed(register=${register}, amountFields=${amountFields})";

  private static final String SPLIT_PANEL =
      "fragments/split-panel :: panel(register=${register}, panel=${panel}, oob=false)";

  private final DockCommitService dockCommitService;
  private final DockEditService dockEditService;
  private final DockSplitService dockSplitService;
  private final SplitPanelAssembler splitPanelAssembler;
  private final RegisterService registerService;
  private final PayeeService payeeService;
  private final DockAmountFieldsService dockAmountFieldsService;
  private final GhostSuggestionRepository ghostSuggestionRepository;

  RegisterEntryController(
      DockCommitService dockCommitService,
      DockEditService dockEditService,
      DockSplitService dockSplitService,
      SplitPanelAssembler splitPanelAssembler,
      RegisterService registerService,
      PayeeService payeeService,
      DockAmountFieldsService dockAmountFieldsService,
      GhostSuggestionRepository ghostSuggestionRepository) {
    this.dockCommitService = dockCommitService;
    this.dockEditService = dockEditService;
    this.dockSplitService = dockSplitService;
    this.splitPanelAssembler = splitPanelAssembler;
    this.registerService = registerService;
    this.payeeService = payeeService;
    this.dockAmountFieldsService = dockAmountFieldsService;
    this.ghostSuggestionRepository = ghostSuggestionRepository;
  }

  /**
   * Commit the dock — <em>new</em> when {@code transactionId} is absent, an in-place <em>edit</em>
   * when present (register §3.1) — then re-render the rows body for the active filter (carried as
   * {@code view*} hidden fields) so the new/backdated/re-threaded row(s) thread correctly. The dock
   * is reset to new mode via an out-of-band swap of its fragment (§3.1). On a validation error the
   * rows are left untouched and the dock re-renders carrying the message and the entered fields
   * (including any cross-currency amounts, §3.8a).
   */
  @PostMapping("/register/entry")
  String commit(@ModelAttribute DockEntryForm form, Model model) {
    RegisterFilter filter = filterFrom(form);
    if (form.accountId() == null) {
      return dockError(filter, form, "An account is required", model);
    }
    if (form.categoryId() == null) {
      return dockError(
          filter, form, "A category or transfer target is required (pick or create one)", model);
    }
    try {
      dockCommitService.commit(
          new DockEntry(
              form.transactionId(),
              form.date(),
              form.accountId(),
              null,
              blankToNull(form.payeeText()),
              form.categoryId(),
              form.categoryCurrencyCode(),
              form.amount(),
              form.categoryAmount(),
              form.baseAmount(),
              form.note(),
              form.transferDirection()));
    } catch (IllegalArgumentException | IllegalStateException e) {
      return dockError(filter, form, e.getMessage(), model);
    }
    RegisterView register = registerService.view(filter);
    model.addAttribute(REGISTER, register);
    addCurrencyAttributes(model, dockAmountFieldsService.fresh(register));
    return COMMITTED;
  }

  /**
   * Load a live transaction into the dock's edit mode (register §3.1). The row's edit affordance
   * hx-gets this, carrying the active filter as {@code view*} params (so the dock keeps repainting
   * the current view), and swaps the returned dock fragment over {@code #entry-dock}. A transaction
   * the dock can't edit yet (a transfer, an opening balance, cross-currency) re-renders the dock in
   * new mode carrying an explanatory message rather than loading a half-usable form.
   */
  @GetMapping("/register/edit/{transactionId}")
  String edit(@PathVariable long transactionId, @ModelAttribute DockEntryForm form, Model model) {
    RegisterView register = registerService.view(filterFrom(form));
    model.addAttribute(REGISTER, register);
    try {
      DockEditModel edit = dockEditService.load(transactionId);
      model.addAttribute("edit", edit);
      addCurrencyAttributes(model, dockAmountFieldsService.forAccount(edit.accountId()));
      return DOCK_FRAGMENT
          + " :: dock(register=${register}, oob=false, edit=${edit}, amountFields=${amountFields})";
    } catch (IllegalArgumentException notSimple) {
      // Not a simple one-category transaction: it may still be a split the panel edits (register
      // §3.10). Try that before giving up.
      try {
        model.addAttribute(
            "panel", splitPanelAssembler.panel(dockSplitService.load(transactionId), null));
        return SPLIT_PANEL;
      } catch (IllegalArgumentException notSplit) {
        // Neither shape (a transfer, opening balance, or cross-currency): stay in new mode carrying
        // the simple-dock explanation. Not an OOB swap — the edit affordance targets #entry-dock.
        model.addAttribute("entryError", notSimple.getMessage());
        addCurrencyAttributes(model, dockAmountFieldsService.fresh(register));
        return DOCK_FRAGMENT
            + " :: dock(register=${register}, oob=false, edit=null,"
            + " amountFields=${amountFields})";
      }
    }
  }

  /**
   * Void the transaction being edited (register §3.1) — a reversible soft-delete (data-model §3.5)
   * — then repaint the rows (the row is gone and every balance below it re-threads) and reset the
   * dock to new mode, exactly as a commit does.
   */
  @PostMapping("/register/void")
  String voidTransaction(@ModelAttribute DockEntryForm form, Model model) {
    RegisterFilter filter = filterFrom(form);
    if (form.transactionId() == null) {
      return dockError(filter, form, "No transaction selected to void", model);
    }
    try {
      dockCommitService.voidTransaction(form.transactionId());
    } catch (IllegalArgumentException e) {
      return dockError(filter, form, e.getMessage(), model);
    }
    RegisterView register = registerService.view(filter);
    model.addAttribute(REGISTER, register);
    addCurrencyAttributes(model, dockAmountFieldsService.fresh(register));
    return COMMITTED;
  }

  /**
   * A fresh new-mode dock for the active filter (register §3.1) — the Cancel affordance in edit
   * mode, which drops the edit and returns the dock to entering a new transaction.
   */
  @GetMapping("/register/dock")
  String dock(@ModelAttribute DockEntryForm form, Model model) {
    RegisterView register = registerService.view(filterFrom(form));
    model.addAttribute(REGISTER, register);
    addCurrencyAttributes(model, dockAmountFieldsService.fresh(register));
    return DOCK_FRAGMENT
        + " :: dock(register=${register}, oob=false, edit=null, amountFields=${amountFields})";
  }

  /**
   * Recompute the amount-field layout (register §3.5/§3.8a): the category-currency selector's own
   * change, or the funding account's (which resets the currency default — its {@code hx-include}
   * deliberately omits the stale {@code categoryCurrencyCode}, so this always re-derives it from
   * the new account). Returns the currency picker and the funding Amount field's currency label,
   * both out-of-band (so their shown value/label stays in sync — the funding Amount field's typed
   * value rides along unchanged), plus the primary swap of the extra amount field(s).
   */
  @PostMapping("/register/currency-fields")
  String currencyFields(@ModelAttribute DockEntryForm form, Model model) {
    addCurrencyAttributes(model, dockAmountFieldsService.forForm(form));
    model.addAttribute("fundingAmountText", form.amount());
    return DOCK_FRAGMENT
        + " :: currencyFieldsResponse(fields=${amountFields},"
        + " fundingAmountText=${fundingAmountText})";
  }

  /**
   * Re-render the dock (out-of-band) carrying a validation message and the entered fields
   * (including any cross-currency amounts, §3.8a) so the user can correct them; the rows are left
   * untouched.
   */
  private String dockError(RegisterFilter filter, DockEntryForm form, String message, Model model) {
    RegisterView register = registerService.view(filter);
    model.addAttribute(REGISTER, register);
    model.addAttribute("entryError", message);
    addCurrencyAttributes(model, dockAmountFieldsService.forForm(form));
    return DOCK_ERROR;
  }

  /** Add the currency picker's options and the resolved amount-field layout to the model. */
  private void addCurrencyAttributes(Model model, CrossCurrencyFields fields) {
    model.addAttribute(CURRENCIES, dockAmountFieldsService.currencies());
    model.addAttribute(AMOUNT_FIELDS, fields);
  }

  /** The active filter carried by the dock's {@code view*} fields (register §2.2). */
  private static RegisterFilter filterFrom(DockEntryForm form) {
    return new RegisterFilter(
        form.viewAccountId() == null ? List.of() : form.viewAccountId(),
        form.viewFromDate(),
        form.viewToDate(),
        form.viewPayeeId());
  }

  /**
   * The ghost category suggestion for the typed payee (register §3.9): if the text names an
   * existing payee with a most-common category, return it as a single suggestion the dock shows and
   * pre-fills; otherwise return nothing (a create-new payee has no history yet).
   */
  @GetMapping("/register/ghost")
  String ghost(@RequestParam(required = false) String payeeText, Model model) {
    Optional<GhostSuggestion> suggestion =
        payeeService
            .findExisting(payeeText)
            .map(Payee::payeeId)
            .flatMap(ghostSuggestionRepository::suggestFor);
    model.addAttribute("categoryId", suggestion.map(GhostSuggestion::categoryId).orElse(null));
    model.addAttribute("categoryName", suggestion.map(GhostSuggestion::categoryName).orElse(null));
    return DOCK_FRAGMENT + " :: ghost(categoryId=${categoryId}, categoryName=${categoryName})";
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
