package volkovandr.hauptbuch.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.operations.SplitCurrencyService;
import volkovandr.hauptbuch.operations.SplitEntry;
import volkovandr.hauptbuch.operations.SplitLineDraft;
import volkovandr.hauptbuch.operations.SplitTotals;
import volkovandr.hauptbuch.operations.SplitTotalsQuery;
import volkovandr.hauptbuch.recurring.repository.RecurringTemplateRepository;

/**
 * Unit tier (CLAUDE.md §6): the entry one occurrence books (data-model §14.1/§14.3, recurring
 * sub-plan slice c). The stored split shape goes back into a {@link SplitEntry} on the occurrence
 * date, people by their stored ids, the lifecycle from the confirmation mode, and any
 * cross-currency totals proposed from the rate on that date.
 */
@ExtendWith(MockitoExtension.class)
class RecurringOccurrenceEntriesTest {

  private static final long TEMPLATE_ID = 5L;
  private static final long BANK_ID = 7L;
  private static final long STREAMING_ID = 11L;
  private static final long SAVINGS_ID = 12L;
  private static final long MAX_ID = 13L;
  private static final LocalDate OCCURRENCE = LocalDate.of(2026, 10, 31);

  @Mock private RecurringTemplateRepository repository;
  @Mock private AccountService accountService;
  @Mock private SplitCurrencyService splitCurrencyService;

  private RecurringOccurrenceEntries entries() {
    return new RecurringOccurrenceEntries(repository, accountService, splitCurrencyService);
  }

  private static RecurringTemplate template(
      Long accountId, Long personId, String confirmation, String spendingCurrency) {
    return new RecurringTemplate(
        TEMPLATE_ID,
        "Streaming",
        LocalDate.of(2026, 1, 31),
        "month",
        1,
        null,
        0,
        confirmation,
        LocalDate.of(2026, 9, 27),
        false,
        null,
        null,
        accountId,
        personId,
        personId == null ? null : "BY",
        3L,
        "plan",
        spendingCurrency,
        null,
        null,
        null);
  }

  private static RecurringTemplateLine line(
      long lineId,
      Long accountId,
      String transferDirection,
      Long personId,
      String personDirection,
      String amount) {
    return new RecurringTemplateLine(
        lineId,
        TEMPLATE_ID,
        accountId,
        transferDirection,
        personId,
        personDirection,
        new BigDecimal(amount),
        "line " + lineId,
        (int) lineId);
  }

  private void streamingIsAnExpense() {
    when(accountService.findById(STREAMING_ID))
        .thenReturn(
            Optional.of(
                new Account(
                    STREAMING_ID,
                    "Streaming",
                    "expense",
                    null,
                    "EUR",
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    false)));
  }

  /** The totals proposal hands the stored (blank) totals back, as for a single-currency entry. */
  private void proposesNothing() {
    when(splitCurrencyService.proposeTotals(any())).thenReturn(new SplitTotals(null, null));
  }

  @Test
  void entryCarriesTheStoredSplitOnTheOccurrenceDate() {
    when(repository.findLines(TEMPLATE_ID))
        .thenReturn(
            List.of(
                line(1L, STREAMING_ID, null, null, null, "9.9900"),
                line(2L, SAVINGS_ID, "TO", null, null, "50.0000"),
                line(3L, null, null, MAX_ID, "FOR", "-5.0000")));
    when(repository.findTagIds(TEMPLATE_ID)).thenReturn(List.of(20L));
    when(repository.findLineTagIds(1L)).thenReturn(List.of(20L, 21L));
    when(repository.findLineTagIds(2L)).thenReturn(List.of());
    when(repository.findLineTagIds(3L)).thenReturn(List.of());
    streamingIsAnExpense();
    proposesNothing();

    SplitEntry entry = entries().entryFor(template(BANK_ID, null, "auto", null), OCCURRENCE);

    assertThat(entry.transactionId()).isNull();
    assertThat(entry.date()).isEqualTo(OCCURRENCE);
    assertThat(entry.accountId()).isEqualTo(BANK_ID);
    assertThat(entry.fundingPersonId()).isNull();
    assertThat(entry.payeeId()).isEqualTo(3L);
    assertThat(entry.note()).isEqualTo("plan");
    assertThat(entry.tagIds()).containsExactly(20L);
    assertThat(entry.lifecycle()).isEqualTo("confirmed");
    assertThat(entry.lines())
        .containsExactly(
            new SplitLineDraft(
                STREAMING_ID, "9,99", "line 1", null, null, null, null, null, List.of(20L, 21L)),
            new SplitLineDraft(SAVINGS_ID, "50,00", "line 2", "TO", null, null, null, null, null),
            new SplitLineDraft(null, "-5,00", "line 3", null, null, "FOR", null, MAX_ID, null));
  }

  @Test
  void reviewTemplateBooksPendingReview() {
    when(repository.findLines(TEMPLATE_ID))
        .thenReturn(List.of(line(1L, STREAMING_ID, null, null, null, "9.9900")));
    streamingIsAnExpense();
    proposesNothing();

    SplitEntry entry = entries().entryFor(template(BANK_ID, null, "review", null), OCCURRENCE);

    assertThat(entry.lifecycle()).isEqualTo("pending_review");
  }

  @Test
  void personFundedTemplateNamesItsPersonByIdInItsStoredCurrency() {
    when(repository.findLines(TEMPLATE_ID))
        .thenReturn(List.of(line(1L, STREAMING_ID, null, null, null, "50.0000")));
    streamingIsAnExpense();
    proposesNothing();

    SplitEntry entry = entries().entryFor(template(null, MAX_ID, "auto", "EUR"), OCCURRENCE);

    assertThat(entry.accountId()).isNull();
    assertThat(entry.fundingPersonId()).isEqualTo(MAX_ID);
    assertThat(entry.fundingPersonName()).isNull();
    assertThat(entry.fundingPersonDirection()).isEqualTo("BY");
    assertThat(entry.spendingCurrencyCode()).isEqualTo("EUR");
  }

  @Test
  void crossCurrencyTotalsAreProposedFromTheOccurrenceDate() {
    // An expense of 20 and a storno of 5 net to 15 CHF; the funding and base totals are proposed
    // from the rate on the occurrence date, never stored with the template.
    when(repository.findLines(TEMPLATE_ID))
        .thenReturn(
            List.of(
                line(1L, STREAMING_ID, null, null, null, "20.0000"),
                line(2L, STREAMING_ID, null, null, null, "-5.0000")));
    streamingIsAnExpense();
    when(splitCurrencyService.proposeTotals(
            new SplitTotalsQuery(BANK_ID, "CHF", OCCURRENCE, "15,00", null, null)))
        .thenReturn(new SplitTotals("16,05", null));

    SplitEntry entry = entries().entryFor(template(BANK_ID, null, "auto", "CHF"), OCCURRENCE);

    assertThat(entry.spendingCurrencyCode()).isEqualTo("CHF");
    assertThat(entry.fundingTotal()).isEqualTo("16,05");
    assertThat(entry.baseTotal()).isNull();
    verify(splitCurrencyService)
        .proposeTotals(new SplitTotalsQuery(BANK_ID, "CHF", OCCURRENCE, "15,00", null, null));
  }
}
