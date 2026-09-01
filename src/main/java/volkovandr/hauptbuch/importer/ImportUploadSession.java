package volkovandr.hauptbuch.importer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The uploads of the current import campaign that have a preview but are not yet staged (plan b2).
 * Held as a single HTTP-session attribute so the controller never has to cast a raw collection out
 * of the session.
 *
 * <p>The filename carries no identity (import.md §2): a second upload whose name matches one
 * already here is neither merged nor dropped silently — it is parked as the pending clash until the
 * owner says whether it <em>replaces</em> that upload or is a <em>coincidence</em> (Money reuses
 * one filename across every export, so a match is expected).
 */
final class ImportUploadSession implements Serializable {

  private static final long serialVersionUID = 1L;

  /** The HTTP-session attribute this lives under. */
  static final String ATTRIBUTE = "importUploadSession";

  private final List<PendingImportUpload> uploads = new ArrayList<>();
  private PendingImportUpload pendingClash;

  /** The staged-preview-pending uploads, in upload order. */
  List<PendingImportUpload> pending() {
    return List.copyOf(uploads);
  }

  Optional<PendingImportUpload> findByToken(String token) {
    return uploads.stream().filter(upload -> upload.token().equals(token)).findFirst();
  }

  boolean hasFilename(String filename) {
    return uploads.stream().anyMatch(upload -> upload.sourceFilename().equals(filename));
  }

  void add(PendingImportUpload upload) {
    uploads.add(upload);
  }

  void removeByToken(String token) {
    uploads.removeIf(upload -> upload.token().equals(token));
  }

  /** Apply the owner's charset / date-order choice to one pending upload (import.md §4.3). */
  void updateChoice(String token, String charsetChoice, String dateOrderChoice) {
    for (int index = 0; index < uploads.size(); index++) {
      if (uploads.get(index).token().equals(token)) {
        uploads.set(index, uploads.get(index).withChoice(charsetChoice, dateOrderChoice));
        return;
      }
    }
  }

  /** Park an upload whose filename clashes with an existing one, pending the owner's choice. */
  void parkClash(PendingImportUpload upload) {
    this.pendingClash = upload;
  }

  Optional<PendingImportUpload> clash() {
    return Optional.ofNullable(pendingClash);
  }

  /**
   * Resolve a parked filename clash: {@code replace} discards the existing upload(s) of that name
   * first, {@code coincidence} keeps them alongside the new one. Returns the new upload's token.
   */
  String resolveClash(boolean replace) {
    PendingImportUpload incoming = pendingClash;
    if (replace) {
      uploads.removeIf(upload -> upload.sourceFilename().equals(incoming.sourceFilename()));
    }
    uploads.add(incoming);
    pendingClash = null;
    return incoming.token();
  }

  void clearClash() {
    this.pendingClash = null;
  }
}
