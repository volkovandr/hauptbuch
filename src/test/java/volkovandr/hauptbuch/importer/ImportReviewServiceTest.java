package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
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
import volkovandr.hauptbuch.importer.repository.ImportStatisticsRepository;

/**
 * Unit tier (CLAUDE.md §6): {@link ImportReviewService} render-model assembly with its
 * collaborators mocked — it formats the per-account statistics (German number, {@code dd.MM.yyyy}
 * span) and folds in the account-map panel, both for the open campaign's session id.
 */
@ExtendWith(MockitoExtension.class)
class ImportReviewServiceTest {

  private static final long SESSION_ID = 7L;

  @Mock ImportSessionService importSessionService;
  @Mock ImportStatisticsRepository importStatisticsRepository;
  @Mock ImportAccountMapService importAccountMapService;

  private ImportReviewService service() {
    return new ImportReviewService(
        importSessionService, importStatisticsRepository, importAccountMapService);
  }

  private void openSession() {
    when(importSessionService.currentSession())
        .thenReturn(
            Optional.of(
                new ImportSession(
                    SESSION_ID, ImportSessionState.OPEN, null, null, OffsetDateTime.now(), null)));
  }

  @Test
  void withoutAnOpenCampaignThereIsNoReview() {
    when(importSessionService.currentSession()).thenReturn(Optional.empty());

    assertThat(service().review()).isEmpty();
    verifyNoInteractions(importStatisticsRepository, importAccountMapService);
  }

  @Test
  void formatsTheStatisticsAndFoldsInTheAccountMapForTheOpenSession() {
    openSession();
    when(importStatisticsRepository.perMoneyAccount(SESSION_ID))
        .thenReturn(
            List.of(
                new ImportAccountStatistics(
                    "Current Account",
                    2,
                    new BigDecimal("-17.34"),
                    LocalDate.of(2004, 7, 1),
                    LocalDate.of(2004, 7, 28))));
    ImportAccountMap panel =
        new ImportAccountMap(
            List.of(new ImportAccountMap.Row(1L, "Current Account", null, null, "asset")),
            List.of(),
            List.of());
    when(importAccountMapService.mapPanel(SESSION_ID)).thenReturn(panel);

    ImportReview review = service().review().orElseThrow();

    assertThat(review.empty()).isFalse();
    assertThat(review.accounts())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.moneyAccountName()).isEqualTo("Current Account");
              assertThat(row.transactionCount()).isEqualTo(2);
              assertThat(row.netSum()).isEqualTo("-17,34");
              assertThat(row.dateRange()).isEqualTo("01.07.2004 – 28.07.2004");
            });
    assertThat(review.accountMap()).isSameAs(panel);
  }

  @Test
  void collapsesDateSpanToOneDateWhenStagedDatesMatch() {
    openSession();
    when(importStatisticsRepository.perMoneyAccount(SESSION_ID))
        .thenReturn(
            List.of(
                new ImportAccountStatistics(
                    "Cash",
                    1,
                    new BigDecimal("5.00"),
                    LocalDate.of(2010, 3, 4),
                    LocalDate.of(2010, 3, 4))));
    when(importAccountMapService.mapPanel(SESSION_ID))
        .thenReturn(new ImportAccountMap(List.of(), List.of(), List.of()));

    ImportReview review = service().review().orElseThrow();

    assertThat(review.accounts())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.dateRange()).isEqualTo("04.03.2010");
            });
  }
}
