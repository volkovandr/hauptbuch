package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tier: {@link StatementStorage}'s on-disk behaviour against a temp directory — the path
 * scheme, a file name that cannot escape the root, the empty and oversize refusals, and a read that
 * refuses a path outside the root.
 */
class StatementStorageTest {

  private static final Instant FIXED = Instant.parse("2026-07-30T14:30:22.123Z");

  private static StatementStorage storageAt(Path root) {
    return new StatementStorage(
        new StatementStorageProperties(root), Clock.fixed(FIXED, ZoneId.of("UTC")));
  }

  @Test
  void storesUnderYearMonthWithTimestampAndReadsItBack(@TempDir Path root) {
    StatementStorage storage = storageAt(root);
    byte[] bytes = "a;b".getBytes(StandardCharsets.UTF_8);

    String rel = storage.store("2026-05.csv", bytes);

    assertThat(rel).isEqualTo("2026/07/20260730-143022123-2026-05.csv");
    assertThat(Files.exists(root.resolve(rel))).isTrue();
    assertThat(storage.read(rel)).isEqualTo(bytes);
  }

  @Test
  void fileNameCannotCarryDirectoryOrOddCharacters(@TempDir Path root) {
    String rel = storageAt(root).store("../../etc/my statement (1).csv", new byte[] {1});

    assertThat(rel).isEqualTo("2026/07/20260730-143022123-my_statement__1_.csv");
  }

  @Test
  void refusesEmptyFile(@TempDir Path root) {
    assertThatThrownBy(() -> storageAt(root).store("a.csv", new byte[0]))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageContaining("No file was attached");
  }

  @Test
  void refusesFileOverTheCap(@TempDir Path root) {
    byte[] big = new byte[(int) StatementStorage.MAX_BYTES + 1];

    assertThatThrownBy(() -> storageAt(root).store("a.csv", big))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageContaining("15 MB");
  }

  @Test
  void readRefusesPathOutsideTheRootAndMissingFile(@TempDir Path root) {
    StatementStorage storage = storageAt(root);

    assertThatThrownBy(() -> storage.read("../outside.csv"))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageContaining("not a stored statement file");
    assertThatThrownBy(() -> storage.read("2026/07/missing.csv"))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageContaining("no longer there");
  }
}
