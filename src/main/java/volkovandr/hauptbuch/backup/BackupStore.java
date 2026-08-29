package volkovandr.hauptbuch.backup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The backup directory: what is in it, and adding to or removing from it.
 *
 * <p>Kept apart from {@link BackupService} so the service is orchestration and retention *policy*
 * over a store that only knows about files — the same split {@code receipts} makes between its
 * storage and its services.
 *
 * <p>Every name that arrives from a request is resolved through {@link BackupNames#parse}, so a
 * name that is not exactly the backup encoding never reaches the filesystem.
 */
@Component
class BackupStore {

  private static final Logger LOG = LoggerFactory.getLogger(BackupStore.class);

  /**
   * {@code rwx------}: a dump contains the {@code settings} row, and so the AI API key (NFR-04).
   */
  private static final Set<PosixFilePermission> OWNER_ONLY =
      PosixFilePermissions.fromString("rwx------");

  private final Path root;

  BackupStore(BackupProperties properties) {
    this.root = properties.storageRoot().toAbsolutePath().normalize();
  }

  /** Every backup in the directory, newest first. Empty when nothing has been taken yet. */
  List<BackupFile> list() {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    List<BackupFile> found = new ArrayList<>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
      for (Path entry : entries) {
        decode(entry).ifPresent(found::add);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read the backup directory " + root, e);
    }
    found.sort(Comparator.comparing(BackupFile::takenAt).reversed());
    return List.copyOf(found);
  }

  /**
   * Run {@code write} against a scratch file and, only if it succeeds, move the result into place
   * under {@code fileName}.
   *
   * <p>A dump that fails half-way therefore leaves nothing behind at all: the partial file is
   * removed and the directory is untouched. Without this, an interrupted {@code pg_dump} would
   * leave a truncated file that looks exactly like a good backup.
   *
   * <p>The scratch name is unique per call, not derived from {@code fileName}. Two backups started
   * in the same second would otherwise share one scratch path: both dumps would write into it at
   * once, the first to finish would publish the interleaved result as a good backup, and its
   * cleanup would unlink the other's file mid-write.
   */
  BackupFile write(String fileName, DumpWriter write) {
    ensureDirectory();
    Path scratch = createScratchFile(fileName);
    try {
      write.writeTo(scratch);
      Path target = root.resolve(fileName);
      Files.move(scratch, target, StandardCopyOption.REPLACE_EXISTING);
      return decode(target)
          .orElseThrow(() -> new BackupFailedException("Wrote an unreadable backup."));
    } catch (IOException e) {
      throw new BackupFailedException("Could not store the backup " + fileName + ".", e);
    } finally {
      deleteQuietly(scratch);
    }
  }

  /** A unique, never-listed scratch file ({@code .part} does not parse as a backup name). */
  private Path createScratchFile(String fileName) {
    try {
      return Files.createTempFile(root, fileName + "-", ".part");
    } catch (IOException e) {
      throw new BackupFailedException("Could not prepare the backup " + fileName + ".", e);
    }
  }

  /** Delete a backup by name; false when the name is not a backup or is already gone. */
  boolean delete(String fileName) {
    return resolve(fileName)
        .map(
            path -> {
              try {
                return Files.deleteIfExists(path);
              } catch (IOException e) {
                throw new UncheckedIOException("Failed to delete the backup " + fileName, e);
              }
            })
        .orElse(false);
  }

  /** The path of an existing backup, or empty when the name is not a backup we hold. */
  Optional<Path> resolve(String fileName) {
    if (BackupNames.parse(fileName, 0L).isEmpty()) {
      return Optional.empty();
    }
    Path path = root.resolve(fileName).normalize();
    // root.equals(parent), not parent.equals(root): a normalised path can have a null parent, and
    // this way that reads as "not in the backup directory" rather than throwing.
    if (!root.equals(path.getParent()) || !Files.isRegularFile(path)) {
      return Optional.empty();
    }
    return Optional.of(path);
  }

  /** Create the backup directory, owner-only, if it is not there yet. */
  private void ensureDirectory() {
    if (Files.isDirectory(root)) {
      return;
    }
    try {
      Files.createDirectories(root);
      trySetOwnerOnly();
    } catch (IOException e) {
      throw new BackupFailedException("Could not create the backup directory " + root + ".", e);
    }
  }

  /**
   * Tighten the directory to owner-only. Skipped where POSIX permissions do not apply — the
   * permission is a hardening measure on the Pi, not a correctness requirement.
   */
  private void trySetOwnerOnly() {
    try {
      Files.setPosixFilePermissions(root, OWNER_ONLY);
    } catch (IOException | UnsupportedOperationException e) {
      // Non-POSIX filesystem: nothing to tighten.
      LOG.debug("Could not restrict {} to owner-only", root, e);
    }
  }

  private Optional<BackupFile> decode(Path entry) {
    if (!Files.isRegularFile(entry)) {
      return Optional.empty();
    }
    Path name = entry.getFileName();
    if (name == null) {
      return Optional.empty();
    }
    try {
      return BackupNames.parse(name.toString(), Files.size(entry));
    } catch (IOException e) {
      // A file that vanished mid-listing simply is not there.
      return Optional.empty();
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      // Best effort: the scratch file is already out of the listing (".part" is not a backup name).
      LOG.debug("Could not remove the scratch file {}", path, e);
    }
  }

  /** Writes a dump to the given path, or throws. */
  @FunctionalInterface
  interface DumpWriter {

    /** Produce the dump at {@code destination}. */
    void writeTo(Path destination);
  }
}
