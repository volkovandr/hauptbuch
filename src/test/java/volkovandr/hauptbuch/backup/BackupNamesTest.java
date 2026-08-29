package volkovandr.hauptbuch.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (§1.5): the backup filename is the only place a backup's kind and timestamp are
 * recorded — there is deliberately no {@code backup} table, since a table would live inside every
 * dump and a restore would bring back a stale list of backups. That makes this encoding
 * load-bearing for retention, so it is tested on its own.
 */
class BackupNamesTest {

  private static final LocalDateTime TAKEN = LocalDateTime.of(2026, 8, 29, 13, 45, 0);

  @Test
  void buildsManualName() {
    assertThat(BackupNames.fileNameFor(BackupKind.MANUAL, TAKEN))
        .isEqualTo("hauptbuch-20260829-134500-manual.dump");
  }

  @Test
  void buildsAutomaticName() {
    assertThat(BackupNames.fileNameFor(BackupKind.AUTOMATIC, TAKEN))
        .isEqualTo("hauptbuch-20260829-134500-auto.dump");
  }

  @Test
  void parsesNameItBuilt() {
    String name = BackupNames.fileNameFor(BackupKind.MANUAL, TAKEN);

    Optional<BackupFile> parsed = BackupNames.parse(name, 2048L);

    assertThat(parsed).isPresent();
    assertThat(parsed.get().fileName()).isEqualTo(name);
    assertThat(parsed.get().kind()).isEqualTo(BackupKind.MANUAL);
    assertThat(parsed.get().takenAt()).isEqualTo(TAKEN);
    assertThat(parsed.get().sizeBytes()).isEqualTo(2048L);
  }

  @Test
  void parsesAutomaticKind() {
    Optional<BackupFile> parsed = BackupNames.parse("hauptbuch-20260829-134500-auto.dump", 1L);

    assertThat(parsed).isPresent();
    assertThat(parsed.get().kind()).isEqualTo(BackupKind.AUTOMATIC);
  }

  @Test
  void rejectsForeignFile() {
    assertThat(BackupNames.parse("notes.txt", 1L)).isEmpty();
    assertThat(BackupNames.parse("hauptbuch-20260829-134500-auto.txt", 1L)).isEmpty();
    assertThat(BackupNames.parse("other-20260829-134500-auto.dump", 1L)).isEmpty();
  }

  @Test
  void rejectsUnknownKind() {
    assertThat(BackupNames.parse("hauptbuch-20260829-134500-weekly.dump", 1L)).isEmpty();
  }

  @Test
  void rejectsUnparseableTimestamp() {
    assertThat(BackupNames.parse("hauptbuch-notadate-134500-auto.dump", 1L)).isEmpty();
    assertThat(BackupNames.parse("hauptbuch-20261332-134500-auto.dump", 1L)).isEmpty();
  }

  @Test
  void rejectsPathTraversalDisguisedAsName() {
    // The download and delete routes resolve a user-supplied name; a name that is not exactly the
    // encoding must never survive parsing.
    assertThat(BackupNames.parse("../hauptbuch-20260829-134500-auto.dump", 1L)).isEmpty();
    assertThat(BackupNames.parse("sub/hauptbuch-20260829-134500-auto.dump", 1L)).isEmpty();
  }

  @Test
  void namesSortLexicographicallyByTime() {
    String earlier = BackupNames.fileNameFor(BackupKind.AUTOMATIC, TAKEN);
    String later = BackupNames.fileNameFor(BackupKind.AUTOMATIC, TAKEN.plusSeconds(1));

    // Retention depends on ordering; a zero-padded timestamp makes string order time order.
    assertThat(earlier).isLessThan(later);
  }
}
