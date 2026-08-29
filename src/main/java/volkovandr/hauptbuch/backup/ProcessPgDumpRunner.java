package volkovandr.hauptbuch.backup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs the real {@code pg_dump} binary.
 *
 * <p>{@code pg_dump} is a host dependency: it ships with the PostgreSQL packages already installed
 * on the Pi, and its version must be at least the server's. A missing or too-old binary surfaces as
 * a {@link BackupFailedException} carrying the tool's own message, which is the most useful thing
 * to put in front of the operator.
 *
 * <p>The password is passed in the subprocess <em>environment</em> as {@code PGPASSWORD}, never as
 * a command argument: a process's arguments are readable by any user on the box via {@code ps},
 * while its environment is not.
 *
 * <p><strong>The tool's output goes to a file, not a pipe.</strong> Reading a pipe to EOF only
 * returns once the child has exited, so draining it before {@link Process#waitFor(long, TimeUnit)}
 * would make the timeout unreachable — a {@code pg_dump} blocked on a lock or an unresponsive
 * server would hang the caller forever. That matters more than it first looks: the scheduled path
 * runs on Boot's single-threaded scheduler, which also drives the receipt batch poller, so one
 * stuck dump would stop receipt polling for good. Redirecting to a file lets the timeout actually
 * fire, and cannot deadlock on a full pipe buffer either.
 */
@Component
class ProcessPgDumpRunner implements PgDumpRunner {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessPgDumpRunner.class);

  /** Generous: a Pi dumping a large ledger over a slow card should not be cut off mid-write. */
  private static final long TIMEOUT_MINUTES = 30L;

  /**
   * Enough of the tool's output to diagnose a failure, without pasting a wall of text into the UI.
   */
  private static final int MAX_OUTPUT_CHARS = 500;

  @Override
  public void dump(DatabaseTarget target, Path destination) {
    Path output = createOutputFile();
    try {
      ProcessBuilder builder = new ProcessBuilder(commandFor(target, destination));
      if (target.password() != null) {
        builder.environment().put("PGPASSWORD", target.password());
      }
      builder.redirectErrorStream(true);
      builder.redirectOutput(output.toFile());
      LOG.debug("Dumping {} to {}", target, destination);
      run(builder, target, output);
    } finally {
      deleteQuietly(output);
    }
  }

  /**
   * The custom-format dump: compressed, and the only format {@code pg_restore} can restore
   * selectively. Everything the tool needs is an argument except the password.
   */
  private static List<String> commandFor(DatabaseTarget target, Path destination) {
    return List.of(
        "pg_dump",
        "--format=custom",
        "--host=" + target.host(),
        "--port=" + target.port(),
        "--username=" + target.username(),
        "--dbname=" + target.database(),
        "--file=" + destination);
  }

  // PMD.DoNotUseThreads: a J2EE-era rule against spawning threads. What it flags here is
  // Thread.currentThread().interrupt(), which is the *correct* handling of InterruptedException —
  // restoring the flag rather than swallowing it.
  @SuppressWarnings("PMD.DoNotUseThreads")
  private static void run(ProcessBuilder builder, DatabaseTarget target, Path output) {
    Process process = start(builder);
    try {
      if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
        process.destroyForcibly();
        throw new BackupFailedException(
            "pg_dump did not finish within " + TIMEOUT_MINUTES + " minutes.");
      }
      if (process.exitValue() != 0) {
        throw new BackupFailedException(
            "pg_dump failed for " + target.database() + ": " + truncate(read(output)));
      }
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new BackupFailedException("The backup was interrupted.", e);
    }
  }

  private static Process start(ProcessBuilder builder) {
    try {
      return builder.start();
    } catch (IOException e) {
      throw new BackupFailedException(
          "pg_dump could not be run — is the PostgreSQL client installed and on PATH?", e);
    }
  }

  private static Path createOutputFile() {
    try {
      return Files.createTempFile("hauptbuch-pg-dump", ".log");
    } catch (IOException e) {
      throw new BackupFailedException("Could not prepare the pg_dump log file.", e);
    }
  }

  /** The tool's merged stdout/stderr, for a failure message. */
  private static String read(Path output) {
    try {
      return Files.readString(output, StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      return "";
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      LOG.debug("Could not remove the pg_dump log file {}", path, e);
    }
  }

  private static String truncate(String output) {
    if (output.isEmpty()) {
      return "no output";
    }
    return output.length() <= MAX_OUTPUT_CHARS
        ? output
        : output.substring(0, MAX_OUTPUT_CHARS) + "…";
  }
}
