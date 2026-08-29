package volkovandr.hauptbuch.backup;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Encodes and decodes a backup's filename: {@code hauptbuch-<yyyyMMdd>-<HHmmss>-<kind>.dump}.
 *
 * <p><strong>The filename is the whole record.</strong> This feature deliberately has no {@code
 * backup} table and no migration: a table listing backups would itself be inside every dump, so
 * restoring a month-old backup would also restore a month-old list of backups. The directory is the
 * source of truth, which means the kind and the timestamp have to live in the name — and retention
 * reads them straight back out of a directory listing.
 *
 * <p>The timestamp is zero-padded, so lexicographic order on the filename is chronological order.
 *
 * <p>{@link #parse} is also the validator for the delete and download routes: a name that does not
 * decode exactly is rejected, which is what keeps a traversal attempt like {@code ../../etc/passwd}
 * from ever reaching the filesystem.
 */
final class BackupNames {

  private static final String PREFIX = "hauptbuch";
  private static final String EXTENSION = ".dump";
  private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
  private static final int PARTS = 4;

  private BackupNames() {}

  /** The filename a backup of this kind taken at this local time is stored under. */
  static String fileNameFor(BackupKind kind, LocalDateTime takenAt) {
    return PREFIX + "-" + TIMESTAMP.format(takenAt) + "-" + kind.suffix() + EXTENSION;
  }

  /**
   * Decode a directory entry, or empty when the name is not one of ours — a stray file dropped in
   * the directory, or a supplied name that is not exactly the encoding.
   */
  static Optional<BackupFile> parse(String fileName, long sizeBytes) {
    if (fileName == null || !fileName.endsWith(EXTENSION)) {
      return Optional.empty();
    }
    String stem = fileName.substring(0, fileName.length() - EXTENSION.length());
    String[] parts = stem.split("-", -1);
    if (parts.length != PARTS || !PREFIX.equals(parts[0])) {
      return Optional.empty();
    }
    return kindOf(parts[3])
        .flatMap(
            kind -> timestampOf(parts[1], parts[2]).map(at -> at(fileName, kind, at, sizeBytes)));
  }

  private static BackupFile at(
      String fileName, BackupKind kind, LocalDateTime takenAt, long sizeBytes) {
    return new BackupFile(fileName, kind, takenAt, sizeBytes);
  }

  private static Optional<BackupKind> kindOf(String suffix) {
    for (BackupKind kind : BackupKind.values()) {
      if (kind.suffix().equals(suffix)) {
        return Optional.of(kind);
      }
    }
    return Optional.empty();
  }

  private static Optional<LocalDateTime> timestampOf(String date, String time) {
    try {
      return Optional.of(LocalDateTime.parse(date + "-" + time, TIMESTAMP));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }
}
