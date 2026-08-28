package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tier: {@link ReceiptStorageFootprint} sums every file under the storage root against a temp
 * directory (no Spring, no Postgres). Feeds the landing-page tracking-stats line (CONTEXT.md
 * "Tracking stats").
 */
class ReceiptStorageFootprintTest {

  private ReceiptStorageFootprint footprintAt(Path root) {
    return new ReceiptStorageFootprint(new ReceiptStorageProperties(root));
  }

  @Test
  void totalBytesIsZeroWhenTheRootDoesNotExistYet(@TempDir Path parent) {
    assertThat(footprintAt(parent.resolve("not-created-yet")).totalBytes()).isZero();
  }

  @Test
  void totalBytesSumsEveryFileUnderTheRootAcrossSubdirectories(@TempDir Path root)
      throws IOException {
    Files.createDirectories(root.resolve("originals/2026/07"));
    Files.createDirectories(root.resolve("thumbs/2026/07"));
    Files.write(root.resolve("originals/2026/07/a.jpg"), new byte[300]);
    Files.write(root.resolve("thumbs/2026/07/a.jpg"), new byte[40]);
    // A stray file directly under the root counts too — the walk is by tree, not by scheme.
    Files.write(root.resolve("stray.txt"), new byte[10]);

    assertThat(footprintAt(root).totalBytes()).isEqualTo(350L);
  }

  @Test
  void totalBytesIgnoresNonRegularEntriesInTheTree(@TempDir Path root) throws IOException {
    Files.write(root.resolve("real.jpg"), new byte[128]);
    // A dangling symlink is not a regular file and its target has no size — it must not be
    // counted and must not fail the walk (which would 500 the landing page).
    Files.createSymbolicLink(root.resolve("dangling"), root.resolve("gone"));

    assertThat(footprintAt(root).totalBytes()).isEqualTo(128L);
  }
}
