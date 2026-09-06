package volkovandr.hauptbuch.importer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.ledger.ExchangeRateService;
import volkovandr.hauptbuch.ledger.PostingDraft;
import volkovandr.hauptbuch.shared.MoneyFactory;

/**
 * Freezes the {@code base_amount} on every leg of a resolved cross-currency import transaction
 * (import.md §6.3/§6.5; plan f2). The book never invents a rate:
 *
 * <ul>
 *   <li><strong>Far leg is the base currency.</strong> The mirror-supplied {@code counter_amount}
 *       states this transaction's own conversion: the far leg's {@code base_amount} is that real
 *       amount, and the near-currency legs (funding + any category legs) share {@code
 *       −counter_amount} in proportion to their native amounts, the last one absorbing the
 *       rounding. No rate lookup — and it covers a two-leg transfer and a split alike.
 *   <li><strong>Neither side is base.</strong> No observed base value exists, so the near legs are
 *       valued at {@code ExchangeRateService.rateAsOf} and the commit is refused if no rate is on
 *       file (the owner adds one for that day).
 * </ul>
 *
 * <p>The staged near-currency amounts sum to zero, so the frozen base amounts do too, and the far
 * leg's {@code base_amount} is simply the negation of the near legs' sum.
 */
@Component
class CrossCurrencyBaseAmounts {

  private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  /** The intermediate division scale before rounding to the base currency's minor units. */
  private static final int DIVISION_SCALE = 10;

  private final ExchangeRateService exchangeRateService;

  CrossCurrencyBaseAmounts(ExchangeRateService exchangeRateService) {
    this.exchangeRateService = exchangeRateService;
  }

  /**
   * The postings for a cross-currency transaction — the funding leg, the near-currency category
   * legs, then the one cross-currency transfer leg (its native amount already the real far amount).
   *
   * @param where a human locator for any error
   */
  List<PostingDraft> postings(
      String where,
      long fundingAccountId,
      BigDecimal fundingAmount,
      String nearCurrency,
      String reconciliation,
      List<ImportResolvedLeg> nonFunding,
      String baseCurrency,
      LocalDate date) {
    ImportResolvedLeg crossLeg = onlyCrossLeg(where, nonFunding);
    List<ImportResolvedLeg> nearLegs =
        nonFunding.stream().filter(leg -> !leg.crossCurrency()).toList();

    List<BigDecimal> nearAmounts = new ArrayList<>();
    nearAmounts.add(fundingAmount);
    BigDecimal nearTotal = fundingAmount;
    for (ImportResolvedLeg leg : nearLegs) {
      nearAmounts.add(leg.nativeAmount());
      nearTotal = nearTotal.add(leg.nativeAmount());
    }

    List<BigDecimal> nearBases =
        baseCurrency.equals(crossLeg.currencyCode())
            ? allocate(
                crossLeg.nativeAmount().negate(), nearAmounts, nearTotal, baseCurrency, where)
            : ratedBases(nearAmounts, baseCurrency, nearCurrency, date, where);

    List<PostingDraft> postings = new ArrayList<>();
    BigDecimal nearBaseSum = nearBases.get(0);
    postings.add(
        new PostingDraft(
            fundingAccountId,
            fundingAmount,
            nearBases.get(0),
            reconciliation,
            null,
            ImportResolvedLeg.sharedTags(nonFunding)));
    for (int i = 0; i < nearLegs.size(); i++) {
      ImportResolvedLeg leg = nearLegs.get(i);
      BigDecimal legBase = nearBases.get(i + 1);
      nearBaseSum = nearBaseSum.add(legBase);
      postings.add(
          new PostingDraft(
              leg.accountId(),
              leg.nativeAmount(),
              legBase,
              reconciliation,
              leg.note(),
              leg.tagIds()));
    }
    // The far leg balances the near legs exactly — and, when far is base, that balance IS
    // counter_amount, so its frozen base value equals its own real amount.
    postings.add(
        new PostingDraft(
            crossLeg.accountId(),
            crossLeg.nativeAmount(),
            nearBaseSum.negate(),
            reconciliation,
            crossLeg.note(),
            crossLeg.tagIds()));
    return postings;
  }

  /** The transaction's one cross-currency transfer leg — a multi-currency shape is refused. */
  private static ImportResolvedLeg onlyCrossLeg(String where, List<ImportResolvedLeg> nonFunding) {
    List<ImportResolvedLeg> crossLegs =
        nonFunding.stream().filter(ImportResolvedLeg::crossCurrency).toList();
    if (crossLegs.size() != 1) {
      throw new IllegalStateException(
          where
              + " has "
              + crossLegs.size()
              + " cross-currency transfer legs — split it in Money and re-export (import.md §6)");
    }
    return crossLegs.get(0);
  }

  /**
   * Distribute {@code target} across the near legs in proportion to their native amounts, the last
   * leg taking whatever the rounding left, so the parts sum to {@code target} exactly.
   */
  private static List<BigDecimal> allocate(
      BigDecimal target,
      List<BigDecimal> nearAmounts,
      BigDecimal nearTotal,
      String base,
      String where) {
    if (nearTotal.signum() == 0) {
      throw new IllegalStateException(
          "Cannot value "
              + where
              + ": its non-base legs net to zero, so the base-currency transfer amount states no"
              + " rate — split it in Money and re-export (import.md §6.5)");
    }
    List<BigDecimal> bases = new ArrayList<>();
    BigDecimal allocated = BigDecimal.ZERO;
    for (int i = 0; i < nearAmounts.size(); i++) {
      if (i == nearAmounts.size() - 1) {
        bases.add(target.subtract(allocated));
      } else {
        BigDecimal exact =
            target
                .multiply(nearAmounts.get(i))
                .divide(nearTotal, DIVISION_SCALE, RoundingMode.HALF_UP);
        BigDecimal part = MoneyFactory.of(exact, base).getAmount();
        bases.add(part);
        allocated = allocated.add(part);
      }
    }
    return bases;
  }

  private List<BigDecimal> ratedBases(
      List<BigDecimal> nearAmounts,
      String base,
      String nearCurrency,
      LocalDate date,
      String where) {
    BigDecimal rate =
        nearCurrency.equals(base)
            ? BigDecimal.ONE
            : exchangeRateService
                .rateAsOf(nearCurrency, date)
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "No exchange rate for "
                                + nearCurrency
                                + " on or before "
                                + GERMAN_DATE.format(date)
                                + " to value "
                                + where
                                + " — add a manual rate for that day and retry (import.md §6.5)"));
    List<BigDecimal> bases = new ArrayList<>();
    for (BigDecimal amount : nearAmounts) {
      bases.add(MoneyFactory.of(amount.multiply(rate), base).getAmount());
    }
    return bases;
  }
}
