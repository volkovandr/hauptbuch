package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.backup.BackupFailedException;
import volkovandr.hauptbuch.backup.BackupFile;
import volkovandr.hauptbuch.backup.BackupKind;
import volkovandr.hauptbuch.backup.BackupService;
import volkovandr.hauptbuch.importer.ImportCommitService.CommitResult;

/**
 * Unit tier (CLAUDE.md §6): {@link ImportCommitWorker} — the run body's outcomes ({@link #run()}
 * driven directly, the {@code submit} pattern) and the "one run at a time" guard on {@link
 * ImportCommitWorker#start(long)}.
 */
@SuppressWarnings("PMD.DoNotUseThreads")
@ExtendWith(MockitoExtension.class)
class ImportCommitWorkerTest {

  @Mock ImportCommitService importCommitService;
  @Mock BackupService backupService;

  private ImportCommitWorker commitWorker;

  @AfterEach
  void tearDown() {
    if (commitWorker != null) {
      commitWorker.shutdown();
    }
  }

  private ImportCommitWorker newWorker() {
    commitWorker = new ImportCommitWorker(importCommitService, backupService);
    return commitWorker;
  }

  @Test
  void successfulRunClearsStagingThenTakesTheClosingBackup() {
    when(importCommitService.commit(any())).thenReturn(new CommitResult(9L, 40, 2, 5));
    when(backupService.take(BackupKind.MANUAL))
        .thenReturn(new BackupFile("import-close.dump", BackupKind.MANUAL, null, 1L));

    newWorker().run();

    verify(importCommitService).clearStaging(9L);
    verify(backupService).take(BackupKind.MANUAL);
    assertThat(commitWorker.progress().succeeded()).isTrue();
    assertThat(commitWorker.progress().message())
        .contains("40 transaction(s)")
        .contains("skipped 2");
  }

  @Test
  void commitFailureIsRecordedAndNothingElseRuns() {
    when(importCommitService.commit(any()))
        .thenThrow(new IllegalStateException("the campaign is not ready to commit"));

    newWorker().run();

    assertThat(commitWorker.progress().failed()).isTrue();
    assertThat(commitWorker.progress().message()).contains("not ready to commit");
    verify(importCommitService, never()).clearStaging(anyLong());
    verify(backupService, never()).take(any());
  }

  @Test
  void failedPostCommitStepStillEndsInDoneBecauseCommitSucceeded() {
    when(importCommitService.commit(any())).thenReturn(new CommitResult(9L, 40, 0, 0));
    when(backupService.take(BackupKind.MANUAL)).thenThrow(new BackupFailedException("disk full"));

    newWorker().run();

    verify(importCommitService).clearStaging(9L);
    assertThat(commitWorker.progress().succeeded()).isTrue();
    assertThat(commitWorker.progress().message())
        .contains("Committed 40 transaction(s)")
        .contains("take a backup by hand");
  }

  @Test
  void progressIsScopedToTheSessionItRanFor() throws InterruptedException {
    when(importCommitService.commit(any())).thenReturn(new CommitResult(9L, 1, 0, 0));
    when(backupService.take(BackupKind.MANUAL))
        .thenReturn(new BackupFile("x.dump", BackupKind.MANUAL, null, 1L));
    when(importCommitService.committableCount()).thenReturn(1);

    ImportCommitWorker w = newWorker();
    assertThat(w.start(9L)).isTrue();
    waitUntil(() -> w.progressFor(9L).succeeded());

    assertThat(w.progressFor(42L)).isEqualTo(ImportCommitProgress.IDLE);
  }

  @Test
  void startRefusesTheSecondRunWhileOneIsInFlight() throws InterruptedException {
    CountDownLatch release = new CountDownLatch(1);
    when(importCommitService.committableCount()).thenReturn(3);
    when(importCommitService.commit(any()))
        .thenAnswer(
            invocation -> {
              release.await();
              return new CommitResult(9L, 3, 0, 0);
            });
    when(backupService.take(BackupKind.MANUAL))
        .thenReturn(new BackupFile("x.dump", BackupKind.MANUAL, null, 1L));

    ImportCommitWorker w = newWorker();
    assertThat(w.start(9L)).isTrue();
    assertThat(w.progress().running()).isTrue(); // set synchronously by start()
    assertThat(w.start(9L)).isFalse();

    release.countDown();
    waitUntil(() -> w.progress().succeeded());
  }

  private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 2000;
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("condition not met within 2s");
      }
      Thread.sleep(10);
    }
  }
}
