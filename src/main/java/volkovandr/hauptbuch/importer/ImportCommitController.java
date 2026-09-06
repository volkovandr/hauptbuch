package volkovandr.hauptbuch.importer;

import java.util.Optional;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import volkovandr.hauptbuch.backup.BackupFailedException;
import volkovandr.hauptbuch.backup.BackupKind;
import volkovandr.hauptbuch.backup.BackupService;
import volkovandr.hauptbuch.web.NavItem;

/**
 * The import commit screen (import.md §2, §10; plan f2b) — the {@code backup → commit → backup}
 * ceremony and a background worker the page polls for progress. Lives in {@code importer} (a
 * feature module owns its controller, CLAUDE.md §3), separate from the already-large {@link
 * ImportController}. Server-rendered; the only JS is the app-wide htmx poll.
 */
@Controller
class ImportCommitController {

  private static final String BASE = "/import/commit";
  private static final String VIEW = "import-commit";
  private static final String REDIRECT_SCREEN = "redirect:/import";
  private static final String REDIRECT_COMMIT = "redirect:" + BASE;

  private final ImportSessionService importSessionService;
  private final ImportCommitScreen importCommitScreen;
  private final ImportCommitWorker importCommitWorker;
  private final BackupService backupService;

  ImportCommitController(
      ImportSessionService importSessionService,
      ImportCommitScreen importCommitScreen,
      ImportCommitWorker importCommitWorker,
      BackupService backupService) {
    this.importSessionService = importSessionService;
    this.importCommitScreen = importCommitScreen;
    this.importCommitWorker = importCommitWorker;
    this.backupService = backupService;
  }

  /** The commit screen — the ceremony, the gate, and the progress area. */
  @GetMapping(BASE)
  String screen(Model model) {
    Optional<ImportCommitView> view = importCommitScreen.forOpenSession();
    if (view.isEmpty()) {
      return REDIRECT_SCREEN;
    }
    model.addAttribute("commit", view.get());
    model.addAttribute("progress", view.get().progress());
    model.addAttribute("nav", NavItem.sectionsFor("/import"));
    model.addAttribute("title", "Commit the import · Hauptbuch");
    return VIEW;
  }

  /** Take the pre-commit safety backup (import.md §2) — the first half of the ceremony. */
  @PostMapping(BASE + "/backup")
  String takeSafetyBackup(RedirectAttributes redirectAttributes) {
    try {
      redirectAttributes.addFlashAttribute(
          "backup",
          "Safety backup taken: " + backupService.take(BackupKind.MANUAL).fileName() + ".");
    } catch (BackupFailedException e) {
      redirectAttributes.addFlashAttribute("error", "The backup failed: " + e.getMessage());
    }
    return REDIRECT_COMMIT;
  }

  /**
   * Start the commit run in the background. Refused unless the gate is open and a backup is
   * current.
   */
  @PostMapping(BASE)
  String commit(RedirectAttributes redirectAttributes) {
    Optional<ImportCommitView> view = importCommitScreen.forOpenSession();
    if (view.isEmpty()) {
      return REDIRECT_SCREEN;
    }
    if (!view.get().canCommit()) {
      redirectAttributes.addFlashAttribute(
          "error",
          "The campaign cannot be committed yet — the review must be clear, a safety backup must"
              + " be taken after the last change, and no commit may already be running.");
      return REDIRECT_COMMIT;
    }
    if (!importCommitWorker.start(view.get().importSessionId())) {
      redirectAttributes.addFlashAttribute("error", "A commit is already running.");
    }
    return REDIRECT_COMMIT;
  }

  /** The polling fragment (plan f2b) — the {@code receipts.html} list-poll pattern. */
  @GetMapping(BASE + "/status")
  String status(Model model) {
    ImportCommitProgress progress =
        importSessionService
            .currentSession()
            .map(session -> importCommitWorker.progressFor(session.importSessionId()))
            .orElse(ImportCommitProgress.IDLE);
    model.addAttribute("progress", progress);
    return VIEW + " :: progress";
  }
}
