package volkovandr.hauptbuch.receipts;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.web.NavItem;

/**
 * The PC receipt register (§5, plan stage 9b): the dense list of receipts with a state filter
 * (default work queue), a capture-date-range filter (incl. an "everything" option), captured
 * ascending. The parsed columns render blank until 9e — a stable layout now, no template churn
 * later. Selection + the right-click context menu (§5.2) drive the delete ladder and discard.
 *
 * <p>Lives in {@code receipts}: the feature module owns its screen (CLAUDE.md §3). Actions
 * re-render the list fragment in place (htmx); no full navigation, so the selection surface stays
 * put.
 */
@Controller
class ReceiptRegisterController {

  private static final String BASE_PATH = "/receipts";
  private static final String VIEW = "receipts";
  private static final String LIST_FRAGMENT = "receipts :: list(receipts=${receipts})";

  private static final String STATE_QUEUE = "queue";
  private static final String STATE_ALL = "all";
  private static final String RANGE_90D = "d90";
  private static final String RANGE_1Y = "y1";
  private static final String RANGE_ALL = "all";

  private final ReceiptService receiptService;

  ReceiptRegisterController(ReceiptService receiptService) {
    this.receiptService = receiptService;
  }

  /** The register page, filtered by state and capture-date range. */
  @GetMapping(BASE_PATH)
  String register(
      @RequestParam(required = false, defaultValue = STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = RANGE_90D) String range,
      Model model) {
    populateList(model, state, range);
    model.addAttribute("states", ReceiptState.ALL);
    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("title", "Receipts · Hauptbuch");
    return VIEW;
  }

  /**
   * The right-click context menu for a selection (§5.2): a server-rendered fragment listing the
   * actions valid for the selected receipts, with skip counts. 9e/9h add Process/Re-analyse rows.
   */
  @GetMapping("/receipts/menu")
  String menu(@RequestParam(name = "id", required = false) List<Long> ids, Model model) {
    populateSelection(model, ids);
    return "receipts :: menu";
  }

  /**
   * The keep-files / delete-files dialog (the middle rung of the ladder, §9b) for a non-committed,
   * non-{@code new} selection — a three-way choice htmx's {@code hx-confirm} can't express.
   */
  @GetMapping("/receipts/delete-dialog")
  String deleteDialog(@RequestParam(name = "id", required = false) List<Long> ids, Model model) {
    populateSelection(model, ids);
    return "receipts :: deleteDialog";
  }

  /** Delete a selection through the ladder; committed members are skipped. Re-renders the list. */
  @PostMapping("/receipts/delete")
  String delete(
      @RequestParam(name = "id", required = false) List<Long> ids,
      @RequestParam(required = false, defaultValue = "false") boolean removeFiles,
      @RequestParam(required = false, defaultValue = STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = RANGE_90D) String range,
      Model model) {
    receiptService.deleteSelection(nullSafe(ids), removeFiles);
    populateList(model, state, range);
    return LIST_FRAGMENT;
  }

  /** Discard a selection (non-committed members); re-renders the list. */
  @PostMapping("/receipts/discard")
  String discard(
      @RequestParam(name = "id", required = false) List<Long> ids,
      @RequestParam(required = false, defaultValue = STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = RANGE_90D) String range,
      Model model) {
    receiptService.discardSelection(nullSafe(ids));
    populateList(model, state, range);
    return LIST_FRAGMENT;
  }

  /**
   * Put a selection and its computed context-menu actions into the model (menu + dialog share it).
   */
  private void populateSelection(Model model, List<Long> ids) {
    List<Long> selection = nullSafe(ids);
    model.addAttribute("ids", selection);
    model.addAttribute("menu", receiptService.menuFor(selection));
  }

  private static List<Long> nullSafe(List<Long> ids) {
    return ids == null ? List.of() : ids;
  }

  /**
   * Resolve the filter and load the list rows into the model (shared by the page and re-renders).
   */
  private void populateList(Model model, String state, String range) {
    model.addAttribute("receipts", receiptService.forRegister(statesFor(state), rangeFrom(range)));
    model.addAttribute("stateFilter", state);
    model.addAttribute("rangeFilter", range);
  }

  /** The state filter's state set: the work queue, everything, or a single named state. */
  private static List<String> statesFor(String state) {
    if (STATE_ALL.equals(state)) {
      return ReceiptState.ALL;
    }
    if (ReceiptState.isValid(state)) {
      return List.of(state);
    }
    return ReceiptState.WORK_QUEUE;
  }

  /** The date-range filter's lower bound: last 90 days (default), last year, or unbounded. */
  private static LocalDate rangeFrom(String range) {
    return switch (range) {
      case RANGE_ALL -> null;
      case RANGE_1Y -> LocalDate.now().minusYears(1);
      default -> LocalDate.now().minusDays(90);
    };
  }
}
