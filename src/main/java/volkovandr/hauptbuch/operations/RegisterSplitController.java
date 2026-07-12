package volkovandr.hauptbuch.operations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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
import volkovandr.hauptbuch.shared.MoneyFormat;

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

  private static final int FRACTION_DIGITS = 2;
  private static final String REGISTER = "register";
  private static final String PANEL =
      "fragments/split-panel :: panel(register=${register}," + " panel=${panel}, oob=%s)";
  private static final String PANEL_DIRECT = String.format(PANEL, "false");
  private static final String PANEL_OOB = String.format(PANEL, "true");
  private static final String COMMITTED =
      "fragments/entry-dock :: committed(register=${register}, amountFields=${amountFields})";

  private final DockSplitService dockSplitService;
  private final SplitPanelAssembler assembler;
  private final RegisterService registerService;
  private final AccountService accountService;
  private final DockAmountFieldsService dockAmountFieldsService;

  RegisterSplitController(
      DockSplitService dockSplitService,
      SplitPanelAssembler assembler,
      RegisterService registerService,
      AccountService accountService,
      DockAmountFieldsService dockAmountFieldsService) {
    this.dockSplitService = dockSplitService;
    this.assembler = assembler;
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
    String type =
        form.categoryId() == null
            ? ""
            : accountService.findById(form.categoryId()).map(Account::type).orElse("");
    String categoryId = form.categoryId() == null ? "" : String.valueOf(form.categoryId());
    String amount = form.amount() == null ? "" : form.amount();

    SplitForm seed =
        new SplitForm(
            form.transactionId(),
            form.date(),
            form.accountId(),
            form.payeeText(),
            form.note(),
            openingTotal(amount, type),
            List.of(categoryText == null ? "" : categoryText),
            List.of(categoryId),
            List.of(type),
            List.of(amount),
            List.of(""),
            form.viewAccountId(),
            form.viewFromDate(),
            form.viewToDate(),
            form.viewPayeeId());
    return renderPanel(seed, null, PANEL_DIRECT, model);
  }

  /** Append a line defaulting to "the rest" and re-render the panel (register §3.10). */
  @PostMapping("/register/split/add-line")
  String addLine(@RequestParam MultiValueMap<String, String> params, Model model) {
    return renderPanel(assembler.addLine(bind(params)), null, PANEL_DIRECT, model);
  }

  /** Remove the line at {@code index} and re-render the panel (register §3.10). */
  @PostMapping("/register/split/remove-line")
  String removeLine(
      @RequestParam MultiValueMap<String, String> params, @RequestParam int index, Model model) {
    return renderPanel(assembler.removeLine(bind(params), index), null, PANEL_DIRECT, model);
  }

  /**
   * Commit the split — records a new transaction, or re-threads the edited one — then repaint the
   * rows and reset the dock to new mode (as the simple commit does). On a validation error the rows
   * are left untouched and the panel re-renders out-of-band carrying the message.
   */
  @PostMapping("/register/split/commit")
  String commit(@RequestParam MultiValueMap<String, String> params, Model model) {
    SplitForm form = bind(params);
    RegisterFilter filter = filterFrom(form);
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
              blankToNull(form.payeeText()),
              form.note(),
              linesOf(form)));
    } catch (IllegalArgumentException e) {
      return renderPanel(form, e.getMessage(), PANEL_OOB, model);
    }
    RegisterView register = registerService.view(filter);
    model.addAttribute(REGISTER, register);
    model.addAttribute("currencies", dockAmountFieldsService.currencies());
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
    SplitForm form = bind(params);
    List<String> ids = form.lineCategoryId();
    List<String> texts = form.categoryText();
    List<String> amounts = form.lineAmount();
    DockEditModel prefill =
        new DockEditModel(
            null,
            form.date(),
            form.accountId(),
            form.payeeText(),
            amounts.isEmpty() ? null : blankToNull(amounts.get(0)),
            parseLong(ids.isEmpty() ? null : ids.get(0)),
            texts.isEmpty() ? null : blankToNull(texts.get(0)),
            form.note());
    model.addAttribute(REGISTER, registerService.view(filterFrom(form)));
    model.addAttribute("edit", prefill);
    model.addAttribute("currencies", dockAmountFieldsService.currencies());
    model.addAttribute("amountFields", dockAmountFieldsService.forAccount(form.accountId()));
    return "fragments/entry-dock :: dock(register=${register}, oob=false, edit=${edit},"
        + " amountFields=${amountFields})";
  }

  /** Build the panel view model, add it plus the register view, and return the chosen fragment. */
  private String renderPanel(SplitForm form, String error, String template, Model model) {
    model.addAttribute(REGISTER, registerService.view(filterFrom(form)));
    model.addAttribute("panel", assembler.panel(form, error));
    return template;
  }

  /**
   * Bind the panel form from the <em>raw</em> request parameters, not via {@code @ModelAttribute}
   * list binding. A split line's amount or note legitimately contains a comma (German decimals —
   * {@code 20,50} — and free text), and Spring's collection binding splits a single {@code
   * lineAmount=20,50} value on the comma into two elements, misaligning every line array. Reading
   * the raw multi-valued params keeps each line's value intact (a fix for the "one comma spawns
   * extra lines" bug).
   */
  private static SplitForm bind(MultiValueMap<String, String> p) {
    return new SplitForm(
        parseLong(p.getFirst("transactionId")),
        parseDate(p.getFirst("date")),
        parseLong(p.getFirst("accountId")),
        p.getFirst("payeeText"),
        p.getFirst("note"),
        p.getFirst("total"),
        orEmpty(p.get("categoryText")),
        orEmpty(p.get("lineCategoryId")),
        orEmpty(p.get("lineCategoryType")),
        orEmpty(p.get("lineAmount")),
        orEmpty(p.get("lineNote")),
        longValues(p.get("viewAccountId")),
        parseDate(p.getFirst("viewFromDate")),
        parseDate(p.getFirst("viewToDate")),
        parseLong(p.getFirst("viewPayeeId")));
  }

  private static List<String> orEmpty(List<String> values) {
    return values == null ? List.of() : values;
  }

  private static List<Long> longValues(List<String> values) {
    if (values == null) {
      return List.of();
    }
    return values.stream().filter(v -> v != null && !v.isBlank()).map(Long::valueOf).toList();
  }

  private static Long parseLong(String value) {
    return value == null || value.isBlank() ? null : Long.valueOf(value.strip());
  }

  private static LocalDate parseDate(String value) {
    return value == null || value.isBlank() ? null : LocalDate.parse(value.strip());
  }

  /** The reference total a freshly-opened panel counts against — the seed line's magnitude. */
  private static String openingTotal(String amount, String type) {
    BigDecimal net;
    try {
      net = DockSplitService.signedContribution(amount, type);
    } catch (IllegalArgumentException e) {
      net = BigDecimal.ZERO;
    }
    return MoneyFormat.number(net.abs(), FRACTION_DIGITS);
  }

  /** The complete lines of the form; skips fully-blank lines, rejects a line with no category. */
  private static List<SplitLineDraft> linesOf(SplitForm form) {
    List<SplitLineDraft> lines = new ArrayList<>();
    int count =
        Math.max(
            size(form.lineCategoryId()), Math.max(size(form.lineAmount()), size(form.lineNote())));
    for (int i = 0; i < count; i++) {
      String idText = at(form.lineCategoryId(), i);
      String amount = at(form.lineAmount(), i);
      if (idText.isBlank() && amount.isBlank()) {
        continue; // an empty line the user never filled in
      }
      if (idText.isBlank()) {
        throw new IllegalArgumentException("Each split line needs a category (pick or create one)");
      }
      lines.add(
          new SplitLineDraft(
              Long.parseLong(idText.strip()), amount, blankToNull(at(form.lineNote(), i))));
    }
    return lines;
  }

  private static RegisterFilter filterFrom(SplitForm form) {
    return new RegisterFilter(
        form.viewAccountId() == null ? List.of() : form.viewAccountId(),
        form.viewFromDate(),
        form.viewToDate(),
        form.viewPayeeId());
  }

  private static int size(List<String> list) {
    return list == null ? 0 : list.size();
  }

  private static String at(List<String> list, int index) {
    if (list == null || index >= list.size() || list.get(index) == null) {
      return "";
    }
    return list.get(index);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
