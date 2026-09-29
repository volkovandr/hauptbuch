package volkovandr.hauptbuch.recurring;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.ledger.CurrencyService;
import volkovandr.hauptbuch.ledger.RegisterService;
import volkovandr.hauptbuch.ledger.RegisterView;
import volkovandr.hauptbuch.ledger.UnbalancedTransactionException;
import volkovandr.hauptbuch.operations.SplitCurrencyService;
import volkovandr.hauptbuch.operations.SplitForm;
import volkovandr.hauptbuch.operations.SplitFormBinder;
import volkovandr.hauptbuch.operations.SplitPanelAssembler;
import volkovandr.hauptbuch.operations.SplitPanelHost;
import volkovandr.hauptbuch.web.NavItem;

/**
 * The recurring page and the template editor (recurring sub-plan slice b). The editor is the
 * register's split panel in template mode (data-model §14.1): the same fragment, form binding and
 * panel assembly, posting to {@code /recurring/editor/*} and carrying the schedule block. The
 * add-line, remove-line and currency round-trips mirror the register's split endpoints,
 * re-rendering the whole panel with the schedule block the form carries.
 */
@Controller
class RecurringController {

  private static final String BASE_PATH = "/recurring";
  private static final String PANEL_FRAGMENT = "recurring-editor :: editorPanel";
  private static final String EDITOR_URL = BASE_PATH + "/editor";
  private static final String PENDING_CHOICES = "pendingChoices";
  private static final String TOTALS_HELP =
      "Each occurrence proposes these totals from the latest rate on or before its date. They are"
          + " not stored with the template.";

  private final RecurringTemplateService templateService;
  private final RecurringTemplateViews views;
  private final RecurringBookingService bookingService;
  private final RegisterService registerService;
  private final CurrencyService currencyService;
  private final SplitPanelAssembler assembler;
  private final SplitCurrencyService splitCurrencyService;

  RecurringController(
      RecurringTemplateService templateService,
      RecurringTemplateViews views,
      RecurringBookingService bookingService,
      RegisterService registerService,
      CurrencyService currencyService,
      SplitPanelAssembler assembler,
      SplitCurrencyService splitCurrencyService) {
    this.templateService = templateService;
    this.views = views;
    this.bookingService = bookingService;
    this.registerService = registerService;
    this.currencyService = currencyService;
    this.assembler = assembler;
    this.splitCurrencyService = splitCurrencyService;
  }

  /**
   * The recurring page: the live templates with their cadence and next three dates, and why any of
   * them cannot book (slice f).
   */
  @GetMapping(BASE_PATH)
  String list(Model model) {
    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("templates", views.rows());
    model.addAttribute("failures", bookingService.failures());
    return "recurring";
  }

  /**
   * The main page's booking-failure warnings (data-model §14.3, slice f), lazy-loaded by the
   * landing page: one line per live template that cannot book, linking to its editor. Empty when
   * every template books.
   */
  @GetMapping("/overview/recurring-warnings")
  String warnings(Model model) {
    model.addAttribute("failures", bookingService.failures().values());
    return "fragments/recurring-warnings :: warnings";
  }

  /** The editor for a new template. */
  @GetMapping(BASE_PATH + "/new")
  String create(Model model) {
    RegisterView register = registerService.datalists();
    return editorPage(views.blank(register.defaultAccountId()), register, model);
  }

  /** The editor for an existing template. */
  @GetMapping(BASE_PATH + "/{id}")
  String edit(@PathVariable long id, Model model) {
    return editorPage(views.load(id), registerService.datalists(), model);
  }

  /** Append a line defaulting to "the rest" and re-render the panel (register §3.10). */
  @PostMapping(EDITOR_URL + "/add-line")
  String addLine(@RequestParam MultiValueMap<String, String> params, Model model) {
    return panel(
        assembler.addLine(SplitFormBinder.bind(params)),
        RecurringScheduleForm.bind(params),
        null,
        model);
  }

  /** Remove the line at {@code index} and re-render the panel (register §3.10). */
  @PostMapping(EDITOR_URL + "/remove-line")
  String removeLine(
      @RequestParam MultiValueMap<String, String> params, @RequestParam int index, Model model) {
    return panel(
        assembler.removeLine(SplitFormBinder.bind(params), index),
        RecurringScheduleForm.bind(params),
        null,
        model);
  }

  /**
   * Re-render after the account, currency, a total or the end choice changed: any blank
   * cross-currency total is proposed from the rate on the start date (register §3.8a), and a line
   * with no amount yet takes the rest, so a total typed first fills the first line as the
   * register's Split does (register §3.10).
   */
  @PostMapping(EDITOR_URL + "/currency")
  String currency(@RequestParam MultiValueMap<String, String> params, Model model) {
    SplitForm proposed =
        assembler.restIntoBlankLine(
            splitCurrencyService.withProposedTotals(SplitFormBinder.bind(params)));
    return panel(proposed, RecurringScheduleForm.bind(params), null, model);
  }

  /**
   * Save the template and go back to the recurring page. A refused save re-renders the panel with
   * the message, keeping everything typed. Two questions hold a save back until answered (data-
   * model §14.3): a new template starting in the past asks whether to book its past occurrences,
   * and an end date that cuts off existing pending rows asks whether to keep them. The panel comes
   * back with the question, and the answer rides along on the next save.
   */
  @PostMapping(EDITOR_URL + "/save")
  String save(
      @RequestParam MultiValueMap<String, String> params,
      Model model,
      HttpServletResponse response) {
    SplitForm split = SplitFormBinder.bind(params);
    RecurringScheduleForm schedule = RecurringScheduleForm.bind(params);
    try {
      if (!schedule.pastOccurrencesAnswered()) {
        int past = templateService.pastOccurrences(schedule, split);
        if (past > 0) {
          model.addAttribute("pastOccurrences", past);
          return panel(split, schedule, null, model);
        }
      }
      if (schedule.pendingRowsAnswer() == null) {
        int cutOff = templateService.cutOffPending(schedule, split);
        if (cutOff > 0) {
          model.addAttribute("cutOffPending", cutOff);
          model.addAttribute(PENDING_CHOICES, PendingRows.values());
          return panel(split, schedule, null, model);
        }
      }
      templateService.save(schedule, split);
    } catch (IllegalArgumentException | IllegalStateException | UnbalancedTransactionException e) {
      return panel(split, schedule, e.getMessage(), model);
    }
    response.setHeader("HX-Redirect", BASE_PATH);
    return panel(split, schedule, null, model);
  }

  /**
   * Soft-delete a template and go back to the recurring page. A template with pending rows is not
   * deleted until the operator answers what becomes of them (data-model §14.3): the question opens
   * in the editor's dialog slot, and each answer posts here again.
   *
   * @param deleteAnswer a {@link PendingRows#code()}, blank while unasked. Not {@code pendingRows}:
   *     the panel's Delete posts its whole form, which may carry the end-date question's answer
   */
  @PostMapping(BASE_PATH + "/{id}/delete")
  String delete(
      @PathVariable long id,
      @RequestParam(required = false) String deleteAnswer,
      Model model,
      HttpServletResponse response) {
    PendingRows answer = PendingRows.fromCode(deleteAnswer);
    if (answer == null) {
      int pending = templateService.pendingRowCount(id);
      if (pending > 0) {
        model.addAttribute("recurringTemplateId", id);
        model.addAttribute("pendingCount", pending);
        model.addAttribute(PENDING_CHOICES, PendingRows.values());
        response.setHeader("HX-Retarget", "#recurring-dialog");
        response.setHeader("HX-Reswap", "innerHTML");
        return "recurring-editor :: deleteDialog";
      }
    }
    // With no pending rows there is nothing to keep or remove; the answer is moot.
    templateService.delete(id, answer == null ? PendingRows.KEEP_ALL : answer);
    response.setHeader("HX-Redirect", BASE_PATH);
    return "recurring-editor :: deleted";
  }

  /** The split panel hosted by the editor: its endpoints, Start, and Cancel/Delete to this page. */
  private static SplitPanelHost host(RecurringScheduleForm schedule) {
    Long id = schedule.recurringTemplateId();
    return new SplitPanelHost(
        EDITOR_URL,
        "Start",
        BASE_PATH,
        id == null ? null : BASE_PATH + "/" + id + "/delete",
        "Delete this template? Transactions it already booked stay.",
        TOTALS_HELP);
  }

  private String editorPage(RecurringEditor editor, RegisterView register, Model model) {
    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("register", register);
    model.addAttribute("panel", assembler.panel(editor.split(), null));
    model.addAttribute("template", editor.schedule());
    model.addAttribute("host", host(editor.schedule()));
    model.addAttribute("currencies", currencyService.findAll());
    return "recurring-editor";
  }

  private String panel(SplitForm split, RecurringScheduleForm schedule, String error, Model model) {
    model.addAttribute("register", registerService.datalists());
    model.addAttribute("panel", assembler.panel(split, error));
    model.addAttribute("template", schedule);
    model.addAttribute("host", host(schedule));
    model.addAttribute("currencies", currencyService.findAll());
    return PANEL_FRAGMENT;
  }
}
