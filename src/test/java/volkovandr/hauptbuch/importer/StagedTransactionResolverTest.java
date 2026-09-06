package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.verifyNoInteractions;
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
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.categories.TagService;
import volkovandr.hauptbuch.categories.TagService.ResolvedChip;
import volkovandr.hauptbuch.ledger.ExchangeRateService;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.ledger.PostingDraft;
import volkovandr.hauptbuch.ledger.TransactionDraft;
import volkovandr.hauptbuch.operations.CurrencyLeafService;

/**
 * Unit tier (CLAUDE.md §6): {@link StagedTransactionResolver} with the cross-module services mocked
 * — one staged transaction ({@link ImportTransaction} + its {@link ImportPosting} legs) shaped into
 * the {@link TransactionDraft} the commit books (plan f2). Covers the single-currency pass-through,
 * splits, same- and cross-currency transfers (all three base-freezing branches), the {@code /Class}
 * tag, the funding-leg tag intersection, reconciliation, and the note assembly.
 */
@ExtendWith(MockitoExtension.class)
class StagedTransactionResolverTest {

  private static final long BANK_AAA = 100L;
  private static final long BANK_BBB = 200L;
  private static final long BANK_CCC = 300L;
  private static final long FOOD = 5L;
  private static final long TRAVEL = 6L;
  private static final long FOOD_EUR_LEAF = 500L;
  private static final long TRAVEL_EUR_LEAF = 600L;

  @Mock CurrencyLeafService currencyLeafService;
  @Mock ExchangeRateService exchangeRateService;
  @Mock PayeeService payeeService;
  @Mock TagService tagService;

  private StagedTransactionResolver resolver() {
    return new StagedTransactionResolver(
        currencyLeafService,
        new CrossCurrencyBaseAmounts(exchangeRateService),
        payeeService,
        tagService);
  }

  private StagedTransactionResolver.Maps mapsEur() {
    return new StagedTransactionResolver.Maps(
        "EUR",
        Map.of("BankAaa", BANK_AAA, "BankBbb", BANK_BBB, "BankCcc", BANK_CCC),
        Map.of(BANK_AAA, "EUR", BANK_BBB, "EUR", BANK_CCC, "CHF"),
        Map.of("Food:Groceries", FOOD, "Travel:Fuel", TRAVEL),
        Map.of("Food:Groceries", List.of(), "Travel:Fuel", List.of()));
  }

  @Test
  void singleCurrencyFundingAndCategoryPassStraightThrough() {
    when(currencyLeafService.resolveCurrencyLeaf(FOOD, "EUR"))
        .thenReturn(account(FOOD_EUR_LEAF, "EUR"));

    TransactionDraft draft =
        resolver()
            .resolve(
                transaction("2016-06-06", null, "unreconciled"),
                List.of(
                    fundingLeg("-20.00", "BankAaa"),
                    categoryLeg("20.00", "Food:Groceries", null, null)),
                mapsEur());

    assertThat(draft.date()).isEqualTo(LocalDate.of(2016, 6, 6));
    assertThat(draft.lifecycle()).isEqualTo("confirmed");
    assertThat(draft.postings())
        .extracting(PostingDraft::accountId, PostingDraft::amount, PostingDraft::baseAmount)
        .containsExactly(
            tuple(BANK_AAA, new BigDecimal("-20.00"), null),
            tuple(FOOD_EUR_LEAF, new BigDecimal("20.00"), null));
  }

  @Test
  void splitBooksOneLegPerCategory() {
    when(currencyLeafService.resolveCurrencyLeaf(FOOD, "EUR"))
        .thenReturn(account(FOOD_EUR_LEAF, "EUR"));
    when(currencyLeafService.resolveCurrencyLeaf(TRAVEL, "EUR"))
        .thenReturn(account(TRAVEL_EUR_LEAF, "EUR"));

    TransactionDraft draft =
        resolver()
            .resolve(
                transaction("2016-06-06", null, "unreconciled"),
                List.of(
                    fundingLeg("-50.00", "BankAaa"),
                    categoryLeg("30.00", "Food:Groceries", "food", null),
                    categoryLeg("20.00", "Travel:Fuel", "fuel", null)),
                mapsEur());

    assertThat(draft.postings())
        .extracting(PostingDraft::accountId, PostingDraft::amount, PostingDraft::note)
        .containsExactly(
            tuple(BANK_AAA, new BigDecimal("-50.00"), null),
            tuple(FOOD_EUR_LEAF, new BigDecimal("30.00"), "food"),
            tuple(TRAVEL_EUR_LEAF, new BigDecimal("20.00"), "fuel"));
  }

  @Test
  void sameCurrencyTransferBooksBothLegsNative() {
    TransactionDraft draft =
        resolver()
            .resolve(
                transaction("2016-06-06", null, "unreconciled"),
                List.of(fundingLeg("-100.00", "BankAaa"), transferLeg("100.00", "BankBbb", null)),
                mapsEur());

    assertThat(draft.postings())
        .allSatisfy(p -> assertThat(p.baseAmount()).isNull())
        .extracting(PostingDraft::accountId, PostingDraft::amount)
        .containsExactly(
            tuple(BANK_AAA, new BigDecimal("-100.00")), tuple(BANK_BBB, new BigDecimal("100.00")));
    verifyNoInteractions(exchangeRateService);
  }

  @Test
  void crossCurrencyTransferWhereNearSideIsBaseFreezesAtParFactor() {
    // BankAaa (EUR=base) −150 → BankCcc (CHF); the mirror supplied CHF 180 as counter_amount.
    TransactionDraft draft =
        resolver()
            .resolve(
                transaction("2016-06-06", null, "unreconciled"),
                List.of(
                    fundingLeg("-150.00", "BankAaa"), transferLeg("150.00", "BankCcc", "180.00")),
                mapsEur());

    // Near side IS base here, so factor 1: funding base = its own amount, far balances.
    assertThat(draft.postings())
        .extracting(PostingDraft::accountId, PostingDraft::amount, PostingDraft::baseAmount)
        .containsExactly(
            tuple(BANK_AAA, new BigDecimal("-150.00"), new BigDecimal("-150.00")),
            tuple(BANK_CCC, new BigDecimal("180.00"), new BigDecimal("150.00")));
    verifyNoInteractions(exchangeRateService);
  }

  @Test
  void crossCurrencyTransferWhereFarSideIsBaseFreezesFromThePair() {
    StagedTransactionResolver.Maps maps =
        new StagedTransactionResolver.Maps(
            "EUR",
            Map.of("BankCcc", BANK_CCC, "BankBbb", BANK_BBB),
            Map.of(BANK_CCC, "CHF", BANK_BBB, "EUR"),
            Map.of(),
            Map.of());

    // BankCcc (CHF) −200 → BankBbb (EUR=base); mirror supplied EUR 185 as counter_amount.
    TransactionDraft draft =
        resolver()
            .resolve(
                transaction("2016-06-06", null, "unreconciled"),
                List.of(
                    fundingLeg("-200.00", "BankCcc"), transferLeg("200.00", "BankBbb", "185.00")),
                maps);

    assertThat(draft.postings())
        .extracting(PostingDraft::accountId, PostingDraft::amount, PostingDraft::baseAmount)
        .containsExactly(
            tuple(BANK_CCC, new BigDecimal("-200.00"), new BigDecimal("-185.00")),
            tuple(BANK_BBB, new BigDecimal("185.00"), new BigDecimal("185.00")));
    verifyNoInteractions(exchangeRateService);
  }

  @Test
  void crossCurrencySplitWhoseTransferLegIsBaseAllocatesTheObservedRate() {
    when(currencyLeafService.resolveCurrencyLeaf(FOOD, "CHF"))
        .thenReturn(account(FOOD_EUR_LEAF, "CHF"));
    StagedTransactionResolver.Maps maps =
        new StagedTransactionResolver.Maps(
            "EUR",
            Map.of("BankCcc", BANK_CCC, "BankBbb", BANK_BBB),
            Map.of(BANK_CCC, "CHF", BANK_BBB, "EUR"),
            Map.of("Food:Groceries", FOOD),
            Map.of("Food:Groceries", List.of()));

    // Paid from BankCcc (CHF): CHF 80 groceries + a CHF 20 transfer to BankBbb (EUR=base). The
    // mirror stamped EUR 18,30 on the transfer leg, so this transaction's own rate is 0,915 EUR/CHF
    // and the near legs share −18,30 in proportion to their CHF amounts (−100 : +80).
    TransactionDraft draft =
        resolver()
            .resolve(
                transaction("2016-06-06", null, "unreconciled"),
                List.of(
                    fundingLeg("-100.00", "BankCcc"),
                    categoryLeg("80.00", "Food:Groceries", null, null),
                    transferLeg("20.00", "BankBbb", "18.30")),
                maps);

    assertThat(draft.postings())
        .extracting(PostingDraft::accountId, PostingDraft::amount, PostingDraft::baseAmount)
        .containsExactly(
            tuple(BANK_CCC, new BigDecimal("-100.00"), new BigDecimal("-91.50")),
            tuple(FOOD_EUR_LEAF, new BigDecimal("80.00"), new BigDecimal("73.20")),
            tuple(BANK_BBB, new BigDecimal("18.30"), new BigDecimal("18.30")));
    assertThat(
            draft.postings().stream()
                .map(PostingDraft::baseAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo("0.00");
    verifyNoInteractions(exchangeRateService);
  }

  @Test
  void resolutionErrorNamesTheDateFundingAccountAndAmount() {
    StagedTransactionResolver.Maps maps =
        new StagedTransactionResolver.Maps(
            "EUR", Map.of("BankAaa", BANK_AAA), Map.of(BANK_AAA, "EUR"), Map.of(), Map.of());

    // "Food:Groceries" is not in the category map.
    assertThatThrownBy(
            () ->
                resolver()
                    .resolve(
                        transaction("2016-06-06", "ShopBbb", "unreconciled"),
                        List.of(
                            fundingLeg("-20.00", "BankAaa"),
                            categoryLeg("20.00", "Food:Groceries", null, null)),
                        maps))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("06.06.2016")
        .hasMessageContaining("BankAaa")
        .hasMessageContaining("-20,00")
        .hasMessageContaining("ShopBbb");
  }

  @Test
  void crossCurrencyTransferNeitherSideIsBaseValuesViaTheRate() {
    StagedTransactionResolver.Maps maps =
        new StagedTransactionResolver.Maps(
            "EUR",
            Map.of("BankCcc", BANK_CCC, "BankBbb", BANK_BBB),
            Map.of(BANK_CCC, "CHF", BANK_BBB, "GBP"),
            Map.of(),
            Map.of());
    when(exchangeRateService.rateAsOf("CHF", LocalDate.of(2016, 6, 6)))
        .thenReturn(Optional.of(new BigDecimal("0.90")));

    // BankCcc (CHF) −100 → BankBbb (GBP); mirror gave GBP 78 as counter_amount.
    TransactionDraft draft =
        resolver()
            .resolve(
                transaction("2016-06-06", null, "unreconciled"),
                List.of(
                    fundingLeg("-100.00", "BankCcc"), transferLeg("100.00", "BankBbb", "78.00")),
                maps);

    assertThat(draft.postings())
        .extracting(PostingDraft::accountId, PostingDraft::amount, PostingDraft::baseAmount)
        .containsExactly(
            tuple(BANK_CCC, new BigDecimal("-100.00"), new BigDecimal("-90.00")),
            tuple(BANK_BBB, new BigDecimal("78.00"), new BigDecimal("90.00")));
  }

  @Test
  void crossCurrencyTransferWithNoRateIsRefused() {
    StagedTransactionResolver.Maps maps =
        new StagedTransactionResolver.Maps(
            "EUR",
            Map.of("BankCcc", BANK_CCC, "BankBbb", BANK_BBB),
            Map.of(BANK_CCC, "CHF", BANK_BBB, "GBP"),
            Map.of(),
            Map.of());
    when(exchangeRateService.rateAsOf("CHF", LocalDate.of(2016, 6, 6)))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                resolver()
                    .resolve(
                        transaction("2016-06-06", null, "unreconciled"),
                        List.of(
                            fundingLeg("-100.00", "BankCcc"),
                            transferLeg("100.00", "BankBbb", "78.00")),
                        maps))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No exchange rate for CHF");
  }

  @Test
  void classSuffixTagsItsLegAndFundingLegGetsTheIntersection() {
    when(currencyLeafService.resolveCurrencyLeaf(FOOD, "EUR"))
        .thenReturn(account(FOOD_EUR_LEAF, "EUR"));
    when(currencyLeafService.resolveCurrencyLeaf(TRAVEL, "EUR"))
        .thenReturn(account(TRAVEL_EUR_LEAF, "EUR"));
    when(tagService.resolveChip("Trips:Alps"))
        .thenReturn(Optional.of(new ResolvedChip(9L, "Trips:Alps")));

    TransactionDraft draft =
        resolver()
            .resolve(
                transaction("2016-06-06", null, "unreconciled"),
                List.of(
                    fundingLeg("-50.00", "BankAaa"),
                    categoryLeg("30.00", "Food:Groceries", null, "Trips:Alps"),
                    categoryLeg("20.00", "Travel:Fuel", null, "Trips:Alps")),
                mapsEur());

    assertThat(draft.postings().get(0).tagIds()).containsExactly(9L);
    assertThat(draft.postings().get(1).tagIds()).containsExactly(9L);
    assertThat(draft.postings().get(2).tagIds()).containsExactly(9L);
  }

  @Test
  void tagOnOneLineOnlyDoesNotReachTheFundingLeg() {
    when(currencyLeafService.resolveCurrencyLeaf(FOOD, "EUR"))
        .thenReturn(account(FOOD_EUR_LEAF, "EUR"));
    when(currencyLeafService.resolveCurrencyLeaf(TRAVEL, "EUR"))
        .thenReturn(account(TRAVEL_EUR_LEAF, "EUR"));
    when(tagService.resolveChip("Trips:Alps"))
        .thenReturn(Optional.of(new ResolvedChip(9L, "Trips:Alps")));

    TransactionDraft draft =
        resolver()
            .resolve(
                transaction("2016-06-06", null, "unreconciled"),
                List.of(
                    fundingLeg("-50.00", "BankAaa"),
                    categoryLeg("30.00", "Food:Groceries", null, "Trips:Alps"),
                    categoryLeg("20.00", "Travel:Fuel", null, null)),
                mapsEur());

    assertThat(draft.postings().get(0).tagIds()).isEmpty();
  }

  @Test
  void destroyedClassNameContributesNoTag() {
    when(currencyLeafService.resolveCurrencyLeaf(FOOD, "EUR"))
        .thenReturn(account(FOOD_EUR_LEAF, "EUR"));

    TransactionDraft draft =
        resolver()
            .resolve(
                transaction("2016-06-06", null, "unreconciled"),
                List.of(
                    fundingLeg("-20.00", "BankAaa"),
                    categoryLeg("20.00", "Food:Groceries", null, "????")),
                mapsEur());

    assertThat(draft.postings().get(1).tagIds()).isEmpty();
    verifyNoInteractions(tagService);
  }

  @Test
  void reconciliationIsAppliedToEveryLeg() {
    when(currencyLeafService.resolveCurrencyLeaf(FOOD, "EUR"))
        .thenReturn(account(FOOD_EUR_LEAF, "EUR"));

    TransactionDraft draft =
        resolver()
            .resolve(
                transaction("2016-06-06", null, "reconciled"),
                List.of(
                    fundingLeg("-20.00", "BankAaa"),
                    categoryLeg("20.00", "Food:Groceries", null, null)),
                mapsEur());

    assertThat(draft.postings())
        .allSatisfy(p -> assertThat(p.reconciliation()).isEqualTo("reconciled"));
  }

  @Test
  void noteCombinesTheReferenceNumberAndMemo() {
    when(currencyLeafService.resolveCurrencyLeaf(FOOD, "EUR"))
        .thenReturn(account(FOOD_EUR_LEAF, "EUR"));

    ImportTransaction withRef =
        new ImportTransaction(
            1L,
            1L,
            LocalDate.of(2016, 6, 6),
            "ShopBbb",
            false,
            "weekly shop",
            "10231",
            "unreconciled",
            false,
            "ready",
            null);

    TransactionDraft draft =
        resolver()
            .resolve(
                withRef,
                List.of(
                    fundingLeg("-20.00", "BankAaa"),
                    categoryLeg("20.00", "Food:Groceries", null, null)),
                mapsEur());

    assertThat(draft.note()).isEqualTo("#10231 weekly shop");
  }

  @Test
  void payeeIsResolvedThroughTheImportedPayeeRoutine() {
    when(currencyLeafService.resolveCurrencyLeaf(FOOD, "EUR"))
        .thenReturn(account(FOOD_EUR_LEAF, "EUR"));
    when(payeeService.resolveImportedPayee("ShopBbb")).thenReturn(88L);

    TransactionDraft draft =
        resolver()
            .resolve(
                transaction("2016-06-06", "ShopBbb", "unreconciled"),
                List.of(
                    fundingLeg("-20.00", "BankAaa"),
                    categoryLeg("20.00", "Food:Groceries", null, null)),
                mapsEur());

    assertThat(draft.payeeId()).isEqualTo(88L);
  }

  // --- fixtures -------------------------------------------------------------

  private ImportTransaction transaction(String date, String payee, String cleared) {
    return new ImportTransaction(
        1L, 1L, LocalDate.parse(date), payee, false, null, null, cleared, false, "ready", null);
  }

  private ImportPosting fundingLeg(String amount, String moneyAccountName) {
    return new ImportPosting(
        null, 1L, new BigDecimal(amount), null, null, moneyAccountName, null, null, null, true);
  }

  private ImportPosting categoryLeg(String amount, String path, String note, String className) {
    return new ImportPosting(
        null, 1L, new BigDecimal(amount), note, path, null, className, null, null, false);
  }

  private ImportPosting transferLeg(String amount, String moneyAccountName, String counterAmount) {
    return new ImportPosting(
        null,
        1L,
        new BigDecimal(amount),
        null,
        null,
        moneyAccountName,
        null,
        null,
        counterAmount == null ? null : new BigDecimal(counterAmount),
        false);
  }

  private static Account account(long id, String currency) {
    return new Account(
        id, "leaf", "expense", null, currency, null, null, null, null, false, false, false);
  }
}
