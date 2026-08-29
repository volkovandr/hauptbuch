package volkovandr.hauptbuch.backup;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (§1.5): the unattended paths — the daily job and the boot catch-up that covers a Pi
 * which was powered off at the scheduled hour.
 *
 * <p>Two things are being pinned down. First, the catch-up must fire only when the scheduled time
 * has actually passed, or a reboot before it produces a backup now <em>and</em> another at the
 * scheduled hour. Second, neither path may propagate <em>any</em> runtime failure: an escape from
 * the startup listener aborts the whole application boot.
 */
class BackupSchedulerTest {

  /** 03:00 daily, matching the shipped default. */
  private static final String CRON = "0 0 3 * * *";

  private static final LocalDateTime AFTER_THE_HOUR = LocalDateTime.of(2026, 8, 29, 8, 0);
  private static final LocalDateTime BEFORE_THE_HOUR = LocalDateTime.of(2026, 8, 29, 1, 0);

  private BackupService service;
  private BackupScheduler scheduler;

  @BeforeEach
  void setUp() {
    service = mock(BackupService.class);
    scheduler =
        new BackupScheduler(service, new BackupProperties(Path.of("unused"), 30, true, CRON));
  }

  private static BackupFile taken() {
    return new BackupFile("f.dump", BackupKind.AUTOMATIC, LocalDateTime.now(), 1L);
  }

  // ── the daily job ───────────────────────────────────────────────────────────

  @Test
  void dailyRunTakesAutomaticBackup() {
    when(service.take(BackupKind.AUTOMATIC)).thenReturn(taken());

    scheduler.takeDailyBackup();

    verify(service).take(BackupKind.AUTOMATIC);
  }

  @Test
  void dailyRunSwallowsBackupFailure() {
    when(service.take(BackupKind.AUTOMATIC)).thenThrow(new BackupFailedException("no pg_dump"));

    assertThatCode(scheduler::takeDailyBackup).doesNotThrowAnyException();
  }

  @Test
  void dailyRunSwallowsStorageFailure() {
    // Sweeping throws UncheckedIOException, not BackupFailedException, when the directory is gone.
    when(service.take(BackupKind.AUTOMATIC))
        .thenThrow(new UncheckedIOException(new IOException("read-only file system")));

    assertThatCode(scheduler::takeDailyBackup).doesNotThrowAnyException();
  }

  // ── the startup catch-up ────────────────────────────────────────────────────

  @Test
  void catchUpTakesBackupWhenTheHourPassedAndTodayHasNone() {
    when(service.hasBackupOn(AFTER_THE_HOUR.toLocalDate())).thenReturn(false);
    when(service.take(BackupKind.AUTOMATIC)).thenReturn(taken());

    scheduler.catchUpAt(AFTER_THE_HOUR);

    verify(service).take(BackupKind.AUTOMATIC);
  }

  @Test
  void catchUpSkipsWhenTodayIsAlreadyCovered() {
    when(service.hasBackupOn(AFTER_THE_HOUR.toLocalDate())).thenReturn(true);

    scheduler.catchUpAt(AFTER_THE_HOUR);

    // Restarting the app repeatedly must not produce a backup per restart.
    verify(service, never()).take(any());
  }

  @Test
  void catchUpSkipsBeforeTheScheduledHour() {
    scheduler.catchUpAt(BEFORE_THE_HOUR);

    // Nothing has been missed yet at 01:00 — the 03:00 job will run normally. Taking one here
    // would mean two automatic backups on any day the Pi rebooted overnight.
    verify(service, never()).take(any());
    verify(service, never()).hasBackupOn(any());
  }

  @Test
  void catchUpSwallowsBackupFailure() {
    when(service.hasBackupOn(any())).thenReturn(false);
    when(service.take(BackupKind.AUTOMATIC)).thenThrow(new BackupFailedException("no pg_dump"));

    // A Pi with a broken pg_dump must still boot into a usable ledger.
    assertThatCode(() -> scheduler.catchUpAt(AFTER_THE_HOUR)).doesNotThrowAnyException();
  }

  @Test
  void catchUpSwallowsStorageFailure() {
    // The listener runs during ApplicationReadyEvent: an escape here aborts application startup,
    // which would take the ledger down exactly when the card is failing and it is most needed.
    when(service.hasBackupOn(any()))
        .thenThrow(new UncheckedIOException(new IOException("cannot read backup directory")));

    assertThatCode(() -> scheduler.catchUpAt(AFTER_THE_HOUR)).doesNotThrowAnyException();
  }
}
