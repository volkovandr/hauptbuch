package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.analytics.repository.ReportRepository;

/**
 * Unit tier (CLAUDE.md §6): {@link ReportService}'s own orchestration and validation logic — the
 * repository is mocked (thin by design, no DB). Round-tripping the {@code report} table and the
 * jsonb spec is {@link ReportRepositoryIntegrationTest}'s job.
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

  @Mock ReportRepository reportRepository;

  private ReportService reportService() {
    return new ReportService(reportRepository);
  }

  @Test
  void saveRejectsBlankName() {
    ReportService service = reportService();

    assertThatThrownBy(() -> service.save(" ", Presets.balanceSheet(), Renderer.TABLE, false))
        .isInstanceOf(IllegalArgumentException.class);
    verify(reportRepository, never()).insert(any(), any(), any(), anyBoolean());
  }

  @Test
  void saveStripsTheNameBeforeInserting() {
    ReportService service = reportService();
    when(reportRepository.insert(eq("Trimmed"), any(), any(), anyBoolean()))
        .thenReturn(new SavedReport(1L, "Trimmed", Presets.balanceSheet(), Renderer.TABLE, false));

    service.save("  Trimmed  ", Presets.balanceSheet(), Renderer.TABLE, false);

    verify(reportRepository).insert("Trimmed", Presets.balanceSheet(), Renderer.TABLE, false);
  }

  @Test
  void updateSpecRejectsAnUnknownId() {
    ReportService service = reportService();
    when(reportRepository.findById(404L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.updateSpec(404L, "New name", Presets.balanceSheet()))
        .isInstanceOf(IllegalArgumentException.class);
    verify(reportRepository, never()).update(anyLong(), any(), any());
  }

  @Test
  void updateSpecRejectsBlankName() {
    ReportService service = reportService();
    when(reportRepository.findById(1L))
        .thenReturn(
            Optional.of(
                new SavedReport(1L, "Existing", Presets.balanceSheet(), Renderer.TABLE, false)));

    assertThatThrownBy(() -> service.updateSpec(1L, "", Presets.balanceSheet()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void updateSpecDelegatesToTheRepositoryWithTheTrimmedName() {
    ReportService service = reportService();
    when(reportRepository.findById(1L))
        .thenReturn(
            Optional.of(
                new SavedReport(1L, "Existing", Presets.balanceSheet(), Renderer.TABLE, false)));

    service.updateSpec(1L, "  Renamed  ", Presets.categoryMonthMatrix());

    verify(reportRepository).update(1L, "Renamed", Presets.categoryMonthMatrix());
  }

  @Test
  void findDelegatesToTheRepository() {
    ReportService service = reportService();
    when(reportRepository.findById(1L)).thenReturn(Optional.empty());

    assertThat(service.find(1L)).isEmpty();
  }

  @Test
  void listDelegatesToTheRepository() {
    ReportService service = reportService();
    SavedReport saved =
        new SavedReport(1L, "Existing", Presets.balanceSheet(), Renderer.TABLE, false);
    when(reportRepository.findAll()).thenReturn(List.of(saved));

    assertThat(service.list()).containsExactly(saved);
  }

  @Test
  void deleteDelegatesToTheRepository() {
    ReportService service = reportService();

    service.delete(5L);

    verify(reportRepository).delete(5L);
  }
}
