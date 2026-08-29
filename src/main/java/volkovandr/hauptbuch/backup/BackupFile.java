package volkovandr.hauptbuch.backup;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * One backup sitting on disk, as the listing shows it.
 *
 * <p>Every field except the size is decoded from the filename ({@link BackupNames}); the size comes
 * from the directory entry. There is no database row behind this — see {@link BackupNames} for why.
 *
 * @param fileName the dump's filename, which is also its identity in the delete/download routes
 * @param kind whether it was taken by hand or by the scheduler
 * @param takenAt the local timestamp encoded in the filename
 * @param sizeBytes the file's size on disk
 */
public record BackupFile(String fileName, BackupKind kind, LocalDateTime takenAt, long sizeBytes) {

  private static final long KILOBYTE = 1024L;
  private static final long MEGABYTE = KILOBYTE * 1024L;

  /**
   * The size rendered for display, German-formatted like every other number in the UI (CLAUDE.md
   * §5): bytes below a kilobyte, then one decimal place in kB or MB.
   */
  public String sizeLabel() {
    if (sizeBytes < KILOBYTE) {
      return sizeBytes + " B";
    }
    if (sizeBytes < MEGABYTE) {
      return String.format(Locale.GERMAN, "%.1f kB", (double) sizeBytes / KILOBYTE);
    }
    return String.format(Locale.GERMAN, "%.1f MB", (double) sizeBytes / MEGABYTE);
  }
}
