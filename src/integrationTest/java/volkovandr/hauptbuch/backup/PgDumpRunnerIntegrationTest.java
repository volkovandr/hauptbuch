package volkovandr.hauptbuch.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import volkovandr.hauptbuch.TestcontainersConfiguration;

/**
 * Integration tier (§1.5): the one test that runs the actual {@code pg_dump} binary, against the
 * Testcontainers database, and proves the file it produces is a real restorable dump.
 *
 * <p><strong>Skipped when {@code pg_dump} is not on {@code PATH}.</strong> This feature introduces
 * a host dependency the build never had, and failing the whole suite on a machine that simply has
 * no PostgreSQL client installed would be a poor trade. The consequence is worth stating plainly:
 * on such a machine a green build does <em>not</em> prove the dump path works — everything else
 * about backups is covered, but this is the piece that goes untested.
 *
 * <p>The client must be at least the server's major version, which is the usual cause of a failure
 * here when one does appear.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PgDumpRunnerIntegrationTest {

  /** The first five bytes of every PostgreSQL custom-format dump. */
  private static final String CUSTOM_FORMAT_MAGIC = "PGDMP";

  @TempDir Path temp;

  @Autowired ProcessPgDumpRunner runner;
  @Autowired DatabaseTargetResolver targetResolver;

  @Test
  void dumpsSchemaIntoRestorableFile() throws IOException, InterruptedException {
    assumeTrue(toolOnPath("pg_dump"), "pg_dump is not installed on this machine");
    Path destination = temp.resolve("test.dump");

    runner.dump(targetResolver.resolve(), destination);

    assertThat(destination).exists();
    assertThat(Files.size(destination)).isPositive();
    assertThat(magicOf(destination)).isEqualTo(CUSTOM_FORMAT_MAGIC);
  }

  @Test
  void dumpContainsTheLedgerSchema() throws IOException, InterruptedException {
    assumeTrue(toolOnPath("pg_dump"), "pg_dump is not installed on this machine");
    assumeTrue(toolOnPath("pg_restore"), "pg_restore is not installed on this machine");
    Path destination = temp.resolve("schema.dump");
    runner.dump(targetResolver.resolve(), destination);

    // pg_restore --list reads the archive's table of contents: proof the file is not merely
    // non-empty but a dump pg_restore can actually work with, carrying the migrated schema.
    String contents = listArchive(destination);

    assertThat(contents).contains("posting");
    assertThat(contents).contains("transaction");
  }

  @Test
  void failureCarriesTheToolsOwnMessage() throws InterruptedException {
    assumeTrue(toolOnPath("pg_dump"), "pg_dump is not installed on this machine");
    DatabaseTarget wrongDatabase =
        new DatabaseTarget(
            "localhost", targetResolver.resolve().port(), "no_such_database", "nobody", "wrong");

    assertThatThrownBy(() -> runner.dump(wrongDatabase, temp.resolve("doomed.dump")))
        .isInstanceOf(BackupFailedException.class)
        .hasMessageContaining("no_such_database");
  }

  private static String magicOf(Path dump) throws IOException {
    byte[] head = new byte[CUSTOM_FORMAT_MAGIC.length()];
    try (InputStream in = Files.newInputStream(dump)) {
      assertThat(in.read(head)).isEqualTo(head.length);
    }
    return new String(head, StandardCharsets.UTF_8);
  }

  private static String listArchive(Path dump) throws IOException, InterruptedException {
    Process process =
        new ProcessBuilder("pg_restore", "--list", dump.toString())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    process.waitFor(1, TimeUnit.MINUTES);
    return output;
  }

  /**
   * Whether {@code tool} can be run at all. An {@link InterruptedException} is declared rather than
   * caught: nothing here should be interrupting the suite, and letting it fail the test is more
   * honest than reporting "the tool is missing".
   */
  private static boolean toolOnPath(String tool) throws InterruptedException {
    try {
      Process process = new ProcessBuilder(tool, "--version").redirectErrorStream(true).start();
      return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
    } catch (IOException e) {
      return false;
    }
  }
}
