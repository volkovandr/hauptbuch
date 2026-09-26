package volkovandr.hauptbuch.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.analytics.repository.PostingValue;
import volkovandr.hauptbuch.analytics.repository.PostingValueRepository;
import volkovandr.hauptbuch.analytics.repository.RawBalanceCell;
import volkovandr.hauptbuch.ledger.ExchangeRateService;

/**
 * A closing-balance figure's drill-down list (reporting.md §12): an opening-balance line, then the
 * postings inside the cell's period.
 *
 * <p>The figure's balance groups ({@link CellValuation#balanceMatches}) carry the ids of their
 * postings inside the period, collected by the very queries that summed them, so the rows are the
 * figure's by construction. Each group's part of the opening line is its closing balance less those
 * postings. The line and every row are valued at the cell's period-end rate, as the cell is (§5.4),
 * so the list closes on it.
 */
@Component
class ClosingBalanceDrillDown {

  private final PostingValueRepository postingValueRepository;
  private final ExchangeRateService exchangeRateService;

  ClosingBalanceDrillDown(
      PostingValueRepository postingValueRepository, ExchangeRateService exchangeRateService) {
    this.postingValueRepository = postingValueRepository;
    this.exchangeRateService = exchangeRateService;
  }

  /**
   * A closing-balance list: its opening line and the rows beneath it.
   *
   * @param opening the opening-balance line
   * @param rows the period's postings, the last one's running figure equal to the cell
   */
  record Listing(DrillDown.Opening opening, List<DrillDown.Row> rows) {

    /** Defensively copies the rows. */
    Listing {
      rows = List.copyOf(rows);
    }
  }

  /** The list behind the closing-balance figure at {@code address} in {@code source}. */
  Listing list(DrillSource source, CellAddress address, Measure measure) {
    String baseCurrency = source.cellContext().baseCurrency();
    List<CellValuation.BalanceMatches> addends = source.balancesBehind(address);
    Map<Long, List<DrillSource.Membership>> membershipsByPosting =
        DrillSource.balancePostingsBehind(addends);
    List<PostingValue> values =
        postingValueRepository.postingValues(
            List.copyOf(membershipsByPosting.keySet()), baseCurrency);
    Map<Long, PostingValue> valueById =
        values.stream().collect(Collectors.toMap(PostingValue::postingId, Function.identity()));
    List<RunningColumn.OpeningPart> openings =
        addends.stream()
            .flatMap(
                addend ->
                    addend.cells().stream()
                        .map(group -> opening(group, addend.creditNatural(), valueById)))
            .toList();
    List<RunningColumn.Entry> entries = RunningColumn.entries(values, membershipsByPosting);
    // A legal closing-balance figure never adds across time (§5.2): its cells share one period.
    CellValuation.BalanceMatches period = addends.get(0);
    RunningColumn.BalanceRun run =
        RunningColumn.closingBalance(
            measure,
            baseCurrency,
            openings,
            entries,
            ratesAt(period.asOf(), openings, baseCurrency));
    return new Listing(
        new DrillDown.Opening(period.periodStart(), run.opening()),
        DrillDown.rows(entries, run.running()));
  }

  /** {@code group}'s part of the opening line: its closing balance less its listed postings. */
  private static RunningColumn.OpeningPart opening(
      RawBalanceCell group, boolean creditNatural, Map<Long, PostingValue> valueById) {
    BigDecimal listed =
        group.postingIds().stream()
            .map(id -> valueById.get(id).amount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return new RunningColumn.OpeningPart(
        group.currencyCode(),
        group.nativeBalance().subtract(listed),
        !group.postingIds().isEmpty(),
        creditNatural);
  }

  /** Each non-base currency's rate at {@code asOf}, the one date the whole list is valued at. */
  private Map<String, BigDecimal> ratesAt(
      LocalDate asOf, List<RunningColumn.OpeningPart> openings, String baseCurrency) {
    Map<String, BigDecimal> rates = new LinkedHashMap<>();
    openings.stream()
        .map(RunningColumn.OpeningPart::currencyCode)
        .filter(currency -> !currency.equals(baseCurrency))
        .distinct()
        .forEach(
            currency ->
                exchangeRateService
                    .rateAsOf(currency, asOf)
                    .ifPresent(rate -> rates.put(currency, rate)));
    return rates;
  }
}
