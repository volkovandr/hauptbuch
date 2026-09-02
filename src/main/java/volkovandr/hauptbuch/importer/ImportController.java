package volkovandr.hauptbuch.importer;

import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import volkovandr.hauptbuch.shared.MoneyFormat;
import volkovandr.hauptbuch.web.NavItem;

/**
 * The import campaign screen (import.md §2; plan b2 — upload → preview, nothing staged). Lives in
 * {@code importer}: a feature module owns its own controller (CLAUDE.md §3). Plain server-rendered
 * forms, redirect after POST; no bespoke JS.
 *
 * <p>Between the upload and the "Confirm & stage", the file is held in the HTTP session ({@link
 * ImportUploadSession}) rather than a staging table — the preview is derived from the uploaded
 * bytes plus the owner's charset / date-order choice and recomputed on each render, so nothing has
 * to be unwound if the campaign is discarded. Confirming (b3) stages the file through {@link
 * ImportStagingService} and drops it from the session; a staged file can still be removed from the
 * campaign screen without discarding the whole campaign.
 */
@Controller
class ImportController {

  private static final String BASE = "/import";
  private static final String UPLOAD_PATH = BASE + "/uploads";
  private static final String SCREEN_VIEW = "import";
  private static final String PREVIEW_VIEW = "import-preview";
  private static final String REVIEW_VIEW = "import-review";
  private static final String REDIRECT_SCREEN = "redirect:" + BASE;
  private static final String REDIRECT_REVIEW = "redirect:" + BASE + "/review";
  private static final String RESOLUTION_REPLACE = "replace";
  private static final String RESOLUTION_COINCIDENCE = "coincidence";
  private static final String ERROR = "error";

  private final ImportSessionService importSessionService;
  private final ImportPreviewService importPreviewService;
  private final ImportStagingService importStagingService;
  private final ImportReviewService importReviewService;
  private final ImportAccountMapService importAccountMapService;

  ImportController(
      ImportSessionService importSessionService,
      ImportPreviewService importPreviewService,
      ImportStagingService importStagingService,
      ImportReviewService importReviewService,
      ImportAccountMapService importAccountMapService) {
    this.importSessionService = importSessionService;
    this.importPreviewService = importPreviewService;
    this.importStagingService = importStagingService;
    this.importReviewService = importReviewService;
    this.importAccountMapService = importAccountMapService;
  }

  /** The screen: the open campaign (or the button to start one) and the pending uploads. */
  @GetMapping(BASE)
  String screen(HttpSession httpSession, Model model) {
    ImportUploadSession uploads = uploadSession(httpSession);
    model.addAttribute("campaign", importSessionService.currentSession().orElse(null));
    model.addAttribute("uploads", uploads.pending().stream().map(ImportUploadView::of).toList());
    model.addAttribute("clash", uploads.clash().map(ImportUploadView::of).orElse(null));
    model.addAttribute("files", importStagingService.stagedFiles());
    model.addAttribute("nav", NavItem.sectionsFor(BASE));
    model.addAttribute("title", "Import · Hauptbuch");
    return SCREEN_VIEW;
  }

  /**
   * The review page (import.md §9; plan e′): the per-account statistics that get ticked against
   * Money's own balances. Born as a skeleton — slices c/d/e add the map and issues panels. No open
   * campaign ⇒ back to the screen.
   */
  @GetMapping(BASE + "/review")
  String review(Model model) {
    Optional<ImportReview> review = importReviewService.review();
    if (review.isEmpty()) {
      return REDIRECT_SCREEN;
    }
    model.addAttribute("review", review.get());
    // The shared currency-picker fragment (account-map panel, plan c1) reads ${currencies}.
    model.addAttribute("currencies", review.get().accountMap().currencyOptions());
    model.addAttribute("nav", NavItem.sectionsFor(BASE));
    model.addAttribute("title", "Import review · Hauptbuch");
    return REVIEW_VIEW;
  }

  /**
   * Map one Money account name (import.md §5.1; plan c1). A chosen {@code accountId} maps to that
   * existing account; otherwise a new account is opened from {@code newName} / {@code type} /
   * {@code currencyCode} and the row maps to it. The map is many-to-one — pointing several rows at
   * one account is how a merge and the junk-account cleanup are done. A rejected choice comes back
   * to the review with the reason.
   */
  @PostMapping(BASE + "/review/accounts/{importAccountId}/map")
  String mapAccount(
      @PathVariable long importAccountId,
      @RequestParam(required = false) String accountId,
      @RequestParam(required = false) String newName,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String currencyCode,
      RedirectAttributes redirectAttributes) {
    try {
      String existing = blankToNull(accountId);
      if (existing != null) {
        importAccountMapService.mapToExisting(importAccountId, Long.parseLong(existing));
      } else {
        importAccountMapService.mapToNew(
            importAccountId, blankToNull(newName), blankToNull(type), blankToNull(currencyCode));
      }
    } catch (IllegalArgumentException | IllegalStateException rejected) {
      redirectAttributes.addFlashAttribute(ERROR, rejected.getMessage());
    }
    return reviewAnchoredAt(importAccountId);
  }

  /**
   * Map one Money account name to a person (import.md §5.4; plan c2). A chosen {@code personId}
   * targets that existing person; otherwise a new person is created from {@code newPersonName}. The
   * {@code currencyCode} selects the person's per-currency leaf. A rejected choice comes back to
   * the review with the reason.
   */
  @PostMapping(BASE + "/review/accounts/{importAccountId}/map-person")
  String mapAccountToPerson(
      @PathVariable long importAccountId,
      @RequestParam(required = false) String personId,
      @RequestParam(required = false) String newPersonName,
      @RequestParam(required = false) String currencyCode,
      RedirectAttributes redirectAttributes) {
    try {
      String existing = blankToNull(personId);
      importAccountMapService.mapToPerson(
          importAccountId,
          existing == null ? null : Long.parseLong(existing),
          blankToNull(newPersonName),
          blankToNull(currencyCode));
    } catch (IllegalArgumentException | IllegalStateException rejected) {
      redirectAttributes.addFlashAttribute(ERROR, rejected.getMessage());
    }
    return reviewAnchoredAt(importAccountId);
  }

  /**
   * Flip the {@code expect-file} flag on one Money account (import.md §5.1; plan c2) — "am I still
   * waiting for this account's own export?". The commit gate stays locked while any account still
   * expects a file (§9).
   */
  @PostMapping(BASE + "/review/accounts/{importAccountId}/expect-file")
  String updateExpectFile(
      @PathVariable long importAccountId,
      @RequestParam boolean expectFile,
      RedirectAttributes redirectAttributes) {
    try {
      importAccountMapService.setExpectFile(importAccountId, expectFile);
    } catch (IllegalArgumentException | IllegalStateException rejected) {
      redirectAttributes.addFlashAttribute(ERROR, rejected.getMessage());
    }
    return reviewAnchoredAt(importAccountId);
  }

  /**
   * Record the opening-balance reconciliation for one Money account (import.md §5.1; plan c3) —
   * {@code keep_hauptbuch} keeps the target account's own, {@code take_money} takes Money's staged
   * one (voiding Hauptbuch's at commit), {@code override} books the typed {@code amount}
   * (German-formatted). Only the decision is recorded; the commit acts on it. A rejected choice
   * comes back to the review with the reason.
   */
  @PostMapping(BASE + "/review/accounts/{importAccountId}/opening-balance")
  String reconcileOpeningBalance(
      @PathVariable long importAccountId,
      @RequestParam String choice,
      @RequestParam(required = false) String amount,
      RedirectAttributes redirectAttributes) {
    try {
      String typed = blankToNull(amount);
      importAccountMapService.reconcileOpeningBalance(
          importAccountId, choice, typed == null ? null : MoneyFormat.parse(typed));
    } catch (IllegalArgumentException | IllegalStateException rejected) {
      redirectAttributes.addFlashAttribute(ERROR, rejected.getMessage());
    }
    return reviewAnchoredAt(importAccountId);
  }

  /**
   * The review redirect with a {@code #account-<id>} fragment: after a map / toggle the browser
   * lands back on that row (now collapsed, since it is mapped) instead of the top of a long page.
   */
  private static String reviewAnchoredAt(long importAccountId) {
    return REDIRECT_REVIEW + "#account-" + importAccountId;
  }

  /** Open the campaign — one open session at a time (import.md §2). */
  @PostMapping(BASE + "/session")
  String openSession(HttpSession httpSession) {
    importSessionService.openSession();
    httpSession.removeAttribute(ImportUploadSession.ATTRIBUTE);
    return REDIRECT_SCREEN;
  }

  /** Discard the campaign (import.md §2) — the feature's only "undo". */
  @PostMapping(BASE + "/session/discard")
  String discardSession(HttpSession httpSession) {
    importSessionService.discardSession();
    httpSession.removeAttribute(ImportUploadSession.ATTRIBUTE);
    return REDIRECT_SCREEN;
  }

  /**
   * Receive a file and route to its preview — or, if the filename matches one already uploaded this
   * session, to the replacement-or-coincidence choice (§2). Which Money account the file is for
   * (§4.1) is deduced here from its opening-balance record (§5.1) and confirmed on the preview;
   * nothing is staged.
   */
  @PostMapping(UPLOAD_PATH)
  String upload(
      @RequestParam("file") MultipartFile file,
      HttpSession httpSession,
      RedirectAttributes redirectAttributes) {
    if (importSessionService.currentSession().isEmpty()) {
      redirectAttributes.addFlashAttribute(
          ERROR, "Start an import session before uploading a file.");
      return REDIRECT_SCREEN;
    }
    byte[] bytes;
    try {
      bytes = bytesOf(file);
    } catch (IllegalArgumentException rejected) {
      redirectAttributes.addFlashAttribute(ERROR, rejected.getMessage());
      return REDIRECT_SCREEN;
    }
    PendingImportUpload pending =
        PendingImportUpload.of(UUID.randomUUID().toString(), filenameOf(file), bytes);
    pending =
        importPreviewService
            .deduceAccountName(pending)
            .map(pending::withDeducedAccountName)
            .orElse(pending);
    ImportUploadSession uploads = uploadSession(httpSession);
    // A name match against a pending upload OR an already-staged file is parked, never assumed —
    // Money reuses one filename across every export (import.md §2).
    if (uploads.hasFilename(pending.sourceFilename())
        || importStagingService.hasStagedFile(pending.sourceFilename())) {
      uploads.parkClash(pending);
      return REDIRECT_SCREEN;
    }
    uploads.add(pending);
    return previewRedirect(pending.token());
  }

  /**
   * Resolve a parked filename clash (§2): {@code replace} discards the same-named upload first,
   * {@code coincidence} keeps both, {@code cancel} drops the new one.
   */
  @PostMapping(UPLOAD_PATH + "/clash")
  String resolveClash(@RequestParam String resolution, HttpSession httpSession) {
    ImportUploadSession uploads = uploadSession(httpSession);
    if (uploads.clash().isEmpty()) {
      return REDIRECT_SCREEN;
    }
    // Only the two explicit "keep it" choices act; cancel — and any unrecognised value — drops it.
    if (RESOLUTION_REPLACE.equals(resolution) || RESOLUTION_COINCIDENCE.equals(resolution)) {
      boolean replace = RESOLUTION_REPLACE.equals(resolution);
      String filename = uploads.clash().orElseThrow().sourceFilename();
      String token = uploads.resolveClash(replace);
      if (replace) {
        // "Replace" also drops any already-staged file of that name — b3's replacement is that
        // removal followed by staging the new file (import.md §2), not a separate mechanism.
        importStagingService.removeFilesNamed(filename);
      }
      return previewRedirect(token);
    }
    uploads.clearClash();
    return REDIRECT_SCREEN;
  }

  /** The preview for one pending upload (import.md §4.3/§4.4). */
  @GetMapping(UPLOAD_PATH + "/{token}")
  String preview(@PathVariable String token, HttpSession httpSession, Model model) {
    Optional<PendingImportUpload> pending = uploadSession(httpSession).findByToken(token);
    if (pending.isEmpty()) {
      return REDIRECT_SCREEN;
    }
    PendingImportUpload upload = pending.get();
    model.addAttribute("upload", ImportUploadView.of(upload));
    model.addAttribute("preview", importPreviewService.preview(upload));
    model.addAttribute("nav", NavItem.sectionsFor(BASE));
    model.addAttribute("title", upload.sourceFilename() + " · Import · Hauptbuch");
    return PREVIEW_VIEW;
  }

  /**
   * Confirm or override the preview: the detected charset / date order (import.md §4.3 — a
   * day/month swap corrupts the whole campaign, so it is confirmed, never assumed) and which Money
   * account the file is for (§4.1). A blank charset / date order follows detection; a blank account
   * name keeps the one deduced from the file (§5.1).
   */
  @PostMapping(UPLOAD_PATH + "/{token}")
  String override(
      @PathVariable String token,
      @RequestParam(required = false) String charset,
      @RequestParam(required = false) String dateOrder,
      @RequestParam(required = false) String moneyAccountName,
      HttpSession httpSession) {
    uploadSession(httpSession)
        .updateChoice(token, blankToNull(charset), blankToNull(dateOrder), moneyAccountName);
    return previewRedirect(token);
  }

  /** Drop a pending upload before it is staged. */
  @PostMapping(UPLOAD_PATH + "/{token}/remove")
  String remove(@PathVariable String token, HttpSession httpSession) {
    uploadSession(httpSession).removeByToken(token);
    return REDIRECT_SCREEN;
  }

  /**
   * Confirm the preview and stage the file (import.md §11; plan b3): writes {@code import_file} +
   * {@code import_transaction} + {@code import_posting} and folds the file's account names and
   * category paths into the maps as unmapped rows. The pending upload is then dropped from the
   * session. A file the parser refuses, or one whose date order is still ambiguous, comes back to
   * the preview with the reason; a campaign discarded meanwhile sends the owner back to the screen.
   */
  @PostMapping(UPLOAD_PATH + "/{token}/stage")
  String stage(
      @PathVariable String token, HttpSession httpSession, RedirectAttributes redirectAttributes) {
    if (importSessionService.currentSession().isEmpty()) {
      redirectAttributes.addFlashAttribute(ERROR, "Start an import session before staging a file.");
      return REDIRECT_SCREEN;
    }
    Optional<PendingImportUpload> pending = uploadSession(httpSession).findByToken(token);
    if (pending.isEmpty()) {
      return REDIRECT_SCREEN;
    }
    try {
      ImportFile staged = importStagingService.stage(pending.get());
      uploadSession(httpSession).removeByToken(token);
      redirectAttributes.addFlashAttribute(
          "staged",
          "Staged "
              + staged.sourceFilename()
              + " for "
              + staged.moneyAccountName()
              + " — "
              + staged.transactionCount()
              + " transactions.");
      return REDIRECT_SCREEN;
    } catch (QifRejectedException rejected) {
      redirectAttributes.addFlashAttribute(ERROR, rejected.getMessage());
      return previewRedirect(token);
    }
  }

  /**
   * Remove a staged file and everything it staged (plan b3) — recovers a mis-stated account or a
   * wrong date order without discarding the whole campaign.
   */
  @PostMapping(BASE + "/files/{importFileId}/remove")
  String removeFile(@PathVariable long importFileId) {
    importStagingService.removeFile(importFileId);
    return REDIRECT_SCREEN;
  }

  private static String previewRedirect(String token) {
    return "redirect:" + UPLOAD_PATH + "/" + token;
  }

  private static ImportUploadSession uploadSession(HttpSession httpSession) {
    ImportUploadSession existing =
        (ImportUploadSession) httpSession.getAttribute(ImportUploadSession.ATTRIBUTE);
    if (existing == null) {
      existing = new ImportUploadSession();
      httpSession.setAttribute(ImportUploadSession.ATTRIBUTE, existing);
    }
    return existing;
  }

  private static byte[] bytesOf(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException(
          "No file was attached — choose a Money QIF export and try again.");
    }
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read the uploaded file", e);
    }
  }

  private static String filenameOf(MultipartFile file) {
    String original = file.getOriginalFilename();
    return original == null || original.isBlank() ? "export.qif" : original;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
