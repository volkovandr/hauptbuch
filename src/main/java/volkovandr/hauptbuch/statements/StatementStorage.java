package volkovandr.hauptbuch.statements;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * The on-disk home of uploaded statement files (ARCH-07): {@code <yyyy>/<MM>/<stamp>-<name>}. The
 * file is the evidence a statement came from, so it is written once and never edited; deleting a
 * statement leaves it on the Pi (statements.md §5).
 */
@Component
public class StatementStorage {

  /** Hard upload cap: 15 MB, re-checked here so the message is ours. */
  static final long MAX_BYTES = 15L * 1024 * 1024;

  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");
  private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy/MM");

  private final Path root;
  private final Clock clock;

  StatementStorage(StatementStorageProperties properties, Clock clock) {
    this.root = properties.storageRoot().toAbsolutePath().normalize();
    this.clock = clock;
  }

  /**
   * Write an uploaded file and return its <em>root-relative</em> path.
   *
   * @throws StatementFormatException if the file is empty or over the size cap
   */
  public String store(String originalFilename, byte[] bytes) {
    if (bytes.length == 0) {
      throw new StatementFormatException("No file was attached — pick a file and try again.");
    }
    if (bytes.length > MAX_BYTES) {
      throw new StatementFormatException("That file is larger than the 15 MB limit.");
    }
    LocalDateTime now = LocalDateTime.now(clock);
    String rel =
        now.format(YEAR_MONTH) + "/" + now.format(STAMP) + "-" + safeName(originalFilename);
    Path target = resolve(rel);
    try {
      Files.createDirectories(root.resolve(now.format(YEAR_MONTH)));
      Files.write(target, bytes);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write " + target, e);
    }
    return rel;
  }

  /**
   * The bytes of a stored file.
   *
   * @throws StatementFormatException if no such file is stored
   */
  public byte[] read(String relPath) {
    Path path = resolve(relPath);
    if (!Files.isRegularFile(path)) {
      throw new StatementFormatException(
          "That uploaded file is no longer there — upload it again.");
    }
    try {
      return Files.readAllBytes(path);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + path, e);
    }
  }

  /** The file name without any directory part or characters a path could misuse. */
  private static String safeName(String originalFilename) {
    String name = originalFilename == null ? "" : originalFilename;
    name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
    name = name.replaceAll("[^A-Za-z0-9._-]", "_");
    return name.isEmpty() ? "statement" : name;
  }

  /** Resolve a root-relative path against the storage root, refusing to escape it. */
  private Path resolve(String relPath) {
    Path resolved = root.resolve(relPath).normalize();
    if (!resolved.startsWith(root)) {
      throw new StatementFormatException("That is not a stored statement file.");
    }
    return resolved;
  }
}
