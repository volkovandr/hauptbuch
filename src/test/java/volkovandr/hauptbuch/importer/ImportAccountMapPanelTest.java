package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.Person;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.importer.repository.ImportAccountRepository;
import volkovandr.hauptbuch.importer.repository.ImportFileRepository;
import volkovandr.hauptbuch.ledger.Currency;
import volkovandr.hauptbuch.ledger.CurrencyService;

/**
 * Unit tier (CLAUDE.md §6): {@link ImportAccountMapPanel} read-model assembly with its
 * collaborators mocked — resolved target names, the first-non-null proposed type, the many-to-one
 * target, the person-leaf target and {@code expect-file} flag (plan c1/c2), and the account /
 * person / currency option lists.
 */
@ExtendWith(MockitoExtension.class)
class ImportAccountMapPanelTest {

  private static final long SESSION_ID = 1L;

  @Mock ImportAccountRepository importAccountRepository;
  @Mock ImportFileRepository importFileRepository;
  @Mock AccountService accountService;
  @Mock PersonService personService;
  @Mock CurrencyService currencyService;

  private ImportAccountMapPanel panel() {
    return new ImportAccountMapPanel(
        importAccountRepository,
        importFileRepository,
        accountService,
        personService,
        currencyService);
  }

  private static ImportAccount unmapped(long id, String name) {
    return new ImportAccount(id, SESSION_ID, name, null, null, null, true, null, null);
  }

  private static ImportAccount mappedTo(long id, String name, long accountId) {
    return new ImportAccount(id, SESSION_ID, name, accountId, null, null, true, null, null);
  }

  private static Account account(long id, String name, String type) {
    return new Account(id, name, type, null, "EUR", null, null, null, null, false, false, false);
  }

  private static ImportFile file(String moneyAccountName, String proposedType) {
    return new ImportFile(
        1L,
        SESSION_ID,
        "export.qif",
        moneyAccountName,
        "utf_8",
        "day_month",
        proposedType,
        0,
        OffsetDateTime.now());
  }

  @Test
  void buildsPanelWithResolvedNamesProposedTypesAndManyToOneTarget() {
    when(importAccountRepository.findBySession(SESSION_ID))
        .thenReturn(
            List.of(
                unmapped(10L, "Cash"), mappedTo(11L, "Junk A", 42L), mappedTo(12L, "Junk B", 42L)));
    when(importFileRepository.findBySession(SESSION_ID))
        .thenReturn(
            List.of(file("Cash", "asset"), file("Junk A", null), file("Junk A", "liability")));
    when(accountService.manageableAccounts())
        .thenReturn(List.of(account(42L, "Everything Else", "asset")));
    when(personService.personNamesForAccounts(anyList())).thenReturn(Map.of());
    when(personService.findAllLive()).thenReturn(List.of(new Person(8L, "Max", null)));
    when(currencyService.findAll()).thenReturn(List.of(new Currency("EUR", 2, "€", "Euro")));

    ImportAccountMap result = panel().forSession(SESSION_ID);

    assertThat(result.rows())
        .satisfiesExactly(
            cash -> {
              assertThat(cash.moneyAccountName()).isEqualTo("Cash");
              assertThat(cash.mapped()).isFalse();
              assertThat(cash.targetName()).isNull();
              assertThat(cash.proposedType()).isEqualTo("asset");
            },
            junkA -> {
              assertThat(junkA.mapped()).isTrue();
              assertThat(junkA.targetAccountId()).isEqualTo(42L);
              assertThat(junkA.targetName()).isEqualTo("Everything Else");
              assertThat(junkA.personTarget()).isFalse();
              // first non-null header wins
              assertThat(junkA.proposedType()).isEqualTo("liability");
            },
            junkB -> {
              assertThat(junkB.targetName()).isEqualTo("Everything Else");
              assertThat(junkB.proposedType()).isNull();
            });
    assertThat(result.accountOptions())
        .singleElement()
        .satisfies(
            option -> {
              assertThat(option.accountId()).isEqualTo(42L);
              assertThat(option.name()).isEqualTo("Everything Else");
            });
    assertThat(result.personOptions())
        .singleElement()
        .satisfies(
            option -> {
              assertThat(option.personId()).isEqualTo(8L);
              assertThat(option.name()).isEqualTo("Max");
            });
    assertThat(result.currencyOptions())
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.code()).isEqualTo("EUR");
              assertThat(c.name()).isEqualTo("Euro");
            });
  }

  @Test
  void panelIsEmptyWhenNothingHasBeenStaged() {
    when(importAccountRepository.findBySession(SESSION_ID)).thenReturn(List.of());
    when(importFileRepository.findBySession(SESSION_ID)).thenReturn(List.of());
    when(accountService.manageableAccounts()).thenReturn(List.of());
    when(personService.personNamesForAccounts(anyList())).thenReturn(Map.of());
    when(personService.findAllLive()).thenReturn(List.of());
    when(currencyService.findAll()).thenReturn(List.of());

    assertThat(panel().forSession(SESSION_ID).rows()).isEmpty();
  }

  @Test
  void showsPersonLeafTargetTaggedWithOwnersName() {
    when(importAccountRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(mappedTo(20L, "Loan to Max", 99L)));
    when(importFileRepository.findBySession(SESSION_ID)).thenReturn(List.of());
    when(accountService.manageableAccounts()).thenReturn(List.of());
    when(personService.personNamesForAccounts(List.of(99L))).thenReturn(Map.of(99L, "Max"));
    when(personService.findAllLive()).thenReturn(List.of(new Person(8L, "Max", null)));
    when(currencyService.findAll()).thenReturn(List.of());

    assertThat(panel().forSession(SESSION_ID).rows())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.mapped()).isTrue();
              assertThat(row.personTarget()).isTrue();
              assertThat(row.targetAccountId()).isEqualTo(99L);
              assertThat(row.targetName()).isEqualTo("Max");
              assertThat(row.expectFile()).isTrue();
            });
  }
}
