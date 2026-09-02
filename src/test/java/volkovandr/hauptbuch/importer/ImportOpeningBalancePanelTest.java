package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.importer.repository.ImportAccountRepository;
import volkovandr.hauptbuch.importer.repository.ImportStatisticsRepository;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.OpeningBalanceView;

/**
 * Unit tier (CLAUDE.md §6): {@link ImportOpeningBalancePanel} pairs Money's staged opening balance
 * with the mapped Hauptbuch account's own, formats both German-style, and folds in the proposal —
 * the earlier-dated winner, ties toward the non-zero one (import.md §5.1; plan c3). Repositories
 * and the ledger mocked.
 */
@ExtendWith(MockitoExtension.class)
class ImportOpeningBalancePanelTest {

  private static final long SESSION_ID = 1L;

  @Mock ImportAccountRepository importAccountRepository;
  @Mock ImportStatisticsRepository importStatisticsRepository;
  @Mock PersonService personService;
  @Mock LedgerService ledgerService;

  private ImportOpeningBalancePanel panel() {
    return new ImportOpeningBalancePanel(
        importAccountRepository, importStatisticsRepository, personService, ledgerService);
  }

  private static ImportAccount mapRow(long id, String name, Long accountId) {
    return new ImportAccount(id, SESSION_ID, name, accountId, null, null, true, null, null);
  }

  private static ImportAccount recorded(long id, String name, String choice, BigDecimal amount) {
    return new ImportAccount(id, SESSION_ID, name, 42L, null, null, true, choice, amount);
  }

  private static ImportStagedOpeningBalance staged(String name, String date, String amount) {
    return new ImportStagedOpeningBalance(name, LocalDate.parse(date), new BigDecimal(amount));
  }

  private void mapRowsAre(ImportAccount... rows) {
    when(importAccountRepository.findBySession(SESSION_ID)).thenReturn(List.of(rows));
  }

  @Test
  void pairsBothSidesFormatsThemAndProposesTheEarlierDatedWinner() {
    mapRowsAre(mapRow(10L, "Current Account", 42L));
    when(importStatisticsRepository.stagedOpeningBalances(SESSION_ID))
        .thenReturn(List.of(staged("Current Account", "2004-01-01", "1234.56")));
    when(ledgerService.openingBalanceOf(42L))
        .thenReturn(
            Optional.of(new OpeningBalanceView(LocalDate.of(2005, 6, 1), new BigDecimal("0.00"))));

    ImportOpeningBalanceCells cells = panel().forSession(SESSION_ID).get(10L);

    assertThat(cells.moneyOpeningBalance()).isEqualTo("1.234,56 · 01.01.2004");
    assertThat(cells.hauptbuchOpeningBalance()).isEqualTo("0,00 · 01.06.2005");
    assertThat(cells.proposal()).isEqualTo(OpeningBalanceChoice.TAKE_MONEY);
    assertThat(cells.reconciles()).isTrue();
    assertThat(cells.conflict()).isTrue();
  }

  @Test
  void rowWithoutStagedOpeningBalanceGetsEmptyCells() {
    mapRowsAre(mapRow(10L, "Cash", 42L));
    when(importStatisticsRepository.stagedOpeningBalances(SESSION_ID)).thenReturn(List.of());

    ImportOpeningBalanceCells cells = panel().forSession(SESSION_ID).get(10L);

    assertThat(cells.moneyOpeningBalance()).isNull();
    assertThat(cells.proposal()).isNull();
    assertThat(cells.reconciles()).isFalse();
  }

  @Test
  void doesNotAskTheLedgerForPersonLeafTarget() {
    mapRowsAre(mapRow(10L, "Loan to Max", 99L));
    when(personService.personNamesForAccounts(anyList())).thenReturn(Map.of(99L, "Max"));
    when(importStatisticsRepository.stagedOpeningBalances(SESSION_ID))
        .thenReturn(List.of(staged("Loan to Max", "2004-01-01", "50.00")));

    ImportOpeningBalanceCells cells = panel().forSession(SESSION_ID).get(10L);

    assertThat(cells.hauptbuchOpeningBalance()).isNull();
    assertThat(cells.proposal()).isEqualTo(OpeningBalanceChoice.TAKE_MONEY);
    verify(ledgerService, never()).openingBalanceOf(anyLong());
  }

  @Test
  void carriesTheRecordedOverrideAmountThroughGermanFormatted() {
    mapRowsAre(
        recorded(10L, "Current Account", OpeningBalanceChoice.OVERRIDE, new BigDecimal("1234.56")));
    when(importStatisticsRepository.stagedOpeningBalances(SESSION_ID))
        .thenReturn(List.of(staged("Current Account", "2004-01-01", "10.00")));
    when(ledgerService.openingBalanceOf(42L)).thenReturn(Optional.empty());

    ImportOpeningBalanceCells cells = panel().forSession(SESSION_ID).get(10L);

    assertThat(cells.recordedChoice()).isEqualTo(OpeningBalanceChoice.OVERRIDE);
    assertThat(cells.recordedAmount()).isEqualTo("1.234,56");
  }
}
