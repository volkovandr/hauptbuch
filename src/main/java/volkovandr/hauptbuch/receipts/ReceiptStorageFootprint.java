package volkovandr.hauptbuch.receipts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import org.springframework.stereotype.Component;

/**
 * The on-disk size of the receipt image tree — originals, edited derivatives, and thumbnails —
 * summed by walking {@link ReceiptStorageProperties#storageRoot()}. Kept apart from {@link
 * ReceiptStorage} (which owns writing and reading individual images) so that class stays focused;
 * this is a read-only measurement for the landing-page tracking-stats line (CONTEXT.md "Tracking
 * stats").
 */
@Component
class ReceiptStorageFootprint {

  private final Path root;

  ReceiptStorageFootprint(ReceiptStorageProperties properties) {
    this.root = properties.storageRoot().toAbsolutePath().normalize();
  }

  /**
   * Total bytes of every regular file under the storage root. Zero when the root does not exist yet
   * (nothing captured). Walks the tree on each call — cheap at this scale; a cache would only be
   * warranted if measured slow. Reads each entry's size from the attributes the walk already loaded
   * (one stat per file), and tolerates a file that vanishes mid-walk — a concurrent re-crop or
   * purge just leaves that file uncounted rather than failing the landing page.
   */
  long totalBytes() {
    if (!Files.isDirectory(root)) {
      return 0L;
    }
    ByteSummingVisitor visitor = new ByteSummingVisitor();
    try {
      Files.walkFileTree(root, visitor);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to walk the receipt storage root " + root, e);
    }
    return visitor.total;
  }

  /** Accumulates the size of every regular file visited; skips entries that cannot be read. */
  private static final class ByteSummingVisitor extends SimpleFileVisitor<Path> {

    private long total;

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
      if (attrs.isRegularFile()) {
        total += attrs.size();
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) {
      // A vanished or unreadable entry (a concurrent delete, a broken symlink) does not count.
      return FileVisitResult.CONTINUE;
    }
  }
}
