package volkovandr.hauptbuch.recurring;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
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
  private static final String TOTALS_HELP =
      "Each occurrence proposes these totals from the latest rate on or before its date. They are"
          + " not stored with the template.";

  private final RecurringTemplateService templateService;
  private final RecurringTemplateViews views;
  private final RegisterService registerService;
  private final CurrencyService currencyService;
  private final SplitPanelAssembler assembler;
  private final SplitCurrencyService splitCurrencyService;

  RecurringController(
      RecurringTemplateService templateService,
      RecurringTemplateViews views,
      RegisterService registerService,
      CurrencyService currencyService,
      SplitPanelAssembler assembler,
      SplitCurrencyService splitCurrencyService) {
    this.templateService = templateService;
    this.views = views;
    this.registerService = registerService;
    this.currencyService = currencyService;
    this.assembler = assembler;
    this.splitCurrencyService = splitCurrencyService;
  }

  /** The recurring page: the live templates with their cadence and next three dates. */
  @GetMapping(BASE_PATH)
  String list(Model model) {
    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("templates", views.rows());
    return "recurring";
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
   * Recompute the currency layout after the account, currency or a total changed, proposing any
   * blank cross-currency total from the rate on the start date (register §3.8a).
   */
  @PostMapping(EDITOR_URL + "/currency")
  String currency(@RequestParam MultiValueMap<String, String> params, Model model) {
    SplitForm proposed = splitCurrencyService.withProposedTotals(SplitFormBinder.bind(params));
    return panel(proposed, RecurringScheduleForm.bind(params), null, model);
  }

  /**
   * Save the template and go back to the recurring page. A refused save re-renders the panel with
   * the message, keeping everything typed.
   */
  @PostMapping(EDITOR_URL + "/save")
  String save(
      @RequestParam MultiValueMap<String, String> params,
      Model model,
      HttpServletResponse response) {
    SplitForm split = SplitFormBinder.bind(params);
    RecurringScheduleForm schedule = RecurringScheduleForm.bind(params);
    try {
      templateService.save(schedule, split);
    } catch (IllegalArgumentException | IllegalStateException | UnbalancedTransactionException e) {
      return panel(split, schedule, e.getMessage(), model);
    }
    response.setHeader("HX-Redirect", BASE_PATH);
    return panel(split, schedule, null, model);
  }

  /** Soft-delete a template and go back to the recurring page. */
  @PostMapping(BASE_PATH + "/{id}/delete")
  @ResponseBody
  String delete(@PathVariable long id, HttpServletResponse response) {
    templateService.delete(id);
    response.setHeader("HX-Redirect", BASE_PATH);
    return "";
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
