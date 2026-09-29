package volkovandr.hauptbuch.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.ExchangeRateService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.recurring.RecurringCostSummary.Line;
import volkovandr.hauptbuch.recurring.repository.RecurringTemplateRepository;

/**
 * Unit tier (CLAUDE.md §6): the recurring page's figures (data-model §14.4, recurring sub-plan
 * slice g). They are schedule math over a template's funding-leg amount, never bookkeeping:
 * "already" counts every occurrence from the start through today, booked or not. The Recurring cost
 * summary classifies each non-funding leg as expense (by top-level category), income or transfer,
 * in base at today's rate.
 */
@ExtendWith(MockitoExtension.class)
class RecurringCostsTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 3, 31);
  private static final long BANK_ID = 7L;
  private static final long CHF_BANK_ID = 8L;
  private static final long LOAN_ID = 9L;
  private static final long FINANCE_ID = 20L;
  private static final long INTEREST_ID = 21L;
  private static final long MEDIA_ID = 22L;
  private static final long SALARY_ID = 23L;
  private static final long KIDS_ID = 24L;
  private static final long SON_ID = 30L;

  @Mock private RecurringTemplateRepository repository;
  @Mock private AccountService accountService;
  @Mock private SettingsService settingsService;
  @Mock private ExchangeRateService exchangeRateService;

  private RecurringCosts costs;
  private long nextLineId;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    costs =
        new RecurringCosts(repository, accountService, settingsService, exchangeRateService, clock);
    lenient().when(settingsService.baseCurrency()).thenReturn(Optional.of("EUR"));
    account(BANK_ID, "BankAaa-EUR", "asset", null, "EUR");
    account(CHF_BANK_ID, "BankBbb-CHF", "asset", null, "CHF");
    account(LOAN_ID, "Loan", "liability", null, "EUR");
    account(FINANCE_ID, "Finance", "expense", null, "EUR");
    account(INTEREST_ID, "Interest", "expense", FINANCE_ID, "EUR");
    account(MEDIA_ID, "Media", "expense", null, "EUR");
    account(SALARY_ID, "Salary", "income", null, "EUR");
    account(KIDS_ID, "Kids", "expense", null, "EUR");
  }

  private void account(long id, String name, String type, Long parentId, String currency) {
    lenient()
        .when(accountService.findById(id))
        .thenReturn(
            Optional.of(
                new Account(
                    id, name, type, parentId, currency, null, null, null, null, false, false,
                    false)));
  }

  private static RecurringTemplate template(
      long id, String name, Long accountId, Long personId, String unit, LocalDate end) {
    return new RecurringTemplate(
        id,
        name,
        LocalDate.of(2026, 1, 31),
        unit,
        1,
        end,
        0,
        "auto",
        TODAY,
        false,
        null,
        null,
        accountId,
        personId,
        personId == null ? null : "BY",
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private RecurringTemplateLine category(long templateId, long accountId, String amount) {
    return line(templateId, accountId, null, null, null, amount);
  }

  private RecurringTemplateLine line(
      long templateId,
      Long accountId,
      String transferDirection,
      Long personId,
      String personDirection,
      String amount) {
    nextLineId++;
    return new RecurringTemplateLine(
        nextLineId,
        templateId,
        accountId,
        transferDirection,
        personId,
        personDirection,
        new BigDecimal(amount),
        null,
        (int) nextLineId);
  }

  private void live(RecurringTemplate... templates) {
    when(repository.findLive()).thenReturn(List.of(templates));
  }

  private void lines(long templateId, RecurringTemplateLine... lines) {
    when(repository.findLines(templateId)).thenReturn(List.of(lines));
  }

  // ── per-template figures ────────────────────────────────────────────────────

  @Test
  void figuresArePerMonthAndPerYearOfTheFundingLeg() {
    live(template(1L, "Streaming", BANK_ID, null, "month", null));
    lines(1L, category(1L, MEDIA_ID, "9.99"));

    RecurringFigures figures = costs.figures().get(1L);

    assertThat(figures.perMonth()).isEqualTo("-9,99");
    assertThat(figures.perYear()).isEqualTo("-119,88");
    assertThat(figures.already()).isNull();
    assertThat(figures.yetToPay()).isNull();
    assertThat(figures.total()).isNull();
  }

  @Test
  void endDateAddsAlreadyCountingEveryOccurrenceThroughTodayYetToPayAndTotal() {
    // Monthly from 31 Jan: 31 Jan, 28 Feb and today, 31 Mar, are already, booked or not; April to
    // December are yet to pay.
    live(template(1L, "Gym", BANK_ID, null, "month", LocalDate.of(2026, 12, 31)));
    lines(1L, category(1L, MEDIA_ID, "10"));

    RecurringFigures figures = costs.figures().get(1L);

    assertThat(figures.already()).isEqualTo("-30,00");
    assertThat(figures.yetToPay()).isEqualTo("-90,00");
    assertThat(figures.total()).isEqualTo("-120,00");
  }

  @Test
  void foreignCurrencyFiguresAddBaseAtTodaysRate() {
    live(template(1L, "Streaming", CHF_BANK_ID, null, "month", null));
    lines(1L, category(1L, MEDIA_ID, "10"));
    when(exchangeRateService.rateAsOf("CHF", TODAY))
        .thenReturn(Optional.of(new BigDecimal("1.07")));

    RecurringFigures figures = costs.figures().get(1L);

    assertThat(figures.perMonth()).startsWith("-10,00 ").endsWith(" (-10,70)");
    assertThat(figures.perYear()).endsWith(" (-128,40)");
  }

  @Test
  void foreignCurrencyWithoutRateShowsNativeOnly() {
    live(template(1L, "Streaming", CHF_BANK_ID, null, "month", null));
    lines(1L, category(1L, MEDIA_ID, "10"));
    when(exchangeRateService.rateAsOf("CHF", TODAY)).thenReturn(Optional.empty());

    assertThat(costs.figures().get(1L).perMonth()).doesNotContain("(");
  }

  // ── the Recurring cost summary ──────────────────────────────────────────────

  @Test
  void summaryClassifiesEachLegAndNets() {
    // A loan instalment split into repayment (transfer) and interest (expense); a salary (income);
    // pocket money funded by a person, still an expense of the book's.
    live(
        template(1L, "Loan", BANK_ID, null, "month", null),
        template(2L, "Salary", BANK_ID, null, "month", null),
        template(3L, "Pocket money", null, SON_ID, "month", null));
    lines(1L, line(1L, LOAN_ID, "TO", null, null, "400"), category(1L, INTEREST_ID, "100"));
    lines(2L, category(2L, SALARY_ID, "3000"));
    lines(3L, category(3L, KIDS_ID, "50"));

    RecurringCostSummary summary = costs.summary();

    assertThat(summary.expenses())
        .containsExactly(
            new Line("Finance", "100,00", "1.200,00"), new Line("Kids", "50,00", "600,00"));
    assertThat(summary.expenseTotal()).isEqualTo(new Line("Expenses", "150,00", "1.800,00"));
    assertThat(summary.income()).isEqualTo(new Line("Income", "3.000,00", "36.000,00"));
    assertThat(summary.transfer()).isEqualTo(new Line("Transfers", "400,00", "4.800,00"));
    assertThat(summary.net()).isEqualTo(new Line("Net", "2.450,00", "29.400,00"));
    assertThat(summary.leftOut()).isEmpty();
  }

  @Test
  void summaryNormalisesWeeksAndStornoLegs() {
    // Weekly cleaning of 25 with a 5 storno back: 20 a week, 1.042,86 a year.
    live(template(1L, "Cleaning", BANK_ID, null, "week", null));
    lines(1L, category(1L, MEDIA_ID, "25"), category(1L, MEDIA_ID, "-5"));

    RecurringCostSummary summary = costs.summary();

    assertThat(summary.expenses()).containsExactly(new Line("Media", "86,90", "1.042,86"));
    assertThat(summary.net()).isEqualTo(new Line("Net", "-86,90", "-1.042,86"));
  }

  @Test
  void summaryConvertsForeignTemplatesAndLeavesOutThoseWithoutRate() {
    live(
        template(1L, "Streaming", CHF_BANK_ID, null, "month", null),
        template(2L, "Gym", BANK_ID, null, "month", null));
    lines(1L, category(1L, MEDIA_ID, "10"));
    lines(2L, category(2L, MEDIA_ID, "30"));
    when(exchangeRateService.rateAsOf("CHF", TODAY)).thenReturn(Optional.of(new BigDecimal("1.1")));

    assertThat(costs.summary().expenses()).containsExactly(new Line("Media", "41,00", "492,00"));

    when(exchangeRateService.rateAsOf(any(), any())).thenReturn(Optional.empty());

    RecurringCostSummary summary = costs.summary();
    assertThat(summary.expenses()).containsExactly(new Line("Media", "30,00", "360,00"));
    assertThat(summary.leftOut()).containsExactly("Streaming");
  }

  @Test
  void transferFromAndPersonByLinesReduceTheTransfers() {
    // 500 out to savings, 200 back from the loan account, Max pays 50 of it: 250 net out.
    live(template(1L, "Shuffle", BANK_ID, null, "month", null));
    lines(
        1L,
        line(1L, LOAN_ID, "TO", null, null, "500"),
        line(1L, LOAN_ID, "FROM", null, null, "200"),
        line(1L, null, null, SON_ID, "BY", "50"));

    RecurringCostSummary summary = costs.summary();

    assertThat(summary.transfer()).isEqualTo(new Line("Transfers", "250,00", "3.000,00"));
    assertThat(summary.net()).isEqualTo(new Line("Net", "-250,00", "-3.000,00"));
  }

  @Test
  void endedTemplateNoLongerCountsInTheSummary() {
    live(
        template(1L, "Old gym", BANK_ID, null, "month", TODAY.minusDays(1)),
        template(2L, "Gym", BANK_ID, null, "month", TODAY));
    lines(2L, category(2L, MEDIA_ID, "30"));

    assertThat(costs.summary().expenses()).containsExactly(new Line("Media", "30,00", "360,00"));
  }
}
