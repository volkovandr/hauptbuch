package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.statements.repository.StatementProfileRepository;

/**
 * Unit tier: {@link StatementProfileService} validates before it writes and tidies what it stores
 * (statements.md §3.1): a blank optional column is no column, and an unknown or deleted profile is
 * refused.
 */
@ExtendWith(MockitoExtension.class)
class StatementProfileServiceTest {

  @Mock private StatementProfileRepository repository;

  private StatementProfileService service() {
    return new StatementProfileService(repository, new StatementCsvParser());
  }

  private static StatementProfile valid() {
    return new StatementProfile(
        null,
        "  BankAaa CSV ",
        "csv",
        10,
        3,
        null,
        ";",
        "\"",
        "UTF-8",
        null,
        null,
        ",",
        "dd.MM.yyyy",
        "signed",
        " Booking ",
        "  ",
        "Amount",
        null,
        null,
        "",
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  void savesNewProfileTrimmedWithBlankColumnsAsNull() {
    when(repository.insert(any())).thenReturn(5L);

    assertThat(service().save(valid())).isEqualTo(5L);

    ArgumentCaptor<StatementProfile> saved = ArgumentCaptor.forClass(StatementProfile.class);
    verify(repository).insert(saved.capture());
    assertThat(saved.getValue().name()).isEqualTo("BankAaa CSV");
    assertThat(saved.getValue().colBookingDate()).isEqualTo("Booking");
    assertThat(saved.getValue().colValueDate()).isNull();
    assertThat(saved.getValue().colCurrency()).isNull();
    assertThat(saved.getValue().csvSkipRows()).isZero();
    assertThat(saved.getValue().csvHasHeader()).isFalse();
  }

  @Test
  void updatesExistingProfileById() {
    StatementProfile existing = withId(valid(), 9L);
    when(repository.update(eq(9L), any())).thenReturn(1);

    assertThat(service().save(existing)).isEqualTo(9L);
    verify(repository, never()).insert(any());
  }

  @Test
  void refusesToUpdateProfileThatIsGone() {
    when(repository.update(eq(9L), any())).thenReturn(0);

    assertThatThrownBy(() -> service().save(withId(valid(), 9L)))
        .isInstanceOf(StatementFormatException.class)
        .hasMessage("That statement profile no longer exists.");
  }

  @Test
  void refusesBlankNameBeforeWriting() {
    StatementProfile blank = StatementProfile.blankCsv();

    assertThatThrownBy(() -> service().save(blank))
        .isInstanceOf(StatementFormatException.class)
        .hasMessage("Give the profile a name.");
    verify(repository, never()).insert(any());
  }

  @Test
  void refusesSettingTheParserCannotApply() {
    StatementProfile badDate =
        new StatementProfile(
            null,
            "p",
            "csv",
            10,
            3,
            null,
            ";",
            "\"",
            "UTF-8",
            0,
            true,
            ",",
            "dd.MM.'yyyy",
            "signed",
            "Booking",
            null,
            "Amount",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    assertThatThrownBy(() -> service().save(badDate))
        .hasMessage("The date format 'dd.MM.'yyyy' is not valid.");
    verify(repository, never()).insert(any());
  }

  @Test
  void lookupRefusesDeletedProfile() {
    StatementProfile deleted =
        new StatementProfile(
            1L,
            "p",
            "csv",
            10,
            3,
            null,
            ";",
            "\"",
            "UTF-8",
            0,
            true,
            ",",
            "dd.MM.yyyy",
            "signed",
            "Booking",
            null,
            "Amount",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            java.time.OffsetDateTime.now());
    when(repository.findById(1L)).thenReturn(Optional.of(deleted));

    assertThatThrownBy(() -> service().get(1L)).isInstanceOf(StatementFormatException.class);
  }

  private static StatementProfile withId(StatementProfile p, long id) {
    return new StatementProfile(
        id,
        p.name(),
        p.format(),
        p.windowDaysBefore(),
        p.windowDaysAfter(),
        p.aiNote(),
        p.csvDelimiter(),
        p.csvQuote(),
        p.csvEncoding(),
        p.csvSkipRows(),
        p.csvHasHeader(),
        p.csvDecimalSeparator(),
        p.csvDateFormat(),
        p.csvSignMode(),
        p.colBookingDate(),
        p.colValueDate(),
        p.colAmount(),
        p.colDebit(),
        p.colCredit(),
        p.colCurrency(),
        p.colCounterparty(),
        p.colDescription(),
        p.colBankCategory(),
        p.colIban(),
        null);
  }

  @Test
  void pdfProfileKeepsOnlyItsNameWindowAndAiNoteAndNeedsNoColumns() {
    StatementProfile submitted =
        new StatementProfile(
            null, " BankBbb PDF ", "pdf", 5, 2, "  Beschreibung carries the rate ", ";", null,
            null, 3, true, null, null, "signed", "Booking", null, null, null, null, null, null,
            null, null, null, null);
    when(repository.insert(any())).thenReturn(9L);

    assertThat(service().save(submitted)).isEqualTo(9L);

    ArgumentCaptor<StatementProfile> saved = ArgumentCaptor.forClass(StatementProfile.class);
    verify(repository).insert(saved.capture());
    assertThat(saved.getValue().isPdf()).isTrue();
    assertThat(saved.getValue().name()).isEqualTo("BankBbb PDF");
    assertThat(saved.getValue().aiNote()).isEqualTo("Beschreibung carries the rate");
    assertThat(saved.getValue().windowDaysBefore()).isEqualTo(5);
    assertThat(saved.getValue().csvDelimiter()).isNull();
    assertThat(saved.getValue().colBookingDate()).isNull();
  }

  @Test
  void pdfProfileStillNeedsAName() {
    StatementProfile submitted = StatementProfile.blankPdf();

    assertThatThrownBy(() -> service().save(submitted))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageContaining("name");
    verify(repository, never()).insert(any());
  }
}
