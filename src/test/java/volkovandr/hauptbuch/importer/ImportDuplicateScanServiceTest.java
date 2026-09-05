package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.importer.repository.ImportDuplicateScanRepository;

/**
 * Unit tier (CLAUDE.md §6): {@link ImportDuplicateScanService} with {@link
 * ImportDuplicateScanRepository} mocked — the decision validation, the no-open-session guard, the
 * unknown-match rejection, and the panel's {@code everRun} / {@code stale} / {@code cleared}
 * assembly from mocked repository values. The detection SQL and the re-run reconcile live in {@link
 * ImportDuplicateScanSqlLogicTest}.
 */
@ExtendWith(MockitoExtension.class)
class ImportDuplicateScanServiceTest {

  private static final long SESSION_ID = 7L;
  private static final long SCAN_ID = 42L;
  private static final OffsetDateTime RAN_AT = OffsetDateTime.parse("2026-09-01T14:03:00Z");

  @Mock ImportSessionService importSessionService;
  @Mock ImportDuplicateScanRepository repository;

  private ImportDuplicateScanService service() {
    return new ImportDuplicateScanService(importSessionService, repository);
  }

  private void openSession() {
    when(importSessionService.currentSession())
        .thenReturn(
            Optional.of(
                new ImportSession(
                    SESSION_ID, ImportSessionState.OPEN, null, null, OffsetDateTime.now(), null)));
  }

  @Test
  void runScanRequiresAnOpenSession() {
    when(importSessionService.currentSession()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().runScan()).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(repository);
  }

  @Test
  void runScanDelegatesToTheRepository() {
    openSession();
    when(repository.rescan(SESSION_ID)).thenReturn(3);

    service().runScan();

    verify(repository).rescan(SESSION_ID);
  }

  @Test
  void adjudicateRejectsAnUnknownDecision() {
    assertThatThrownBy(() -> service().adjudicate(1L, "maybe"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(importSessionService, repository);
  }

  @Test
  void adjudicateRejectsWhenTheScanHasNeverRun() {
    openSession();
    when(repository.findScan(SESSION_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().adjudicate(1L, "skip"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Run the duplicate scan");
  }

  @Test
  void adjudicateRejectsMatchNotOnTheCurrentScan() {
    openSession();
    when(repository.findScan(SESSION_ID))
        .thenReturn(Optional.of(new ImportDuplicateScanRow(SCAN_ID, RAN_AT)));
    when(repository.adjudicate(SCAN_ID, 99L, "import")).thenReturn(false);

    assertThatThrownBy(() -> service().adjudicate(99L, "import"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not on the current duplicate scan");
  }

  @Test
  void adjudicatePassesValidDecisionThrough() {
    openSession();
    when(repository.findScan(SESSION_ID))
        .thenReturn(Optional.of(new ImportDuplicateScanRow(SCAN_ID, RAN_AT)));
    when(repository.adjudicate(SCAN_ID, 5L, "skip")).thenReturn(true);

    service().adjudicate(5L, "skip");

    verify(repository).adjudicate(SCAN_ID, 5L, "skip");
  }

  @Test
  void panelIsEmptyWhenTheScanHasNeverRun() {
    when(repository.findScan(SESSION_ID)).thenReturn(Optional.empty());

    ImportDuplicateScan panel = service().panelFor(SESSION_ID);

    assertThat(panel.everRun()).isFalse();
    assertThat(panel.cleared()).isFalse();
  }

  @Test
  void panelSplitsPendingFromAdjudicatedAndIsNotStaleWhenNoActivityPostdatesTheScan() {
    when(repository.findScan(SESSION_ID))
        .thenReturn(Optional.of(new ImportDuplicateScanRow(SCAN_ID, RAN_AT)));
    when(repository.latestLedgerMutation()).thenReturn(Optional.of(RAN_AT.minusHours(1)));
    when(repository.findMatchRows(SCAN_ID))
        .thenReturn(List.of(match(1L, "pending"), match(2L, "skip")));

    ImportDuplicateScan panel = service().panelFor(SESSION_ID);

    assertThat(panel.stale()).isFalse();
    assertThat(panel.pending())
        .extracting(ImportDuplicateScan.MatchRow::matchId)
        .containsExactly(1L);
    assertThat(panel.adjudicated())
        .extracting(ImportDuplicateScan.MatchRow::matchId)
        .containsExactly(2L);
    assertThat(panel.cleared()).isFalse(); // a pending match remains
  }

  @Test
  void panelIsStaleWhenLedgerActivityPostdatesTheScan() {
    when(repository.findScan(SESSION_ID))
        .thenReturn(Optional.of(new ImportDuplicateScanRow(SCAN_ID, RAN_AT)));
    when(repository.latestLedgerMutation()).thenReturn(Optional.of(RAN_AT.plusMinutes(5)));
    when(repository.findMatchRows(SCAN_ID)).thenReturn(List.of());

    ImportDuplicateScan panel = service().panelFor(SESSION_ID);

    assertThat(panel.stale()).isTrue();
    assertThat(panel.cleared()).isFalse();
  }

  @Test
  void panelIsClearedWhenCurrentWithNoPendingMatches() {
    when(repository.findScan(SESSION_ID))
        .thenReturn(Optional.of(new ImportDuplicateScanRow(SCAN_ID, RAN_AT)));
    when(repository.latestLedgerMutation()).thenReturn(Optional.of(RAN_AT.minusSeconds(1)));
    when(repository.findMatchRows(SCAN_ID)).thenReturn(List.of(match(1L, "skip")));

    ImportDuplicateScan panel = service().panelFor(SESSION_ID);

    assertThat(panel.cleared()).isTrue();
    assertThat(panel.ranClean()).isFalse();
  }

  private static ImportDuplicateMatch match(long matchId, String adjudication) {
    return new ImportDuplicateMatch(
        matchId,
        100L + matchId,
        200L + matchId,
        LocalDate.of(2024, 3, 1),
        "Current Account",
        new BigDecimal("-12.34"),
        "Grocer",
        "Grocer",
        "weekly shop",
        adjudication);
  }
}
