package volkovandr.hauptbuch.importer;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One non-funding leg of a staged transaction, resolved to its ledger account, native amount,
 * currency, posting note and tags (plan f2). {@code crossCurrency} marks a transfer leg whose
 * account is in a currency other than the transaction's near currency — its {@code nativeAmount} is
 * then the real far amount ({@code counter_amount}), not the near-currency figure staging holds.
 *
 * @param accountId the ledger leaf this leg posts to
 * @param nativeAmount the leg's amount in its own account's currency
 * @param currencyCode the far currency, only when {@code crossCurrency}; else null
 * @param note the posting-level note (a split {@code E} memo), or null
 * @param tagIds the leg's tags (map-derived + a {@code /Class} tag), first-occurrence order
 * @param crossCurrency whether this leg crosses a currency boundary
 */
record ImportResolvedLeg(
    long accountId,
    BigDecimal nativeAmount,
    String currencyCode,
    String note,
    List<Long> tagIds,
    boolean crossCurrency) {

  static ImportResolvedLeg sameCurrency(
      long accountId, BigDecimal amount, String note, List<Long> tags) {
    return new ImportResolvedLeg(accountId, amount, null, note, List.copyOf(tags), false);
  }

  static ImportResolvedLeg crossCurrency(
      long accountId, BigDecimal counterAmount, String currencyCode, String note, List<Long> tags) {
    return new ImportResolvedLeg(
        accountId, counterAmount, currencyCode, note, List.copyOf(tags), true);
  }

  /**
   * The tags every leg carries, first-occurrence order (import.md §8) — mirrors {@code
   * ReceiptSplitEntries.sharedTags}. The funding leg gets these so a tag common to every line is
   * visible on the register row (which renders its own posting's tags).
   */
  static List<Long> sharedTags(List<ImportResolvedLeg> legs) {
    if (legs.isEmpty()) {
      return List.of();
    }
    Set<Long> shared = new LinkedHashSet<>(legs.get(0).tagIds());
    for (ImportResolvedLeg leg : legs) {
      shared.retainAll(leg.tagIds());
    }
    return List.copyOf(shared);
  }
}
