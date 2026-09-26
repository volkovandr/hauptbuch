package volkovandr.hauptbuch.analytics.repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * One leaf-grain turnover group of a Report's raw export (reporting.md §13): the postings of one
 * account, with one payee (and, when a Tag dimension asks, one tag of their own), in one Date
 * bucket — summed exactly as {@link RawTurnoverCell} sums a dimension's group, so the export's
 * leaves add up to the rows the Report shows.
 *
 * @param accountId the postings' own account
 * @param tagId one of the postings' own tags; {@code null} unless the query was by tag
 * @param payeeId the postings' transactions' payee; {@code null} for none
 * @param bucketKey the Date bucket
 * @param currencyCode the account's currency
 * @param nativeAmount the signed native sum
 * @param baseAmount the base sum, by the turnover rule (data-model §6.1)
 * @param missingRateCount how many of the postings had no rate for their date
 * @param postingCount how many postings
 * @param transactionIds their transactions, each once — so a coarser group counts them once too
 */
public record LeafTurnoverFact(
    long accountId,
    Long tagId,
    Long payeeId,
    String bucketKey,
    String currencyCode,
    BigDecimal nativeAmount,
    BigDecimal baseAmount,
    long missingRateCount,
    long postingCount,
    List<Long> transactionIds) {

  /** Defensively copies the transaction ids. */
  public LeafTurnoverFact {
    transactionIds = List.copyOf(transactionIds);
  }
}
