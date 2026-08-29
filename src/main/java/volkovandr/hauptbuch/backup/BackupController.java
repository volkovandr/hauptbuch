package volkovandr.hauptbuch.backup;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import volkovandr.hauptbuch.web.NavItem;

/**
 * The backup screen: the listing, the take-one-now button, per-backup download and delete, and the
 * restore commands to copy.
 *
 * <p>Lives in the {@code backup} module — a feature module owns its own screen (CLAUDE.md §3). It
 * is reached from Settings rather than the primary nav: it is an ops page, not a section of the
 * ledger.
 *
 * <p>Standard server-rendered forms with redirect-after-post, as every other screen of this shape
 * has (Settings, People). A failed dump is an <em>expected</em> failure, so it is caught here and
 * shown on the page as a flash message rather than reaching the global error boundary.
 *
 * <p>There is no restore endpoint, deliberately — see the module's {@code package-info}.
 */
@Controller
class BackupController {

  private static final Logger LOG = LoggerFactory.getLogger(BackupController.class);

  private static final String BASE_PATH = "/backup";
  private static final String VIEW = "backup";
  private static final String REDIRECT_TO_LIST = "redirect:" + BASE_PATH;

  private final BackupService backupService;

  BackupController(BackupService backupService) {
    this.backupService = backupService;
  }

  /** The backup screen. */
  @GetMapping(BASE_PATH)
  String backups(Model model) {
    model.addAttribute("backups", backupService.list());
    model.addAttribute("databaseName", backupService.databaseName());
    model.addAttribute("databaseUser", backupService.databaseUser());
    model.addAttribute("storageRoot", backupService.storageRoot().toString());
    model.addAttribute("nav", NavItem.sectionsFor("/settings"));
    model.addAttribute("title", "Backups · Hauptbuch");
    return VIEW;
  }

  /** Take a backup now, by hand. Never swept by retention. */
  @PostMapping(BASE_PATH)
  String takeBackup(RedirectAttributes redirectAttributes) {
    try {
      BackupFile taken = backupService.take(BackupKind.MANUAL);
      redirectAttributes.addFlashAttribute(
          "backupMessage", "Backup " + taken.fileName() + " taken.");
    } catch (BackupFailedException e) {
      // An expected failure that was handled: the user is told, the screen still works (§5 WARN).
      LOG.warn("Manual backup failed: {}", e.getMessage());
      redirectAttributes.addFlashAttribute("backupError", e.getMessage());
    }
    return REDIRECT_TO_LIST;
  }

  /**
   * Delete one backup. Honoured even for the last remaining one — the "never empty the directory"
   * floor guards the unattended sweep, not a deliberate click.
   */
  @PostMapping(BASE_PATH + "/{fileName}/delete")
  String deleteBackup(@PathVariable String fileName, RedirectAttributes redirectAttributes) {
    if (backupService.delete(fileName)) {
      redirectAttributes.addFlashAttribute("backupMessage", "Backup " + fileName + " deleted.");
    } else {
      redirectAttributes.addFlashAttribute("backupError", "That backup is no longer there.");
    }
    return REDIRECT_TO_LIST;
  }

  /**
   * Download a dump.
   *
   * <p>The file contains the {@code settings} row and therefore the AI API key (NFR-04), so this is
   * a route that hands out a secret-bearing file — which is survivable only because the app is
   * LAN-only for now (ARCH-04 is still backlog).
   */
  @GetMapping(BASE_PATH + "/{fileName}/download")
  ResponseEntity<Resource> download(@PathVariable String fileName) {
    return backupService
        .fileFor(fileName)
        .map(BackupController::attachment)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private static ResponseEntity<Resource> attachment(Path path) {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(
            HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + path.getFileName() + "\"")
        .body(new FileSystemResource(path));
  }
}
