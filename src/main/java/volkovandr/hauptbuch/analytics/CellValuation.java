package volkovandr.hauptbuch.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.analytics.repository.RawBalanceCell;
import volkovandr.hauptbuch.analytics.repository.RawTurnoverCell;
import volkovandr.hauptbuch.analytics.repository.TopLevelNode;
import volkovandr.hauptbuch.ledger.ExchangeRateService;

/**
 * Values one grid cell (reporting.md §5, the legality rules of §7.2): the credit-natural display
 * flip (data-model §4.1), the turnover/closing-balance/count measures, and which {@link
 * Cell.Reason} makes a cell {@link Cell.Illegal}. Split out of {@link ReportGridBuilder} — which
 * keeps axis assembly, row suppression and totals — so neither class carries both concerns' worth
 * of branching.
 */
@Component
class CellValuation {

  private static final Set<String> CREDIT_NATURAL_TYPES = Set.of("income", "liability", "equity");

  private final ExchangeRateService exchangeRateService;

  CellValuation(ExchangeRateService exchangeRateService) {
    this.exchangeRateService = exchangeRateService;
  }

  Cell compute(Measure measure, AxisNode rowNode, AxisNode columnBucketNode, CellContext context) {
    AxisPlan axes = context.axes();
    Dimension nonDateDim = axes.nonDateDim();
    String dimKey =
        nonDateDim == null
            ? AxisNode.TOTAL_KEY
            : (axes.rowDim() == nonDateDim ? rowNode.key() : columnBucketNode.key());
    boolean creditNatural = isCreditNatural(context.candidatesByKey().get(dimKey), context.scope());

    String monthKey =
        axes.dateOnRows() ? rowNode.key() : axes.dateOnColumns() ? columnBucketNode.key() : null;
    if (measure.kind() == MeasureKind.TURNOVER) {
      List<RawTurnoverCell> matches =
          turnoverMatches(measure.leg(), dimKey, monthKey, context.data());
      return turnoverCellValue(matches, measure, context.baseCurrency(), creditNatural);
    }
    if (measure.kind() == MeasureKind.COUNT_POSTINGS
        || measure.kind() == MeasureKind.COUNT_TRANSACTIONS) {
      List<RawTurnoverCell> matches = turnoverMatches(Leg.NET, dimKey, monthKey, context.data());
      return countCellValue(matches, measure.kind());
    }
    return computeClosingBalanceCell(
        measure, rowNode, columnBucketNode, dimKey, creditNatural, axes, context);
  }

  private Cell computeClosingBalanceCell(
      Measure measure,
      AxisNode rowNode,
      AxisNode columnBucketNode,
      String dimKey,
      boolean creditNatural,
      AxisPlan axes,
      CellContext context) {
    String bucketKey =
        axes.dateOnRows()
            ? rowNode.key()
            : axes.dateOnColumns() ? columnBucketNode.key() : AxisNode.TOTAL_KEY;
    List<RawBalanceCell> raw =
        context.data().balanceByBucketKey().getOrDefault(bucketKey, List.of());
    LocalDate asOf = context.data().asOfByBucketKey().get(bucketKey);
    List<RawBalanceCell> matches =
        raw.stream().filter(c -> c.dimensionKey().equals(dimKey)).toList();
    return balanceCellValue(matches, measure, context.baseCurrency(), asOf, creditNatural);
  }

  /**
   * The credit-natural flip for one cell: a node with a single known type (an account-tree row, or
   * an {@link Dimension#ACCOUNT_TYPE}/{@link Dimension#PERSON} row, both unambiguous) flips by its
   * own type; a node with none — no row/column dimension at all, or a dimension spanning more than
   * one type ({@link Dimension#CURRENCY}, {@link Dimension#PAYEE}) — falls back to {@link
   * #isCreditNaturalScope}.
   */
  private static boolean isCreditNatural(TopLevelNode node, Scope scope) {
    if (node != null && node.type() != null) {
      return CREDIT_NATURAL_TYPES.contains(node.type());
    }
    return isCreditNaturalScope(scope);
  }

  /**
   * The credit-natural flip for a report with no row/column dimension (a plain total), or a
   * dimension whose node carries no single type: flips only when every account type in scope shares
   * the credit-natural side, so a total mixing income and expense — which has no single correct
   * sign — is left unflipped rather than guessing.
   */
  private static boolean isCreditNaturalScope(Scope scope) {
    return !scope.accountTypes().isEmpty()
        && scope.accountTypes().stream().allMatch(CREDIT_NATURAL_TYPES::contains);
  }

  private static List<RawTurnoverCell> turnoverMatches(
      Leg leg, String dimKey, String monthKey, GridData data) {
    List<RawTurnoverCell> raw = data.turnoverByLeg().getOrDefault(leg, List.of());
    return raw.stream()
        .filter(c -> c.dimensionKey().equals(dimKey))
        .filter(c -> monthKey == null || c.monthKey().equals(monthKey))
        .toList();
  }

  /**
   * A count measure's value (§5.5): the raw rows are still partitioned by currency (the same
   * NET-leg turnover data every measure shares), so {@link RawTurnoverCell#postingCount()} sums
   * exactly — a posting belongs to exactly one currency group. {@link
   * RawTurnoverCell#transactionCount()} is each currency group's own distinct-transaction count;
   * summing them over-counts a transaction whose legs in this cell span more than one currency
   * (rare — a cross-currency split within one category/month), counting it once per currency
   * touched rather than once overall. Not corrected here: doing so needs the raw transaction ids,
   * which the aggregated query does not carry.
   */
  private static Cell countCellValue(List<RawTurnoverCell> matches, MeasureKind kind) {
    if (matches.isEmpty()) {
      return Cell.BLANK;
    }
    long total =
        matches.stream()
            .mapToLong(
                kind == MeasureKind.COUNT_POSTINGS
                    ? RawTurnoverCell::postingCount
                    : RawTurnoverCell::transactionCount)
            .sum();
    return new Cell.Count(total);
  }

  private Cell turnoverCellValue(
      List<RawTurnoverCell> matches, Measure measure, String baseCurrency, boolean creditNatural) {
    if (matches.isEmpty()) {
      return Cell.BLANK;
    }
    if (measure.currency() == PresentationCurrency.ACCOUNT) {
      Set<String> currencies =
          matches.stream().map(RawTurnoverCell::currencyCode).collect(Collectors.toSet());
      if (currencies.size() > 1) {
        return new Cell.Illegal(Cell.Reason.MULTI_CURRENCY);
      }
      BigDecimal sum =
          matches.stream()
              .map(RawTurnoverCell::nativeAmount)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      return new Cell.Value(creditNatural ? sum.negate() : sum, currencies.iterator().next());
    }
    boolean missingRate = matches.stream().anyMatch(c -> c.missingRateCount() > 0);
    if (missingRate) {
      return new Cell.Illegal(Cell.Reason.MISSING_RATE);
    }
    BigDecimal sum =
        matches.stream().map(RawTurnoverCell::baseAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    return new Cell.Value(creditNatural ? sum.negate() : sum, baseCurrency);
  }

  private Cell balanceCellValue(
      List<RawBalanceCell> matches,
      Measure measure,
      String baseCurrency,
      LocalDate asOf,
      boolean creditNatural) {
    if (matches.isEmpty()) {
      return Cell.BLANK;
    }
    if (measure.currency() == PresentationCurrency.ACCOUNT) {
      Set<String> currencies =
          matches.stream().map(RawBalanceCell::currencyCode).collect(Collectors.toSet());
      if (currencies.size() > 1) {
        return new Cell.Illegal(Cell.Reason.MULTI_CURRENCY);
      }
      BigDecimal sum =
          matches.stream()
              .map(RawBalanceCell::nativeBalance)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      return new Cell.Value(creditNatural ? sum.negate() : sum, currencies.iterator().next());
    }
    BigDecimal total = BigDecimal.ZERO;
    for (RawBalanceCell cell : matches) {
      if (cell.currencyCode().equals(baseCurrency)) {
        total = total.add(cell.nativeBalance());
        continue;
      }
      Optional<BigDecimal> rate = exchangeRateService.rateAsOf(cell.currencyCode(), asOf);
      if (rate.isEmpty()) {
        return new Cell.Illegal(Cell.Reason.MISSING_RATE);
      }
      total = total.add(cell.nativeBalance().multiply(rate.get()));
    }
    return new Cell.Value(creditNatural ? total.negate() : total, baseCurrency);
  }

  /** Everything {@link #compute} needs, bundled to keep its parameter list short. */
  record CellContext(
      AxisPlan axes,
      Map<String, TopLevelNode> candidatesByKey,
      GridData data,
      String baseCurrency,
      Scope scope) {}
}
