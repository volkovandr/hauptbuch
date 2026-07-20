package volkovandr.hauptbuch.operations;

import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.RegisterFilter;
import volkovandr.hauptbuch.ledger.RegisterService;
import volkovandr.hauptbuch.ledger.RegisterView;
import volkovandr.hauptbuch.ledger.UnbalancedTransactionException;

/**
 * The split panel's htmx endpoints (register §3.10, plan stage 7c.2) — the multi-line sibling of
 * {@link RegisterEntryController}. Lives in {@code operations} for the same reason the dock's
 * controller does (plan stage 7 boundary note): committing a split orchestrates {@code
 * categories}-resolved ids, {@code ledger} payee resolution, per-currency-leaf routing, and {@code
 * recordTransaction}, which a controller in {@code ledger} could not reach without a module cycle.
 *
 * <p>Endpoints: {@link #open} seeds the panel from the dock's committed single line; {@link
 * #addLine} and {@link #removeLine} re-render the whole panel (the form is the single source of
 * truth) with "the rest" defaulting; {@link #commit} records or re-threads the split and repaints
 * the rows. Category <em>create-new</em> is not here — each line resolves through {@code
 * categories} first (the shared, parameterised {@code /categories/resolve}).
 */
@Controller
class RegisterSplitController {

  private static final String REGISTER = "register";
  private static final String CURRENCIES = "currencies";
  private static final String PANEL =
      "fragments/split-panel :: panel(register=${register}," + " panel=${panel}, oob=%s)";
  private static final String PANEL_DIRECT = String.format(PANEL, "false");
  private static final String PANEL_OOB = String.format(PANEL, "true");
  // sticky=null: the split panel resets to a bare dock, never a pre-filled one (plan stage 8b.1
  // scopes sticky defaults to the simple dock; the panel's own reset is 8b.2's if it wants one).
  private static final String COMMITTED =
      "fragments/entry-dock :: committed(register=${register}, amountFields=${amountFields},"
          + " sticky=null)";

  private final DockSplitService dockSplitService;
  private final SplitPanelAssembler assembler;
  private final SplitTagPills tagPills;
  private final RegisterService registerService;
  private final AccountService accountService;
  private final DockAmountFieldsService dockAmountFieldsService;

  RegisterSplitController(
      DockSplitService dockSplitService,
      SplitPanelAssembler assembler,
      SplitTagPills tagPills,
      RegisterService registerService,
      AccountService accountService,
      DockAmountFieldsService dockAmountFieldsService) {
    this.dockSplitService = dockSplitService;
    this.assembler = assembler;
    this.tagPills = tagPills;
    this.registerService = registerService;
    this.accountService = accountService;
    this.dockAmountFieldsService = dockAmountFieldsService;
  }

  /**
   * Open the split panel from the dock's committed single line (register §3.9→§3.10): the dock's
   * category, amount and header seed one line; the reference total is that line's magnitude, so the
   * panel opens balanced (<code>remaining 0,00 ✓</code>). Swaps the panel over the dock.
   */
  @PostMapping("/register/split")
  String open(
      @ModelAttribute DockEntryForm form,
      @RequestParam(required = false) String categoryText,
      Model model) {
    // A person funding the whole split is not supported yet (issue 07): the panel's Account is a
    // real-account <select>, so opening a split from a `by`/`for` dock entry would silently drop
    // the
    // person and misbook. Refuse cleanly here rather than substitute a phantom account.
    if (form.hasFundingPerson()) {
      return dockRefusal(
          form,
          "Splitting a person-funded entry isn't supported yet — enter each line as its own "
              + "transaction, or fund the split from a real account.",
          model);
    }
    // A transfer line carries a direction, not a category type: the dock's committed line may be a
    // transfer to a real own account (register §3.8, plan stage 7d.3), so seed the direction and
    // leave the type blank in that case.
    String direction = SplitFormBinder.blankToNull(form.transferDirection());
    String type =
        direction != null || form.categoryId() == null
            ? ""
            : accountService.findById(form.categoryId()).map(Account::type).orElse("");
    String categoryId = form.categoryId() == null ? "" : String.valueOf(form.categoryId());
    SplitSeed seed = seedFromDock(form);

    // The dock's transaction-level tags become the split's header tags; the one seeded line
    // inherits
    // them (register §3.6 — txn-level tags set before splitting land on every line, plan stage
    // 7e.3).
    List<Long> headerTags = form.tagId() == null ? List.of() : form.tagId();

    SplitForm seedForm =
        new SplitForm(
            form.transactionId(),
            form.date(),
            form.accountId(),
            form.payeeText(),
            form.note(),
            SplitFormBinder.openingTotal(seed.lineAmount(), type),
            seed.spendingCurrencyCode(),
            seed.fundingTotal(),
            seed.baseTotal(),
            List.of(categoryText == null ? "" : categoryText),
            List.of(categoryId),
            List.of(type),
            List.of(direction == null ? "" : direction),
            // The dock's committed line may itself be a person attribution (register §3.5, plan
            // stage 8b.1): it seeds the split's first line the same way a category or transfer
            // does, so splitting a `for Max` entry keeps Max on line one.
            List.of(SplitFormBinder.orEmpty(form.personName())),
            List.of(SplitFormBinder.orEmpty(form.personDirection())),
            List.of(SplitFormBinder.orEmpty(form.personRevive())),
            List.of(seed.lineAmount()),
            List.of(""),
            headerTags,
            List.of(headerTags),
            form.viewAccountId(),
            form.viewFromDate(),
            form.viewToDate(),
            form.viewPayeeId());
    return renderPanel(seedForm, null, PANEL_DIRECT, model);
  }

  /**
   * Lift the dock's committed single line into the split panel's first line and header (register
   * §3.9→§3.10). A cross-currency dock line (register §3.8a) carries the currency selector plus the
   * category (spending) and base amounts: the split seeds its one line in the spending currency and
   * moves the funding/base totals to the header. A same-currency line seeds its funding amount and
   * leaves the header currency fields blank.
   */
  private SplitSeed seedFromDock(DockEntryForm form) {
    String fundingCurrency =
        form.accountId() == null
            ? ""
            : accountService.findById(form.accountId()).map(Account::currencyCode).orElse("");
    String spending = SplitFormBinder.blankToNull(form.categoryCurrencyCode());
    boolean cross =
        spending != null && !fundingCurrency.isBlank() && !spending.equals(fundingCurrency);
    if (!cross) {
      return new SplitSeed(null, SplitFormBinder.orEmpty(form.amount()), "", "");
    }
    return new SplitSeed(
        spending,
        SplitFormBinder.orEmpty(form.categoryAmount()),
        SplitFormBinder.orEmpty(form.amount()),
        SplitFormBinder.orEmpty(form.baseAmount()));
  }

  /**
   * Recompute the split header's currency layout (register §3.8a/§3.10): the spending-currency
   * selector's own change, or the funding account's, re-renders the whole panel so the funding/base
   * total fields appear or hide and the derived per-line columns refresh. A blank base total is
   * pre-filled from {@code rate_as_of} (confirmable) when neither leg is the base currency.
   */
  @PostMapping("/register/split/currency")
  String currency(@RequestParam MultiValueMap<String, String> params, Model model) {
    SplitForm form = SplitFormBinder.bind(params);
    String baseTotal =
        dockAmountFieldsService.splitBaseTotalPrefill(
            form.accountId(),
            form.spendingCurrencyCode(),
            form.date(),
            form.fundingTotal(),
            form.baseTotal());
    return renderPanel(SplitFormBinder.withBaseTotal(form, baseTotal), null, PANEL_DIRECT, model);
  }

  /**
   * The dock's committed line lifted into the split's first line + cross-currency header totals.
   */
  private record SplitSeed(
      String spendingCurrencyCode, String lineAmount, String fundingTotal, String baseTotal) {}

  /** Append a line defaulting to "the rest" and re-render the panel (register §3.10). */
  @PostMapping("/register/split/add-line")
  String addLine(@RequestParam MultiValueMap<String, String> params, Model model) {
    return renderPanel(assembler.addLine(SplitFormBinder.bind(params)), null, PANEL_DIRECT, model);
  }

  /** Remove the line at {@code index} and re-render the panel (register §3.10). */
  @PostMapping("/register/split/remove-line")
  String removeLine(
      @RequestParam MultiValueMap<String, String> params, @RequestParam int index, Model model) {
    return renderPanel(
        assembler.removeLine(SplitFormBinder.bind(params), index), null, PANEL_DIRECT, model);
  }

  /**
   * Commit the split — records a new transaction, or re-threads the edited one — then repaint the
   * rows and reset the dock to new mode (as the simple commit does). On a validation error the rows
   * are left untouched and the panel re-renders out-of-band carrying the message.
   */
  @PostMapping("/register/split/commit")
  String commit(@RequestParam MultiValueMap<String, String> params, Model model) {
    SplitForm form = SplitFormBinder.bind(params);
    RegisterFilter filter = SplitFormBinder.filterFrom(form);
    if (form.accountId() == null) {
      return renderPanel(form, "An account is required", PANEL_OOB, model);
    }
    try {
      dockSplitService.commit(
          new SplitEntry(
              form.transactionId(),
              form.date(),
              form.accountId(),
              null,
              SplitFormBinder.blankToNull(form.payeeText()),
              form.note(),
              SplitFormBinder.blankToNull(form.spendingCurrencyCode()),
              form.fundingTotal(),
              form.baseTotal(),
              form.tagId() == null ? List.of() : form.tagId(),
              SplitFormBinder.linesOf(form)));
    } catch (IllegalArgumentException | UnbalancedTransactionException e) {
      // UnbalancedTransactionException is the engine's balance-invariant signal (e.g. a phantom
      // all-zero-base cross-currency split): show it in the panel rather than let it 500 into an
      // empty swap, which reads as a commit that silently did nothing.
      return renderPanel(form, e.getMessage(), PANEL_OOB, model);
    }
    RegisterView register = registerService.view(filter);
    model.addAttribute(REGISTER, register);
    model.addAttribute(CURRENCIES, dockAmountFieldsService.currencies());
    model.addAttribute("amountFields", dockAmountFieldsService.fresh(register));
    return COMMITTED;
  }

  /**
   * Cancel the split and return to the entry dock <em>without losing what was entered</em>
   * (register §3.9): the dock re-opens pre-filled with the header (date, account, payee, note) and
   * the first line's category and amount, rather than blank. Always a fresh <em>new</em> dock (a
   * null transaction id) — cancelling abandons the split as a split; the register row, if this was
   * an edit, is untouched until a real Save.
   */
  @PostMapping("/register/split/cancel")
  String cancel(@RequestParam MultiValueMap<String, String> params, Model model) {
    SplitForm form = SplitFormBinder.bind(params);
    List<String> ids = form.lineCategoryId();
    List<String> texts = form.categoryText();
    List<String> amounts = form.lineAmount();
    DockEditModel prefill =
        new DockEditModel(
            null,
            form.date(),
            form.accountId(),
            null, // fundingPersonLabel (splits don't reach a person leg in the dock's edit mode)
            form.payeeText(),
            amounts.isEmpty() ? null : SplitFormBinder.blankToNull(amounts.get(0)),
            SplitFormBinder.parseLong(ids.isEmpty() ? null : ids.get(0)),
            texts.isEmpty() ? null : SplitFormBinder.blankToNull(texts.get(0)),
            null, // categoryCurrencyCode (splits don't have cross-currency in the dock's edit mode)
            null, // categoryAmount
            null, // baseAmount
            null, // transferDirection
            form.note(),
            // The split's transaction-level tags return to the dock's chip field so cancelling does
            // not lose them (register §3.6/§3.9, plan stage 7e.3); the per-line tags are dropped
            // with
            // the lines the dock cannot represent.
            tagPills.resolvePills(form.tagId()));
    model.addAttribute(REGISTER, registerService.view(SplitFormBinder.filterFrom(form)));
    model.addAttribute("edit", prefill);
    model.addAttribute(CURRENCIES, dockAmountFieldsService.currencies());
    model.addAttribute("amountFields", dockAmountFieldsService.forAccount(form.accountId()));
    return "fragments/entry-dock :: dock(register=${register}, oob=false, edit=${edit},"
        + " amountFields=${amountFields})";
  }

  /**
   * Refuse to open the split, re-rendering the dock (over {@code #entry-dock}, the Split button's
   * target) in new mode carrying {@code message} — used when the dock is person-funded, which the
   * split panel cannot yet represent (issue 07). The register stays put; the user re-enters the
   * lines separately or funds from a real account.
   */
  private String dockRefusal(DockEntryForm form, String message, Model model) {
    RegisterView register = registerService.view(filterFrom(form));
    model.addAttribute(REGISTER, register);
    model.addAttribute("entryError", message);
    model.addAttribute(CURRENCIES, dockAmountFieldsService.currencies());
    model.addAttribute("amountFields", dockAmountFieldsService.fresh(register));
    return "fragments/entry-dock :: dock(register=${register}, oob=false, edit=null,"
        + " amountFields=${amountFields})";
  }

  private static RegisterFilter filterFrom(DockEntryForm form) {
    return new RegisterFilter(
        form.viewAccountId() == null ? List.of() : form.viewAccountId(),
        form.viewFromDate(),
        form.viewToDate(),
        form.viewPayeeId());
  }

  /**
   * Build the panel view model, add it plus the register view and currency options, then render.
   */
  private String renderPanel(SplitForm form, String error, String template, Model model) {
    model.addAttribute(REGISTER, registerService.view(SplitFormBinder.filterFrom(form)));
    model.addAttribute("panel", assembler.panel(form, error));
    model.addAttribute(CURRENCIES, dockAmountFieldsService.currencies());
    return template;
  }
}
