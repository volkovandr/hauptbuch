package volkovandr.hauptbuch.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tier (§1.5): {@link BackupService} orchestration and the retention rules — the logic that
 * actually decides what is deleted, which is the part of this feature that can destroy something.
 *
 * <p>{@code pg_dump} itself is mocked at the {@link PgDumpRunner} seam (the real binary is
 * exercised once, in the integration tier); the store runs against a real temp directory, since
 * "which files are on disk" is the thing under test and mocking a filesystem would prove nothing.
 */
class BackupServiceTest {

  private static final LocalDateTime TAKEN = LocalDateTime.of(2026, 8, 29, 13, 45, 0);
  private static final DatabaseTarget TARGET =
      new DatabaseTarget("localhost", 5432, "hauptbuch", "hauptbuch", "pw");

  /**
   * The backup directory is a *subdirectory* of the temp dir, so "one level up" is a real place.
   */
  @TempDir Path temp;

  private Path root;
  private PgDumpRunner runner;
  private DatabaseTargetResolver resolver;

  @BeforeEach
  void setUp() throws IOException {
    root = temp.resolve("backups");
    Files.createDirectories(root);
    runner = mock(PgDumpRunner.class);
    resolver = mock(DatabaseTargetResolver.class);
    when(resolver.resolve()).thenReturn(TARGET);
    // A successful pg_dump leaves a file at the destination; the store moves that into place.
    doAnswer(
            invocation -> {
              Files.writeString(invocation.getArgument(1, Path.class), "pretend dump");
              return null;
            })
        .when(runner)
        .dump(any(), any());
  }

  private BackupService serviceKeeping(int keepAutomatic) {
    BackupProperties properties = new BackupProperties(root, keepAutomatic, false, "0 0 3 * * *");
    return new BackupService(new BackupStore(properties), runner, resolver, properties);
  }

  /** Writes a file straight into the directory, as an already-taken backup of that kind. */
  private void existing(BackupKind kind, LocalDateTime at) throws IOException {
    Files.writeString(root.resolve(BackupNames.fileNameFor(kind, at)), "dump");
  }

  // ── taking a backup ─────────────────────────────────────────────────────────

  @Test
  void takingBackupRunsDumpAndListsIt() {
    BackupFile taken = serviceKeeping(30).take(BackupKind.MANUAL);

    verify(runner).dump(eq(TARGET), any(Path.class));
    assertThat(taken.kind()).isEqualTo(BackupKind.MANUAL);
    assertThat(root.resolve(taken.fileName())).exists();
  }

  @Test
  void createsStorageDirectoryOnFirstUse() throws IOException {
    Path nested = root.resolve("not-created-yet");
    BackupProperties properties = new BackupProperties(nested, 30, false, "0 0 3 * * *");
    BackupService service =
        new BackupService(new BackupStore(properties), runner, resolver, properties);

    service.take(BackupKind.MANUAL);

    assertThat(Files.isDirectory(nested)).isTrue();
  }

  @Test
  void failedDumpLeavesNoFileBehind() {
    doThrow(new BackupFailedException("pg_dump exited 1")).when(runner).dump(any(), any());
    BackupService service = serviceKeeping(30);

    assertThatThrownBy(() -> service.take(BackupKind.MANUAL))
        .isInstanceOf(BackupFailedException.class);

    // Not merely absent from the listing — absent from the directory, so no half-written dump
    // accumulates on the Pi.
    assertThat(root.toFile().listFiles()).isEmpty();
    assertThat(service.list()).isEmpty();
  }

  @Test
  void listsNewestFirstAndIgnoresForeignFiles() throws IOException {
    existing(BackupKind.AUTOMATIC, TAKEN.minusDays(2));
    existing(BackupKind.MANUAL, TAKEN);
    existing(BackupKind.AUTOMATIC, TAKEN.minusDays(1));
    Files.writeString(root.resolve("README.txt"), "not a backup");

    List<BackupFile> listed = serviceKeeping(30).list();

    assertThat(listed)
        .extracting(BackupFile::takenAt)
        .containsExactly(TAKEN, TAKEN.minusDays(1), TAKEN.minusDays(2));
  }

  @Test
  void listIsEmptyWhenDirectoryDoesNotExist() {
    BackupProperties properties =
        new BackupProperties(root.resolve("absent"), 30, false, "0 0 3 * * *");
    BackupService service =
        new BackupService(new BackupStore(properties), runner, resolver, properties);

    assertThat(service.list()).isEmpty();
  }

  // ── retention ───────────────────────────────────────────────────────────────

  @Test
  void sweepsAutomaticBackupsBeyondTheKeepCount() throws IOException {
    for (int day = 1; day <= 5; day++) {
      existing(BackupKind.AUTOMATIC, TAKEN.minusDays(day));
    }
    BackupService service = serviceKeeping(3);

    int deleted = service.sweep();

    assertThat(deleted).isEqualTo(2);
    assertThat(service.list())
        .extracting(BackupFile::takenAt)
        .containsExactly(TAKEN.minusDays(1), TAKEN.minusDays(2), TAKEN.minusDays(3));
  }

  @Test
  void sweepKeepsManualBackupsRegardlessOfAge() throws IOException {
    existing(BackupKind.MANUAL, TAKEN.minusYears(2));
    for (int day = 1; day <= 5; day++) {
      existing(BackupKind.AUTOMATIC, TAKEN.minusDays(day));
    }
    BackupService service = serviceKeeping(1);

    service.sweep();

    // The two-year-old manual backup outlives every automatic one: it was taken deliberately.
    assertThat(service.list())
        .extracting(BackupFile::kind, BackupFile::takenAt)
        .containsExactly(
            tuple(BackupKind.AUTOMATIC, TAKEN.minusDays(1)),
            tuple(BackupKind.MANUAL, TAKEN.minusYears(2)));
  }

  @Test
  void sweepDeletesNothingWhenWithinTheKeepCount() throws IOException {
    existing(BackupKind.AUTOMATIC, TAKEN.minusDays(1));
    existing(BackupKind.AUTOMATIC, TAKEN.minusDays(2));

    assertThat(serviceKeeping(30).sweep()).isZero();
  }

  @Test
  void sweepNeverDeletesTheLastRemainingBackup() throws IOException {
    existing(BackupKind.AUTOMATIC, TAKEN.minusDays(1));
    BackupService service = serviceKeeping(0);

    int deleted = service.sweep();

    assertThat(deleted).isZero();
    assertThat(service.list()).hasSize(1);
  }

  @Test
  void automaticBackupSweepsAfterwards() throws IOException {
    for (int day = 1; day <= 3; day++) {
      existing(BackupKind.AUTOMATIC, TAKEN.minusDays(day));
    }

    serviceKeeping(2).take(BackupKind.AUTOMATIC);

    // The new one plus the newest existing one; the two oldest are swept.
    assertThat(serviceKeeping(2).list()).hasSize(2);
  }

  @Test
  void manualBackupDoesNotSweep() throws IOException {
    for (int day = 1; day <= 3; day++) {
      existing(BackupKind.AUTOMATIC, TAKEN.minusDays(day));
    }
    BackupService service = serviceKeeping(2);

    service.take(BackupKind.MANUAL);

    // Retention is the scheduler's business; a deliberate backup must not trigger deletions the
    // user did not ask for.
    assertThat(service.list()).hasSize(4);
  }

  // ── delete ──────────────────────────────────────────────────────────────────

  @Test
  void deletesNamedBackup() throws IOException {
    existing(BackupKind.MANUAL, TAKEN);
    existing(BackupKind.MANUAL, TAKEN.minusDays(1));
    BackupService service = serviceKeeping(30);

    assertThat(service.delete(BackupNames.fileNameFor(BackupKind.MANUAL, TAKEN))).isTrue();

    assertThat(service.list()).extracting(BackupFile::takenAt).containsExactly(TAKEN.minusDays(1));
  }

  @Test
  void deleteRefusesNameOutsideTheBackupDirectory() throws IOException {
    Path outside = temp.resolve("precious.txt");
    Files.writeString(outside, "do not delete me");
    BackupService service = serviceKeeping(30);

    assertThat(service.delete("../precious.txt")).isFalse();

    assertThat(outside).exists();
  }

  @Test
  void deleteReturnsFalseForUnknownBackup() {
    assertThat(serviceKeeping(30).delete("hauptbuch-20260101-000000-auto.dump")).isFalse();
  }

  @Test
  void deleteRemovesTheLastRemainingBackupWhenAskedExplicitly() throws IOException {
    existing(BackupKind.MANUAL, TAKEN);
    BackupService service = serviceKeeping(30);

    // The "never delete the last one" floor guards *retention* — an unattended sweep must not empty
    // the directory. An explicit per-row delete is the user's own decision and is honoured.
    assertThat(service.delete(BackupNames.fileNameFor(BackupKind.MANUAL, TAKEN))).isTrue();

    assertThat(service.list()).isEmpty();
  }

  // ── the catch-up question the scheduler asks ────────────────────────────────

  @Test
  void reportsWhetherBackupExistsOnDate() throws IOException {
    existing(BackupKind.AUTOMATIC, TAKEN);
    BackupService service = serviceKeeping(30);

    assertThat(service.hasBackupOn(TAKEN.toLocalDate())).isTrue();
    assertThat(service.hasBackupOn(TAKEN.toLocalDate().minusDays(1))).isFalse();
  }

  @Test
  void manualBackupCountsForTheDay() throws IOException {
    existing(BackupKind.MANUAL, TAKEN);

    // A day already covered by a deliberate backup does not also need a catch-up one.
    assertThat(serviceKeeping(30).hasBackupOn(TAKEN.toLocalDate())).isTrue();
  }

  @Test
  void resolvesTargetOnlyWhenDumping() {
    serviceKeeping(30).list();

    verifyNoInteractions(runner);
  }

  @Test
  void downloadableFileResolvesForKnownBackup() throws IOException {
    existing(BackupKind.MANUAL, TAKEN);
    BackupService service = serviceKeeping(30);

    assertThat(service.fileFor(BackupNames.fileNameFor(BackupKind.MANUAL, TAKEN))).isPresent();
    assertThat(service.fileFor("../secrets.txt")).isEmpty();
    assertThat(service.fileFor("hauptbuch-20260101-000000-auto.dump")).isEmpty();
  }
}
