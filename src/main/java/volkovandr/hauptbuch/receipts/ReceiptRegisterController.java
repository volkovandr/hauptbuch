package volkovandr.hauptbuch.receipts;

import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import volkovandr.hauptbuch.web.NavItem;

/**
 * The PC receipt register (§5, plan stage 9b): the dense list of receipts with a state filter
 * (default work queue), a capture-date-range filter (incl. an "everything" option), captured
 * ascending. The parsed columns render blank until 9e — a stable layout now, no template churn
 * later. Selection + the right-click context menu (§5.2) drive the delete ladder.
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

  private final ReceiptService receiptService;
  private final ReceiptBatchAnalyser receiptBatchAnalyser;

  ReceiptRegisterController(
      ReceiptService receiptService, ReceiptBatchAnalyser receiptBatchAnalyser) {
    this.receiptService = receiptService;
    this.receiptBatchAnalyser = receiptBatchAnalyser;
  }

  /** The register page, filtered by state and capture-date range. */
  @GetMapping(BASE_PATH)
  String register(
      @RequestParam(required = false, defaultValue = ReceiptFilters.STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = ReceiptFilters.RANGE_90D) String range,
      Model model) {
    populateList(model, state, range);
    model.addAttribute("states", ReceiptState.ALL);
    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("title", "Receipts · Hauptbuch");
    return VIEW;
  }

  /**
   * Upload a scan from the PC (source = pc): store it, then redirect back to the register (PRG). A
   * bad format / oversize upload redirects with the rejection message shown on the register.
   */
  @PostMapping("/receipts/upload")
  String upload(@RequestParam("image") MultipartFile image, RedirectAttributes redirectAttributes) {
    try {
      receiptService.capture(ReceiptUploads.bytesOf(image), ReceiptService.SOURCE_PC);
    } catch (ReceiptFormatException e) {
      redirectAttributes.addFlashAttribute("uploadError", e.getMessage());
    }
    return "redirect:" + BASE_PATH;
  }

  /**
   * The right-click context menu for a selection (§5.2): a server-rendered fragment listing the
   * actions valid for the selected receipts, with skip counts — View image, Process (the 9h batch),
   * and Delete.
   */
  @GetMapping("/receipts/menu")
  String menu(@RequestParam(name = "id", required = false) List<Long> ids, Model model) {
    populateSelection(model, ids);
    return "receipts :: menu";
  }

  /**
   * The keep-files / delete-files dialog (§5.2) for any non-committed selection — {@code new}
   * included on PC (2026-07-31) — a three-way choice htmx's {@code hx-confirm} can't express.
   */
  @GetMapping("/receipts/delete-dialog")
  String deleteDialog(@RequestParam(name = "id", required = false) List<Long> ids, Model model) {
    populateSelection(model, ids);
    return "receipts :: deleteDialog";
  }

  /**
   * Send a selection to the AI as one batch (§3.2, 9h): every {@code pre_processed} member is
   * claimed and queued at half price, members in any other state are skipped. Returns as soon as
   * the claim is done — the submit itself runs in the background — and re-renders the list, where
   * the claimed rows now read {@code Processing}.
   */
  @PostMapping("/receipts/process")
  String process(
      @RequestParam(name = "id", required = false) List<Long> ids,
      @RequestParam(required = false, defaultValue = ReceiptFilters.STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = ReceiptFilters.RANGE_90D) String range,
      Model model) {
    receiptBatchAnalyser.start(nullSafe(ids));
    populateList(model, state, range);
    return LIST_FRAGMENT;
  }

  /**
   * The list poll target (issue tracker #03): while any row shown is {@code processing}, {@code
   * receipts.html}'s {@code listPoll} fragment rechecks the watched ids every 10 s. Nothing watched
   * has left {@code processing} ⇒ hand back the very same trigger, unmoved — {@code #receipt-list}
   * is never touched on a tick where nothing changed, so the owner's row selection survives.
   * Something did (finished, failed, or was deleted mid-flight) ⇒ refresh {@code #receipt-list}
   * out-of-band for the current filter; the fresh render embeds its own new trigger if anything is
   * still in flight, or none at all once the queue has drained.
   */
  @GetMapping("/receipts/status")
  String listStatus(
      @RequestParam(name = "id", required = false) List<Long> ids,
      @RequestParam(required = false, defaultValue = ReceiptFilters.STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = ReceiptFilters.RANGE_90D) String range,
      Model model) {
    List<Long> watched = nullSafe(ids);
    List<Long> stillProcessing = receiptService.stillProcessing(watched);
    if (stillProcessing.size() == watched.size()) {
      model.addAttribute("processingIds", stillProcessing);
      return "receipts :: listPoll";
    }
    populateList(model, state, range);
    return "receipts :: listChanged";
  }

  /** Delete a selection through the ladder; committed members are skipped. Re-renders the list. */
  @PostMapping("/receipts/delete")
  String delete(
      @RequestParam(name = "id", required = false) List<Long> ids,
      @RequestParam(required = false, defaultValue = "false") boolean removeFiles,
      @RequestParam(required = false, defaultValue = ReceiptFilters.STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = ReceiptFilters.RANGE_90D) String range,
      Model model) {
    receiptService.deleteSelection(nullSafe(ids), removeFiles);
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
   * {@code processingIds} rides along so {@code listPoll} (issue tracker #03) knows what to watch.
   */
  private void populateList(Model model, String state, String range) {
    List<Receipt> receipts =
        receiptService.forRegister(
            ReceiptFilters.statesFor(state), ReceiptFilters.rangeFrom(range));
    model.addAttribute("receipts", receipts);
    model.addAttribute("processingIds", processingIdsOf(receipts));
    model.addAttribute("stateFilter", state);
    model.addAttribute("rangeFilter", range);
  }

  private static List<Long> processingIdsOf(List<Receipt> receipts) {
    return receipts.stream()
        .filter(r -> ReceiptState.PROCESSING.equals(r.state()))
        .map(Receipt::receiptId)
        .toList();
  }
}
