package volkovandr.hauptbuch.backup;

import java.nio.file.Path;

/**
 * Produces a PostgreSQL dump of a database at a given path.
 *
 * <p>The seam between this feature's logic and the one thing about it that cannot run in a unit
 * test: an external binary. {@link BackupService}'s naming, retention and failure handling are
 * tested against a mock of this interface; the real implementation is exercised once, against
 * Testcontainers, in the integration tier.
 */
@FunctionalInterface
interface PgDumpRunner {

  /**
   * Dump {@code target} to {@code destination}.
   *
   * @throws BackupFailedException if the dump could not be produced
   */
  void dump(DatabaseTarget target, Path destination);
}
