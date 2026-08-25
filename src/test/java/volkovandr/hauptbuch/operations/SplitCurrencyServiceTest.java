package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.CrossCurrencyFields;
import volkovandr.hauptbuch.ledger.CrossCurrencyFieldsQuery;
import volkovandr.hauptbuch.ledger.CrossCurrencyFieldsService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Unit tier (plan §1.5): the one cross-currency header rule both entry surfaces read (issue
 * receipts/23, decision 4) — whether the header is cross-currency, which totals it needs, the two
 * derived rates, and the per-currency remainings. Plus the blank-total proposals (decision 6),
 * which fill in whichever direction the filled siblings allow and never overwrite what was typed.
 */
@ExtendWith(MockitoExtension.class)
class SplitCurrencyServiceTest {

  private static final String EUR = "EUR";
  private static final String USD = "USD";
  private static final String CHF = "CHF";
  private static final long CARD_ID = 1L;
  private static final LocalDate DATE = LocalDate.of(2026, 2, 1);

  @Mock private AccountService accountService;
  @Mock private SettingsService settingsService;
  @Mock private CrossCurrencyFieldsService crossCurrencyFieldsService;

  private SplitCurrencyService service;

  @BeforeEach
  void setUp() {
    service = new SplitCurrencyService(accountService, settingsService, crossCurrencyFieldsService);
  }

  private static Account account(String currency) {
    return new Account(
        CARD_ID, "Card", "asset", null, currency, null, null, null, null, false, false);
  }

  // ── the header state (decision 4) ────────────────────────────────────────────

  @Test
  void matchingCurrenciesStaySingleCurrency() {
    SplitCurrencyContext ctx = service.resolve(new SplitCurrencyQuery(EUR, EUR, "20", "", ""));

    assertThat(ctx.cross()).isFalse();
    assertThat(ctx.view(new BigDecimal("20")).crossCurrency()).isFalse();
    assertThat(ctx.derivedFunding(new BigDecimal("20"))).isEmpty();
    assertThat(ctx.fundingNet(new BigDecimal("20"))).isEqualByComparingTo("20");
  }

  @Test
  void blankSpendingCurrencyStaysSingleCurrency() {
    assertThat(service.resolve(new SplitCurrencyQuery(EUR, null, "20", "", "")).cross()).isFalse();
    assertThat(service.resolve(new SplitCurrencyQuery(EUR, "  ", "20", "", "")).cross()).isFalse();
  }

  @Test
  void divergingCurrenciesDeriveBothRatesFromTheHeaderTotals() {
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));

    // CHF card, 90 USD receipt, 100 CHF off the card, 95 EUR base.
    SplitCurrencyContext ctx = service.resolve(new SplitCurrencyQuery(CHF, USD, "90", "100", "95"));

    assertThat(ctx.cross()).isTrue();
    assertThat(ctx.neitherIsBase()).isTrue();
    assertThat(ctx.derivedFunding(new BigDecimal("60"))).isEqualTo("66,67");
    assertThat(ctx.derivedBase(new BigDecimal("60"))).isEqualTo("63,33");
    assertThat(ctx.fundingNet(new BigDecimal("90"))).isEqualByComparingTo("100.00");
  }

  @Test
  void fundingLegBeingBaseNeedsNoSeparateBaseTotal() {
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));

    // EUR card, 100 USD receipt, 95 EUR off the card — the base total IS the funding total.
    SplitCurrencyContext ctx = service.resolve(new SplitCurrencyQuery(EUR, USD, "100", "95", ""));

    assertThat(ctx.cross()).isTrue();
    assertThat(ctx.neitherIsBase()).isFalse();
    assertThat(ctx.view(new BigDecimal("100")).baseTotal()).isEqualTo("95,00");
  }

  @Test
  void spendingLegBeingBaseNeedsNoSeparateBaseTotal() {
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));

    // CHF card, 100 EUR receipt — the base total IS the spending total.
    SplitCurrencyContext ctx = service.resolve(new SplitCurrencyQuery(CHF, EUR, "100", "105", ""));

    assertThat(ctx.neitherIsBase()).isFalse();
    assertThat(ctx.view(new BigDecimal("100")).baseTotal()).isEqualTo("100,00");
  }

  @Test
  void viewCountsRemainingInEveryCurrencyInPlay() {
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));

    SplitCurrencyContext ctx = service.resolve(new SplitCurrencyQuery(CHF, USD, "90", "100", "95"));
    SplitCurrency view = ctx.view(new BigDecimal("60")); // only 60 of the 90 USD allocated

    assertThat(view.remainingFunding()).isEqualTo("33,33"); // 100 − 60×100/90
    assertThat(view.remainingBase()).isEqualTo("31,67"); // 95 − 60×95/90
    assertThat(view.fundingCurrencyCode()).isEqualTo(CHF);
    assertThat(view.spendingCurrencyCode()).isEqualTo(USD);
    assertThat(view.baseCurrencyCode()).isEqualTo(EUR);
  }

  @Test
  void derivedAmountsAreBlankForZeroAndForSingleCurrency() {
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));

    assertThat(
            service
                .resolve(new SplitCurrencyQuery(CHF, USD, "90", "100", "95"))
                .derivedFunding(BigDecimal.ZERO))
        .isEmpty();
    assertThat(
            service
                .resolve(new SplitCurrencyQuery(EUR, EUR, "90", "", ""))
                .derivedBase(BigDecimal.TEN))
        .isEmpty();
  }

  @Test
  void totalNoRateCouldProposeStaysBlankRatherThanReadingZero() {
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));

    // Nothing could be proposed, so both fields must render EMPTY. A formatted "0,00" would be a
    // number the operator never entered — and, being non-blank, would block every later proposal.
    SplitCurrency view =
        service.resolve(new SplitCurrencyQuery(CHF, USD, "90", "", "")).view(BigDecimal.ZERO);

    assertThat(view.fundingTotal()).isEmpty();
    assertThat(view.baseTotal()).isEmpty();
  }

  @Test
  void typedTotalIsStillNormalisedForDisplay() {
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));

    SplitCurrency view =
        service.resolve(new SplitCurrencyQuery(CHF, USD, "90", "100", "95")).view(BigDecimal.ZERO);

    assertThat(view.fundingTotal()).isEqualTo("100,00");
    assertThat(view.baseTotal()).isEqualTo("95,00");
  }

  @Test
  void unsetBaseCurrencyFallsBackToTheFundingLeg() {
    when(settingsService.baseCurrency()).thenReturn(Optional.empty());

    SplitCurrencyContext ctx = service.resolve(new SplitCurrencyQuery(CHF, USD, "90", "100", ""));

    assertThat(ctx.cross()).isTrue();
    assertThat(ctx.neitherIsBase()).isFalse();
    assertThat(ctx.baseCurrencyCode()).isEqualTo(CHF);
  }

  // ── the blank-total proposals (decision 6) ───────────────────────────────────

  @Test
  void proposesTheFundingTotalFromTheSpendingTotalWhenItIsBlank() {
    when(accountService.findById(CARD_ID)).thenReturn(Optional.of(account(CHF)));
    when(crossCurrencyFieldsService.prefillFundingTotal(CHF, USD, DATE, "90")).thenReturn("100,00");

    SplitTotals totals =
        service.proposeTotals(new SplitTotalsQuery(CARD_ID, USD, DATE, "90", "", "95"));

    assertThat(totals.fundingTotal()).isEqualTo("100,00");
    assertThat(totals.baseTotal()).isEqualTo("95");
  }

  @Test
  void proposesTheBaseTotalFromTheFundingTotalItJustProposed() {
    when(accountService.findById(CARD_ID)).thenReturn(Optional.of(account(CHF)));
    when(crossCurrencyFieldsService.prefillFundingTotal(CHF, USD, DATE, "90")).thenReturn("100,00");
    when(crossCurrencyFieldsService.resolve(
            new CrossCurrencyFieldsQuery(CHF, USD, DATE, "100,00", null, "")))
        .thenReturn(new CrossCurrencyFields(CHF, USD, true, true, null, "95,00"));

    SplitTotals totals =
        service.proposeTotals(new SplitTotalsQuery(CARD_ID, USD, DATE, "90", "", ""));

    assertThat(totals.fundingTotal()).isEqualTo("100,00");
    assertThat(totals.baseTotal()).isEqualTo("95,00");
  }

  @Test
  void neverOverwritesTotalsTheOperatorTyped() {
    when(accountService.findById(CARD_ID)).thenReturn(Optional.of(account(CHF)));

    SplitTotals totals =
        service.proposeTotals(new SplitTotalsQuery(CARD_ID, USD, DATE, "90", "102,50", "96,10"));

    assertThat(totals.fundingTotal()).isEqualTo("102,50");
    assertThat(totals.baseTotal()).isEqualTo("96,10");
  }

  @Test
  void leavesTheFundingTotalBlankWhenNoRateCanPropose() {
    when(accountService.findById(CARD_ID)).thenReturn(Optional.of(account(CHF)));
    when(crossCurrencyFieldsService.prefillFundingTotal(CHF, USD, DATE, "90")).thenReturn(null);
    when(crossCurrencyFieldsService.resolve(
            new CrossCurrencyFieldsQuery(CHF, USD, DATE, null, null, "")))
        .thenReturn(new CrossCurrencyFields(CHF, USD, true, true, null, null));

    SplitTotals totals =
        service.proposeTotals(new SplitTotalsQuery(CARD_ID, USD, DATE, "90", "", ""));

    assertThat(totals.fundingTotal()).isNull();
    assertThat(totals.baseTotal()).isEmpty();
  }

  @Test
  void proposesNothingForSingleCurrencyOrAnUnknownAccount() {
    when(accountService.findById(CARD_ID)).thenReturn(Optional.of(account(EUR)));
    assertThat(
            service
                .proposeTotals(new SplitTotalsQuery(CARD_ID, EUR, DATE, "90", "", ""))
                .fundingTotal())
        .isEmpty();

    assertThat(
            service
                .proposeTotals(new SplitTotalsQuery(null, USD, DATE, "90", "", ""))
                .fundingTotal())
        .isEmpty();
  }
}
