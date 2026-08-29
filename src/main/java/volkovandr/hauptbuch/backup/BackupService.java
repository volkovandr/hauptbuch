package volkovandr.hauptbuch.backup;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Taking, listing, and sweeping database backups.
 *
 * <p>The domain operation here is small but the retention rules carry the risk, so they are stated
 * in one place:
 *
 * <ul>
 *   <li>an automatic backup sweeps automatic backups beyond the newest {@code keepAutomatic};
 *   <li>a <strong>manual</strong> backup is never swept — it was taken deliberately, usually right
 *       before something risky, and a nightly job removing it would defeat the point;
 *   <li>a sweep never empties the directory, whatever the keep count is set to;
 *   <li>an explicit per-row delete is the user's own decision and is always honoured.
 * </ul>
 *
 * <p>Backups are database-only by decision: receipt images stay on disk and are not copied here, so
 * a restored ledger can reference images that are gone. That limitation is documented in the
 * deployment runbook rather than solved.
 */
@Service
public class BackupService {

  private static final Logger LOG = LoggerFactory.getLogger(BackupService.class);

  private final BackupStore store;
  private final PgDumpRunner runner;
  private final DatabaseTargetResolver targetResolver;
  private final BackupProperties properties;

  BackupService(
      BackupStore store,
      PgDumpRunner runner,
      DatabaseTargetResolver targetResolver,
      BackupProperties properties) {
    this.store = store;
    this.runner = runner;
    this.targetResolver = targetResolver;
    this.properties = properties;
  }

  /** Every backup on disk, newest first. */
  public List<BackupFile> list() {
    return store.list();
  }

  /**
   * Take a backup now.
   *
   * <p>An automatic one sweeps afterwards; a manual one does not (see the class note). A structural
   * event worth a permanent record, so INFO (CLAUDE.md §5) — the filename and size only, never
   * ledger contents.
   *
   * @throws BackupFailedException if the dump could not be produced
   */
  public BackupFile take(BackupKind kind) {
    DatabaseTarget target = targetResolver.resolve();
    String fileName = BackupNames.fileNameFor(kind, LocalDateTime.now());
    BackupFile taken = store.write(fileName, destination -> runner.dump(target, destination));
    LOG.info("Took {} backup {} ({} bytes)", kind.suffix(), taken.fileName(), taken.sizeBytes());
    if (kind == BackupKind.AUTOMATIC) {
      sweep();
    }
    return taken;
  }

  /**
   * Delete automatic backups beyond the newest {@code keepAutomatic}, never emptying the directory.
   *
   * @return how many were deleted
   */
  public int sweep() {
    List<BackupFile> all = list();
    List<BackupFile> automatic =
        all.stream().filter(file -> file.kind() == BackupKind.AUTOMATIC).toList();
    if (automatic.size() <= properties.keepAutomatic()) {
      return 0;
    }
    int remaining = all.size();
    int deleted = 0;
    for (BackupFile stale : automatic.subList(properties.keepAutomatic(), automatic.size())) {
      if (remaining <= 1) {
        break;
      }
      if (store.delete(stale.fileName())) {
        remaining--;
        deleted++;
      }
    }
    if (deleted > 0) {
      LOG.info(
          "Swept {} automatic backup(s) beyond the newest {}", deleted, properties.keepAutomatic());
    }
    return deleted;
  }

  /**
   * Delete one backup by name. Honoured even for the only remaining backup — the floor in {@link
   * #sweep()} guards the unattended path, not a deliberate one.
   *
   * @return false when the name is not a backup we hold
   */
  public boolean delete(String fileName) {
    boolean deleted = store.delete(fileName);
    if (deleted) {
      LOG.info("Deleted backup {}", fileName);
    }
    return deleted;
  }

  /** The file behind a backup name, for download; empty when the name is not one we hold. */
  public Optional<Path> fileFor(String fileName) {
    return store.resolve(fileName);
  }

  /** Whether any backup — of either kind — was already taken on {@code date}. */
  public boolean hasBackupOn(LocalDate date) {
    return list().stream().anyMatch(file -> file.takenAt().toLocalDate().equals(date));
  }

  /** The database being backed up, for the restore commands the screen shows. */
  public String databaseName() {
    return targetResolver.resolve().database();
  }

  /** The role that owns the database, for the {@code createdb --owner} in the restore commands. */
  public String databaseUser() {
    return targetResolver.resolve().username();
  }

  /** The directory backups are written to, for the restore commands the screen shows. */
  public Path storageRoot() {
    return properties.storageRoot().toAbsolutePath().normalize();
  }
}
