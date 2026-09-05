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
import volkovandr.hauptbuch.importer.repository.ImportMirrorRepository;

/**
 * Unit tier (CLAUDE.md §6): {@link ImportCrossCurrencyParkService} with {@link
 * ImportMirrorRepository} mocked — the review's cross-currency panel (import.md §9), the manual
 * match (§6.5) and hand-entered far amount (§6.4) guards, the no-open-session guard, and that a
 * successful resolution triggers {@link ImportCrossCurrencyRateWriteBackService} (plan e3, §6.3)
 * while a rejected one does not. That service's own candidate-forwarding logic is covered in {@link
 * ImportCrossCurrencyRateWriteBackServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class ImportCrossCurrencyParkServiceTest {

  private static final long SESSION_ID = 1L;

  @Mock ImportSessionService importSessionService;
  @Mock ImportMirrorRepository importMirrorRepository;
  @Mock ImportCrossCurrencyRateWriteBackService rateWriteBackService;

  private ImportCrossCurrencyParkService service() {
    return new ImportCrossCurrencyParkService(
        importSessionService, importMirrorRepository, rateWriteBackService);
  }

  private void openSession() {
    when(importSessionService.currentSession())
        .thenReturn(
            Optional.of(
                new ImportSession(
                    SESSION_ID, ImportSessionState.OPEN, null, null, OffsetDateTime.now(), null)));
  }

  @Test
  void parksIsEmptyWithoutAnOpenSession() {
    when(importSessionService.currentSession()).thenReturn(Optional.empty());

    assertThat(service().parks()).isEmpty();
    verifyNoInteractions(importMirrorRepository);
  }

  @Test
  void parksDelegatesToTheRepositoryForTheOpenSession() {
    openSession();
    ImportCrossCurrencyPark park =
        new ImportCrossCurrencyPark(
            10L, 20L, LocalDate.of(2016, 6, 6), "Euro", "Franc", new BigDecimal("100.00"), true);
    when(importMirrorRepository.parkedCrossCurrencyLegs(SESSION_ID)).thenReturn(List.of(park));

    assertThat(service().parks()).containsExactly(park);
  }

  @Test
  void parksForSessionDelegatesDirectlyWithoutLookingUpTheCurrentSessionAgain() {
    ImportCrossCurrencyPark park =
        new ImportCrossCurrencyPark(
            10L, 20L, LocalDate.of(2016, 6, 6), "Euro", "Franc", new BigDecimal("100.00"), true);
    when(importMirrorRepository.parkedCrossCurrencyLegs(SESSION_ID)).thenReturn(List.of(park));

    assertThat(service().parksForSession(SESSION_ID)).containsExactly(park);
    verifyNoInteractions(importSessionService);
  }

  @Test
  void manualMatchDelegatesToTheRepository() {
    openSession();
    when(importMirrorRepository.manualMatch(SESSION_ID, 10L, 20L)).thenReturn(true);

    service().manualMatch(10L, 20L);

    verify(importMirrorRepository).manualMatch(SESSION_ID, 10L, 20L);
  }

  @Test
  void manualMatchTriggersTheRateWriteBackAfterSuccessfulMatch() {
    openSession();
    when(importMirrorRepository.manualMatch(SESSION_ID, 10L, 20L)).thenReturn(true);

    service().manualMatch(10L, 20L);

    verify(rateWriteBackService).writeBackObservedRates(SESSION_ID);
  }

  @Test
  void manualMatchRejectsPairTheRepositoryRefuses() {
    openSession();
    when(importMirrorRepository.manualMatch(SESSION_ID, 10L, 20L)).thenReturn(false);

    assertThatThrownBy(() -> service().manualMatch(10L, 20L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("10")
        .hasMessageContaining("20");

    verifyNoInteractions(rateWriteBackService);
  }

  @Test
  void manualMatchWithoutAnOpenSessionIsRejected() {
    when(importSessionService.currentSession()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().manualMatch(10L, 20L))
        .isInstanceOf(IllegalStateException.class);

    verifyNoInteractions(importMirrorRepository);
    verifyNoInteractions(rateWriteBackService);
  }

  @Test
  void closeParkWithFarAmountDelegatesToTheRepository() {
    openSession();
    BigDecimal amount = new BigDecimal("150.00");
    when(importMirrorRepository.closeParkWithFarAmount(SESSION_ID, 10L, amount)).thenReturn(true);

    service().closeParkWithFarAmount(10L, amount);

    verify(importMirrorRepository).closeParkWithFarAmount(SESSION_ID, 10L, amount);
  }

  @Test
  void closeParkWithFarAmountTriggersTheRateWriteBackAfterSuccessfulClose() {
    openSession();
    BigDecimal amount = new BigDecimal("150.00");
    when(importMirrorRepository.closeParkWithFarAmount(SESSION_ID, 10L, amount)).thenReturn(true);

    service().closeParkWithFarAmount(10L, amount);

    verify(rateWriteBackService).writeBackObservedRates(SESSION_ID);
  }

  @Test
  void closeParkWithFarAmountNeedsAnAmount() {
    openSession();

    assertThatThrownBy(() -> service().closeParkWithFarAmount(10L, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("amount");

    verifyNoInteractions(importMirrorRepository);
    verifyNoInteractions(rateWriteBackService);
  }

  @Test
  void closeParkWithFarAmountRejectsPostingTheRepositoryRefuses() {
    openSession();
    BigDecimal amount = new BigDecimal("150.00");
    when(importMirrorRepository.closeParkWithFarAmount(SESSION_ID, 10L, amount)).thenReturn(false);

    assertThatThrownBy(() -> service().closeParkWithFarAmount(10L, amount))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("10");

    verifyNoInteractions(rateWriteBackService);
  }

  @Test
  void closeParkWithFarAmountWithoutAnOpenSessionIsRejected() {
    when(importSessionService.currentSession()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().closeParkWithFarAmount(10L, new BigDecimal("1.00")))
        .isInstanceOf(IllegalStateException.class);

    verifyNoInteractions(importMirrorRepository);
    verifyNoInteractions(rateWriteBackService);
  }
}
