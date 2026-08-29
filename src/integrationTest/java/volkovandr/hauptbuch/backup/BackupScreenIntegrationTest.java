package volkovandr.hauptbuch.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import volkovandr.hauptbuch.TestcontainersConfiguration;

/**
 * Integration tier (§1.5): the backup screen driven through its controller — listing, taking one by
 * hand, deleting, downloading, and the failure path.
 *
 * <p>{@code pg_dump} is mocked at the {@link PgDumpRunner} seam here on purpose: this test is about
 * the screen, and making every assertion wait on a real subprocess would make it slow and dependent
 * on a binary. The real binary has its own test, {@link PgDumpRunnerIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BackupScreenIntegrationTest {

  private static final Path STORAGE_ROOT =
      Path.of(System.getProperty("java.io.tmpdir"), "hauptbuch-backup-screen-test");

  @Autowired MockMvc mockMvc;
  @Autowired BackupService backupService;
  @MockitoBean PgDumpRunner pgDumpRunner;

  @DynamicPropertySource
  static void storageRoot(DynamicPropertyRegistry registry) {
    registry.add("hauptbuch.backup.storage-root", STORAGE_ROOT::toString);
  }

  @BeforeEach
  void setUp() throws IOException {
    clearStorage();
    // A successful dump leaves a file where it was told to.
    Answer<Void> writeDump =
        invocation -> {
          Files.writeString(invocation.getArgument(1, Path.class), "PGDMP pretend");
          return null;
        };
    doAnswer(writeDump).when(pgDumpRunner).dump(any(), any());
  }

  @AfterEach
  void tearDown() throws IOException {
    clearStorage();
  }

  private static void clearStorage() throws IOException {
    if (!Files.isDirectory(STORAGE_ROOT)) {
      return;
    }
    try (Stream<Path> entries = Files.list(STORAGE_ROOT)) {
      for (Path entry : entries.toList()) {
        Files.deleteIfExists(entry);
      }
    }
  }

  private static void seed(BackupKind kind, LocalDateTime at) throws IOException {
    Files.createDirectories(STORAGE_ROOT);
    Files.writeString(STORAGE_ROOT.resolve(fileName(kind, at)), "PGDMP seeded");
  }

  /** The production naming — same package, so the test names files exactly as the app does. */
  private static String fileName(BackupKind kind, LocalDateTime at) {
    return BackupNames.fileNameFor(kind, at);
  }

  @Test
  void showsEmptyStateAndTheDatabaseBeingBackedUp() throws Exception {
    mockMvc
        .perform(get("/backup"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("No backups yet")))
        .andExpect(content().string(containsString("Take a backup now")))
        // The images caveat has to be on the screen: a restore leaves receipt images behind.
        .andExpect(content().string(containsString("Receipt images")));
  }

  @Test
  void takingBackupRedirectsAndListsIt() throws Exception {
    mockMvc
        .perform(post("/backup"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/backup"));

    assertThat(backupService.list()).hasSize(1);
    assertThat(backupService.list().getFirst().kind()).isEqualTo(BackupKind.MANUAL);

    mockMvc
        .perform(get("/backup"))
        .andExpect(content().string(containsString("Manual")))
        .andExpect(content().string(containsString("Download")));
  }

  @Test
  void failedDumpShowsMessageInsteadOfBlowingUp() throws Exception {
    doThrow(new BackupFailedException("pg_dump is not on PATH"))
        .when(pgDumpRunner)
        .dump(any(), any());

    mockMvc.perform(post("/backup")).andExpect(status().is3xxRedirection());

    assertThat(backupService.list()).isEmpty();
  }

  @Test
  void listingShowsRestoreCommandsCarryingTheFileName() throws Exception {
    LocalDateTime at = LocalDateTime.of(2026, 8, 29, 13, 45, 0);
    seed(BackupKind.MANUAL, at);

    mockMvc
        .perform(get("/backup"))
        .andExpect(status().isOk())
        // The whole point of the panel: the command is ready to copy, not a template to fill in.
        .andExpect(content().string(containsString(fileName(BackupKind.MANUAL, at))))
        .andExpect(content().string(containsString("systemctl stop hauptbuch")))
        .andExpect(content().string(containsString("pg_restore")))
        .andExpect(content().string(containsString("29.08.2026 13:45")));
  }

  @Test
  void downloadsBackupAsAttachment() throws Exception {
    LocalDateTime at = LocalDateTime.of(2026, 8, 29, 13, 45, 0);
    seed(BackupKind.AUTOMATIC, at);

    mockMvc
        .perform(get("/backup/{name}/download", fileName(BackupKind.AUTOMATIC, at)))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string("Content-Disposition", containsString(fileName(BackupKind.AUTOMATIC, at))))
        .andExpect(content().string(containsString("PGDMP")));
  }

  @Test
  void downloadRefusesNameThatIsNotBackup() throws Exception {
    mockMvc
        .perform(get("/backup/{name}/download", "hauptbuch-19990101-000000-auto.dump"))
        .andExpect(status().isNotFound());
  }

  @Test
  void deletesBackup() throws Exception {
    LocalDateTime at = LocalDateTime.of(2026, 8, 29, 13, 45, 0);
    seed(BackupKind.MANUAL, at);
    seed(BackupKind.AUTOMATIC, at.minusDays(1));

    mockMvc
        .perform(post("/backup/{name}/delete", fileName(BackupKind.MANUAL, at)))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/backup"));

    assertThat(backupService.list())
        .extracting(BackupFile::kind)
        .containsExactly(BackupKind.AUTOMATIC);
  }

  @Test
  void settingsLinksToTheBackupScreen() throws Exception {
    mockMvc
        .perform(get("/settings"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("/backup")));
  }
}
