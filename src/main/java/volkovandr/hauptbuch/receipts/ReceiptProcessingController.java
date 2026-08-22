package volkovandr.hauptbuch.receipts;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import volkovandr.hauptbuch.ledger.CurrencyService;
import volkovandr.hauptbuch.ledger.RegisterFilter;
import volkovandr.hauptbuch.ledger.RegisterService;
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
  private static final String EDITOR = VIEW + " :: editor";
  private static final String PANE_RESPONSE = VIEW + " :: paneResponse";
  private static final String STATE = "state";
  private static final String STATE_FILTER = "stateFilter";
  private static final String RANGE_FILTER = "rangeFilter";
  private static final String RANGE = "range";
  private static final String REDIRECT_REGISTER = "redirect:" + BASE_PATH;

  /** The default for every boolean request param here — an absent checkbox/button means "no". */
  private static final String FALSE = "false";

  private final ReceiptService receiptService;
  private final ReceiptAnalyser receiptAnalyser;
  private final ReceiptAnalysisService receiptAnalysisService;
  private final ReceiptEditorService receiptEditorService;
  private final ReceiptCommitService receiptCommitService;
  private final RegisterService registerService;
  private final CurrencyService currencyService;

  ReceiptProcessingController(
      ReceiptService receiptService,
      ReceiptAnalyser receiptAnalyser,
      ReceiptAnalysisService receiptAnalysisService,
      ReceiptEditorService receiptEditorService,
      ReceiptCommitService receiptCommitService,
      RegisterService registerService,
      CurrencyService currencyService) {
    this.receiptService = receiptService;
    this.receiptAnalyser = receiptAnalyser;
    this.receiptAnalysisService = receiptAnalysisService;
    this.receiptEditorService = receiptEditorService;
    this.receiptCommitService = receiptCommitService;
    this.registerService = registerService;
    this.currencyService = currencyService;
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
              addChrome(receipt, state, range, model);
              if (ReceiptState.PROCESSED.equals(receipt.state())
                  || ReceiptState.COMMITTED.equals(receipt.state())) {
                addEditor(
                    receipt, receiptEditorService.seed(receipt, receiptService.linesOf(id)), model);
              }
              model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
              model.addAttribute("title", "Receipt · Hauptbuch");
              return VIEW;
            })
        .orElse(REDIRECT_REGISTER);
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
   * Start analysing a {@code pre_processed} receipt (§3.1): claim it (→ {@code processing}) and
   * queue the background parse, then redirect to the screen — which now renders the greyed, polling
   * processing view. A receipt that is not a live {@code pre_processed} one is not claimed; the
   * redirect simply re-renders its current state.
   *
   * <p>{@code cached} is the second Analyse button (9h): it marks the system prompt with a cache
   * breakpoint, which pays off only when more parses follow within the 5-minute TTL. One POST, one
   * boolean — no settings, no schema, no JS.
   */
  @PostMapping("/receipts/{id}/analyse")
  String analyse(
      @PathVariable long id,
      @RequestParam(required = false, defaultValue = FALSE) boolean cached,
      @RequestParam(required = false, defaultValue = ReceiptFilters.STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = ReceiptFilters.RANGE_90D) String range,
      RedirectAttributes redirectAttributes) {
    receiptAnalyser.start(id, cached);
    return redirectToScreen(id, state, range, redirectAttributes);
  }

  /**
   * The status poll target (§3.1): while a receipt is {@code processing}, returns the polling
   * fragment so htmx keeps checking every 2 s; once it lands ({@code processed} or {@code failed}),
   * asks htmx to refresh the whole page (via {@code HX-Refresh}) so the state-appropriate view
   * shows. A vanished receipt also refreshes (back to the register).
   */
  @GetMapping("/receipts/{id}/status")
  String status(
      @PathVariable long id,
      @RequestParam(required = false, defaultValue = ReceiptFilters.STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = ReceiptFilters.RANGE_90D) String range,
      Model model,
      HttpServletResponse response) {
    boolean stillProcessing =
        receiptService
            .findById(id)
            .map(r -> ReceiptState.PROCESSING.equals(r.state()))
            .orElse(false);
    if (stillProcessing) {
      model.addAttribute("id", id);
      model.addAttribute(STATE_FILTER, state);
      model.addAttribute(RANGE_FILTER, range);
      return VIEW + " :: statusPoll";
    }
    response.setHeader("HX-Refresh", "true");
    return VIEW + " :: statusDone";
  }

  /**
   * Retry a {@code failed} receipt (§3.1): back to {@code pre_processed}, where Analyse can be
   * fired again. Redirects to the screen showing the pre-process view.
   */
  @PostMapping("/receipts/{id}/retry")
  String retry(
      @PathVariable long id,
      @RequestParam(required = false, defaultValue = ReceiptFilters.STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = ReceiptFilters.RANGE_90D) String range,
      RedirectAttributes redirectAttributes) {
    receiptAnalysisService.retry(id);
    return redirectToScreen(id, state, range, redirectAttributes);
  }

  /**
   * Re-seed a {@code failed} or {@code processed} receipt from the operator-edited parser response
   * (owner feedback 2026-08-02; widened to {@code processed} by issue tracker
   * receipt-processing/19) — no new API call. When the model returned slightly-malformed TOON,
   * editing the stored text and re-decoding is faster and cheaper than re-prompting; when it
   * returned good text that named a tag/category/person the taxonomy did not have yet, re-seeding
   * applies it now that it does. Redirects to the screen, which shows {@code processed} on success
   * or the {@code failed} view (with the edited text kept) when the edit will not decode.
   */
  @PostMapping("/receipts/{id}/reparse")
  String reparse(
      @PathVariable long id,
      @RequestParam(required = false) String rawText,
      @RequestParam(required = false, defaultValue = ReceiptFilters.STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = ReceiptFilters.RANGE_90D) String range,
      RedirectAttributes redirectAttributes) {
    receiptAnalyser.reparse(id, rawText);
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
    model.addAttribute(STATE_FILTER, state);
    model.addAttribute(RANGE_FILTER, range);
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
      @RequestParam(required = false, defaultValue = FALSE) boolean removeFiles,
      @RequestParam(required = false, defaultValue = ReceiptFilters.STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = ReceiptFilters.RANGE_90D) String range,
      RedirectAttributes redirectAttributes) {
    ReceiptNeighbours neighbours =
        receiptService.neighbours(
            id, ReceiptFilters.statesFor(state), ReceiptFilters.rangeFrom(range));
    receiptService.delete(id, removeFiles);
    return navigateAfterDelete(neighbours, state, range, redirectAttributes);
  }

  /**
   * Land on the next receipt in the filtered list, else the previous, else back to the register.
   */
  private static String navigateAfterDelete(
      ReceiptNeighbours neighbours,
      String state,
      String range,
      RedirectAttributes redirectAttributes) {
    Long landing = landingAfter(neighbours);
    if (landing == null) {
      redirectAttributes.addAttribute(STATE, state);
      redirectAttributes.addAttribute(RANGE, range);
      return REDIRECT_REGISTER;
    }
    return redirectToScreen(landing, state, range, redirectAttributes);
  }

  /** The next receipt in the filtered list, else the previous — the shared fallback ladder. */
  private static Long landingAfter(ReceiptNeighbours neighbours) {
    return neighbours.next() != null ? neighbours.next() : neighbours.prev();
  }

  // ── Post-process editor round-trips (plan §9f) ──────────────────────────────

  /**
   * Add a blank draft line to the editor (§9f) — its amount defaults to "the rest" (total −
   * allocated). Re-renders the whole editor form from the unsaved state; nothing is persisted until
   * Save.
   */
  @PostMapping("/receipts/{id}/lines/add-line")
  String addLine(
      @PathVariable long id,
      @RequestParam MultiValueMap<String, String> params,
      @RequestParam(required = false, defaultValue = ReceiptFilters.STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = ReceiptFilters.RANGE_90D) String range,
      Model model) {
    return renderEditor(
        id, receiptEditorService.addLine(ReceiptEditorForm.bind(params)), state, range, model);
  }

  /**
   * Remove a draft line from the editor (§9f). Re-renders the whole editor from the unsaved state.
   */
  @PostMapping("/receipts/{id}/lines/remove-line")
  String removeLine(
      @PathVariable long id,
      @RequestParam int index,
      @RequestParam MultiValueMap<String, String> params,
      @RequestParam(required = false, defaultValue = ReceiptFilters.STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = ReceiptFilters.RANGE_90D) String range,
      Model model) {
    return renderEditor(
        id,
        receiptEditorService.removeLine(ReceiptEditorForm.bind(params), index),
        state,
        range,
        model);
  }

  /**
   * Redistribute a draft line over the others and drop it (§9f) — the per-line ⇄ action. A refusal
   * (nothing can absorb the amount) re-renders the unchanged editor with the reason shown.
   */
  @PostMapping("/receipts/{id}/lines/redistribute")
  String redistribute(
      @PathVariable long id,
      @RequestParam int index,
      @RequestParam MultiValueMap<String, String> params,
      @RequestParam(required = false, defaultValue = ReceiptFilters.STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = ReceiptFilters.RANGE_90D) String range,
      Model model) {
    ReceiptEditorForm form = ReceiptEditorForm.bind(params);
    try {
      return renderEditor(id, receiptEditorService.redistribute(form, index), state, range, model);
    } catch (LineRedistribution.RedistributeRefusedException e) {
      model.addAttribute("editorError", e.getMessage());
      return renderEditor(id, form, state, range, model);
    }
  }

  /**
   * Save the reviewed draft (§9f): delete-and-reinsert the lines and persist the header. The
   * receipt stays {@code processed} — Save reviews the draft, it does not advance the state ({@code
   * committed} is 9g's Confirm). Re-renders the editor from the freshly persisted state; a receipt
   * that vanished mid-save (a concurrent delete) redirects to the register rather than rendering an
   * editor with no model.
   */
  @PostMapping("/receipts/{id}/lines/save")
  String save(
      @PathVariable long id,
      @RequestParam MultiValueMap<String, String> params,
      @RequestParam(required = false, defaultValue = ReceiptFilters.STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = ReceiptFilters.RANGE_90D) String range,
      Model model) {
    receiptEditorService.save(id, ReceiptEditorForm.bind(params));
    return receiptService
        .findById(id)
        .map(
            receipt -> {
              model.addAttribute("editorSaved", true);
              return renderEditor(
                  id,
                  receiptEditorService.seed(receipt, receiptService.linesOf(id)),
                  state,
                  range,
                  model);
            })
        .orElse(REDIRECT_REGISTER);
  }

  // ── Confirm / reopen / the committed delete (plan §9g) ──────────────────────

  /**
   * Confirm the reviewed draft (§9g): the gate hard-blocks anything the ledger would choke on,
   * otherwise the draft is saved, materialised through the split commit path, and the receipt flips
   * to {@code committed}. On a reopened receipt this is the <em>Re-enter</em>: the previously
   * booked transaction is voided and a new one takes its place.
   *
   * <p>When the active filter still includes {@code committed} (e.g. {@code state=all}), Confirm
   * never navigates — the pane comes back read-only in place (the chrome rides along out-of-band,
   * so the state badge and the Delete rung follow). When the active filter excludes it (the default
   * work queue), the just-committed receipt would otherwise strand Prev/Next dead — it can no
   * longer find itself in that filtered list — so a successful commit instead auto-advances: land
   * on the next receipt in the filter as it stood <em>before</em> the commit, else the previous,
   * else the register, the same fallback ladder the committed-delete flow already uses. That lands
   * via {@code HX-Redirect} so the address bar follows, not an in-place swap. A refused gate
   * re-renders the same pane, still editable, listing every block, and never navigates either way.
   */
  @PostMapping("/receipts/{id}/confirm")
  String confirm(
      @PathVariable long id,
      @RequestParam MultiValueMap<String, String> params,
      @RequestParam(required = false, defaultValue = ReceiptFilters.STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = ReceiptFilters.RANGE_90D) String range,
      Model model,
      HttpServletResponse response) {
    ReceiptEditorForm form = ReceiptEditorForm.bind(params);
    List<String> states = ReceiptFilters.statesFor(state);
    ReceiptNeighbours preCommitNeighbours =
        states.contains(ReceiptState.COMMITTED)
            ? null
            : receiptService.neighbours(id, states, ReceiptFilters.rangeFrom(range));
    try {
      receiptCommitService.confirm(id, form);
    } catch (ReceiptConfirmException e) {
      model.addAttribute("confirmProblems", e.problems());
      return renderPane(id, form, state, range, model);
    }
    if (preCommitNeighbours != null) {
      response.setHeader("HX-Redirect", advanceUrl(preCommitNeighbours, state, range));
    }
    return renderPane(id, null, state, range, model);
  }

  /**
   * The URL to auto-advance to after a commit drops the receipt out of the active filter: the next
   * receipt in the pre-commit list, else the previous, else the register — carrying the same {@code
   * state}/{@code range} filter along.
   */
  private static String advanceUrl(ReceiptNeighbours neighbours, String state, String range) {
    Long landing = landingAfter(neighbours);
    String path = landing == null ? BASE_PATH : BASE_PATH + "/" + landing;
    return UriComponentsBuilder.fromPath(path)
        .queryParam(STATE, state)
        .queryParam(RANGE, range)
        .toUriString();
  }

  /**
   * Reopen a committed receipt (§9g): instant, no dialog — the transaction is untouched and stays
   * linked, so the pane comes back editable with Confirm reading "Re-enter".
   */
  @PostMapping("/receipts/{id}/reopen")
  String reopen(
      @PathVariable long id,
      @RequestParam(required = false, defaultValue = ReceiptFilters.STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = ReceiptFilters.RANGE_90D) String range,
      Model model) {
    receiptCommitService.reopen(id);
    return renderPane(id, null, state, range, model);
  }

  /** The 5-way committed-delete dialog (§9g), rendered into the overlay mount. */
  @GetMapping("/receipts/{id}/delete-committed-confirm")
  String deleteCommittedConfirm(
      @PathVariable long id,
      @RequestParam(required = false, defaultValue = ReceiptFilters.STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = ReceiptFilters.RANGE_90D) String range,
      Model model) {
    model.addAttribute("id", id);
    model.addAttribute(STATE_FILTER, state);
    model.addAttribute(RANGE_FILTER, range);
    model.addAttribute("deleteChoices", CommittedDeleteChoice.ALL);
    return VIEW + " :: deleteCommittedDialog";
  }

  /**
   * Delete a committed receipt through the 5-way dialog (§9g), then navigate on exactly as the
   * non-committed rung does. The receipt keeps its {@code transaction_id} either way; whether the
   * transaction itself is voided is the dialog's first axis.
   */
  @PostMapping("/receipts/{id}/delete-committed")
  String deleteCommitted(
      @PathVariable long id,
      @RequestParam(required = false, defaultValue = FALSE) boolean voidTransaction,
      @RequestParam(required = false, defaultValue = FALSE) boolean removeFiles,
      @RequestParam(required = false, defaultValue = ReceiptFilters.STATE_QUEUE) String state,
      @RequestParam(required = false, defaultValue = ReceiptFilters.RANGE_90D) String range,
      RedirectAttributes redirectAttributes) {
    ReceiptNeighbours neighbours =
        receiptService.neighbours(
            id, ReceiptFilters.statesFor(state), ReceiptFilters.rangeFrom(range));
    receiptCommitService.deleteCommitted(id, voidTransaction, removeFiles);
    return navigateAfterDelete(neighbours, state, range, redirectAttributes);
  }

  // ── Model assembly ──────────────────────────────────────────────────────────

  /**
   * Render the editor fragment from a bound form (the add/remove/redistribute/Save round-trips).
   */
  private String renderEditor(
      long id, ReceiptEditorForm form, String state, String range, Model model) {
    return receiptService
        .findById(id)
        .map(
            receipt -> {
              model.addAttribute(STATE_FILTER, state);
              model.addAttribute(RANGE_FILTER, range);
              addEditor(receipt, form, model);
              return EDITOR;
            })
        .orElse(REDIRECT_REGISTER);
  }

  /**
   * Render the whole pane + the out-of-band chrome (Confirm, Reopen). A null {@code form} re-seeds
   * from what was just persisted — the freshly booked or reopened state; a non-null one keeps the
   * operator's unsaved edits on screen beside the gate's findings.
   */
  private String renderPane(
      long id, ReceiptEditorForm form, String state, String range, Model model) {
    return receiptService
        .findById(id)
        .map(
            receipt -> {
              addChrome(receipt, state, range, model);
              addEditor(
                  receipt,
                  form == null
                      ? receiptEditorService.seed(receipt, receiptService.linesOf(id))
                      : form,
                  model);
              return PANE_RESPONSE;
            })
        .orElse(REDIRECT_REGISTER);
  }

  /** The state-independent chrome: the carried filter and the prev/next neighbours over it. */
  private void addChrome(Receipt receipt, String state, String range, Model model) {
    long id = receipt.receiptId();
    model.addAttribute("id", id);
    model.addAttribute("receipt", receipt);
    model.addAttribute(
        "neighbours",
        receiptService.neighbours(
            id, ReceiptFilters.statesFor(state), ReceiptFilters.rangeFrom(range)));
    model.addAttribute(STATE_FILTER, state);
    model.addAttribute(RANGE_FILTER, range);
  }

  /** Put the assembled editor + the shared register datalists on the model. */
  private void addEditor(Receipt receipt, ReceiptEditorForm form, Model model) {
    boolean committed = ReceiptState.COMMITTED.equals(receipt.state());
    model.addAttribute("id", receipt.receiptId());
    model.addAttribute("receipt", receipt);
    model.addAttribute("editor", receiptEditorService.panel(form));
    model.addAttribute(
        "register", registerService.view(new RegisterFilter(List.of(), null, null, null)));
    // The header currency-picker (a shared fragment) renders its <select> from `currencies`,
    // exactly
    // as the register and settings screens supply it — without it the picker is an empty, unusable
    // <select required> that blocks Save.
    model.addAttribute("currencies", currencyService.findAll());
    // A committed receipt renders the same editor, disabled (§9g); an already-booked but reopened
    // one keeps its transaction link, which is what turns Confirm into Re-enter.
    model.addAttribute("readOnly", committed);
    model.addAttribute("reentry", !committed && receipt.transactionId() != null);
    // Live-checked on every render, never persisted (issue tracker #08): a committed receipt whose
    // transaction was voided from the register gets the pane's dead-link warning instead of a
    // confirmed-dead "Edit transaction" jump.
    model.addAttribute("transactionVoided", committed && receiptService.transactionVoided(receipt));
  }

  /** Redirect back to a receipt's processing screen, preserving the carried filter + order. */
  private static String redirectToScreen(
      long id, String state, String range, RedirectAttributes redirectAttributes) {
    redirectAttributes.addAttribute(STATE, state);
    redirectAttributes.addAttribute(RANGE, range);
    return REDIRECT_REGISTER + "/" + id;
  }
}
