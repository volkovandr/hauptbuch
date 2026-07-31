package volkovandr.hauptbuch.receipts;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import volkovandr.hauptbuch.web.NavItem;

/**
 * The PC processing screen (receipt doc §6): the per-receipt detail surface, at its own URL in the
 * same tab, carrying the register's current filter + order. It renders <em>one view per state</em>
 * (§2.2) — {@code new} and {@code pre_processed} are the pre-process views this slice (9c) fills
 * in; later states get their views in 9e–9g. Every state transition is an explicit, named action;
 * nothing fires automatically.
 *
 * <p>The image editing itself is 100% client-side (the Cropper.js leaf, tech-stack §5) — this
 * controller only serves the views and persists the results: Save (the baked edited image + AI note
 * + edit recipe), Discard edits (the stage-undo), and Delete (the 3-way dialog, then navigate to
 * the next receipt). Lives in {@code receipts}: the feature module owns its screens (CLAUDE.md §3).
 */
@Controller
class ReceiptProcessingController {

  private static final String BASE_PATH = "/receipts";
  private static final String VIEW = "receipt-process";
  private static final String STATE = "state";
  private static final String RANGE = "range";

  private final ReceiptService receiptService;

  ReceiptProcessingController(ReceiptService receiptService) {
    this.receiptService = receiptService;
  }

  /**
   * The processing screen for one receipt, in the state-appropriate view, with prev/next resolved
   * over the carried filter. A missing (or soft-deleted) receipt falls back to the register.
   */
  @GetMapping("/receipts/{id}")
  String screen(
      @PathVariable long id,
      @RequestParam(required = false, defaultValue = ReceiptFilters.STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = ReceiptFilters.RANGE_90D) String range,
      Model model) {
    return receiptService
        .findById(id)
        .map(
            receipt -> {
              model.addAttribute("receipt", receipt);
              model.addAttribute(
                  "neighbours",
                  receiptService.neighbours(
                      id, ReceiptFilters.statesFor(state), ReceiptFilters.rangeFrom(range)));
              model.addAttribute("stateFilter", state);
              model.addAttribute("rangeFilter", range);
              model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
              model.addAttribute("title", "Receipt · Hauptbuch");
              return VIEW;
            })
        .orElse("redirect:" + BASE_PATH);
  }

  /**
   * Save the pre-process edit: store the client-baked edited image, its recipe, and the AI note,
   * and move to {@code pre_processed} (§6.1). A bad edited upload redirects back with the message
   * shown.
   */
  @PostMapping("/receipts/{id}/pre-process")
  String preProcess(
      @PathVariable long id,
      @RequestParam("image") MultipartFile image,
      @RequestParam(name = "editRecipe", required = false) String editRecipe,
      @RequestParam(name = "aiNote", required = false) String aiNote,
      @RequestParam(required = false, defaultValue = ReceiptFilters.STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = ReceiptFilters.RANGE_90D) String range,
      RedirectAttributes redirectAttributes) {
    try {
      receiptService.preProcess(id, ReceiptUploads.bytesOf(image), editRecipe, aiNote);
    } catch (ReceiptFormatException e) {
      redirectAttributes.addFlashAttribute("editError", e.getMessage());
    }
    return redirectToScreen(id, state, range, redirectAttributes);
  }

  /**
   * Discard the pre-process edits (the stage-undo, §6.1): remove the edited image + recipe, keep
   * the AI note, back to {@code new}. Redirects to the (now {@code new}) processing screen.
   */
  @PostMapping("/receipts/{id}/discard-edits")
  String discardEdits(
      @PathVariable long id,
      @RequestParam(required = false, defaultValue = ReceiptFilters.STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = ReceiptFilters.RANGE_90D) String range,
      RedirectAttributes redirectAttributes) {
    receiptService.discardEdits(id);
    return redirectToScreen(id, state, range, redirectAttributes);
  }

  /**
   * The 3-way keep/delete-files dialog for this one receipt (§6), rendered into the overlay mount.
   */
  @GetMapping("/receipts/{id}/delete-confirm")
  String deleteConfirm(
      @PathVariable long id,
      @RequestParam(required = false, defaultValue = ReceiptFilters.STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = ReceiptFilters.RANGE_90D) String range,
      Model model) {
    model.addAttribute("id", id);
    model.addAttribute("stateFilter", state);
    model.addAttribute("rangeFilter", range);
    return VIEW + " :: deleteDialog";
  }

  /**
   * Delete this receipt through the ladder, then navigate on (§6): land on the next receipt in the
   * filtered list, else the previous, else back to the register. Neighbours are resolved
   * <em>before</em> the delete, while this receipt is still in the list.
   */
  @PostMapping("/receipts/{id}/delete")
  String delete(
      @PathVariable long id,
      @RequestParam(required = false, defaultValue = "false") boolean removeFiles,
      @RequestParam(required = false, defaultValue = ReceiptFilters.STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = ReceiptFilters.RANGE_90D) String range,
      RedirectAttributes redirectAttributes) {
    ReceiptNeighbours neighbours =
        receiptService.neighbours(
            id, ReceiptFilters.statesFor(state), ReceiptFilters.rangeFrom(range));
    receiptService.delete(id, removeFiles);

    Long landing = neighbours.next() != null ? neighbours.next() : neighbours.prev();
    if (landing == null) {
      redirectAttributes.addAttribute(STATE, state);
      redirectAttributes.addAttribute(RANGE, range);
      return "redirect:" + BASE_PATH;
    }
    return redirectToScreen(landing, state, range, redirectAttributes);
  }

  /** Redirect back to a receipt's processing screen, preserving the carried filter + order. */
  private static String redirectToScreen(
      long id, String state, String range, RedirectAttributes redirectAttributes) {
    redirectAttributes.addAttribute(STATE, state);
    redirectAttributes.addAttribute(RANGE, range);
    return "redirect:" + BASE_PATH + "/" + id;
  }
}
