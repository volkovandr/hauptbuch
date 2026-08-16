package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (§1.5): {@link ReceiptSort}'s request-token resolution and its {@code sortByLookup}
 * ordering logic — pure, dependency-free, so no mocks are needed.
 */
class ReceiptSortTest {

  // ── Column/direction resolution ───────────────────────────────────────────

  @Test
  void resolveColumnKeepsRecognisedKey() {
    assertThat(ReceiptSort.resolveColumn("total")).isEqualTo("total");
  }

  @Test
  void resolveColumnFallsBackToCapturedForAnUnrecognisedOrMissingKey() {
    assertThat(ReceiptSort.resolveColumn("bogus")).isEqualTo(ReceiptSort.CAPTURED);
    assertThat(ReceiptSort.resolveColumn(null)).isEqualTo(ReceiptSort.CAPTURED);
  }

  @Test
  void resolveDirectionKeepsRecognisedDirection() {
    assertThat(ReceiptSort.resolveDirection(ReceiptSort.CAPTURED, "asc")).isEqualTo("asc");
  }

  @Test
  void resolveDirectionFallsBackToTheColumnsDefaultForAnUnrecognisedOrMissingDirection() {
    assertThat(ReceiptSort.resolveDirection(ReceiptSort.MERCHANT, "bogus"))
        .isEqualTo(ReceiptSort.ASC);
    assertThat(ReceiptSort.resolveDirection(ReceiptSort.CAPTURED, null))
        .isEqualTo(ReceiptSort.DESC);
  }

  @Test
  void defaultDirectionIsDescendingForEveryColumnExceptMerchant() {
    assertThat(ReceiptSort.defaultDirectionFor(ReceiptSort.CAPTURED)).isEqualTo(ReceiptSort.DESC);
    assertThat(ReceiptSort.defaultDirectionFor(ReceiptSort.TXN_DATE)).isEqualTo(ReceiptSort.DESC);
    assertThat(ReceiptSort.defaultDirectionFor(ReceiptSort.TOTAL)).isEqualTo(ReceiptSort.DESC);
    assertThat(ReceiptSort.defaultDirectionFor(ReceiptSort.MERCHANT)).isEqualTo(ReceiptSort.ASC);
  }

  // ── sortByLookup: Txn date / Merchant (issue tracker #11) ─────────────────

  /**
   * The Txn-date/Merchant sort mechanism: neither is a real column, so both re-order an
   * already-fetched list by the same batched lookup its cells render from ({@link
   * ReceiptService#transactionDates}, {@link ReceiptService#merchantDisplays}) — exercised here
   * with a generic {@code String} lookup, since the ordering logic doesn't care which of the two it
   * is.
   */
  @Test
  void sortByLookupOrdersAscendingByLookupValue() {
    Receipt a = receiptCapturedAt(1L, "2026-07-10T10:00:00Z");
    Receipt b = receiptCapturedAt(2L, "2026-07-10T10:00:00Z");
    Map<Long, String> lookup = new HashMap<>(Map.of(1L, "Zebra", 2L, "Apple"));

    List<Receipt> sorted =
        ReceiptSort.sortByLookup(List.of(a, b), lookup, Comparator.naturalOrder(), false);

    assertThat(sorted).extracting(Receipt::receiptId).containsExactly(2L, 1L);
  }

  @Test
  void sortByLookupOrdersDescendingByLookupValue() {
    Receipt a = receiptCapturedAt(1L, "2026-07-10T10:00:00Z");
    Receipt b = receiptCapturedAt(2L, "2026-07-10T10:00:00Z");
    Map<Long, String> lookup = new HashMap<>(Map.of(1L, "Zebra", 2L, "Apple"));

    List<Receipt> sorted =
        ReceiptSort.sortByLookup(List.of(a, b), lookup, Comparator.naturalOrder(), true);

    assertThat(sorted).extracting(Receipt::receiptId).containsExactly(1L, 2L);
  }

  /**
   * The Merchant sort uses {@code String.CASE_INSENSITIVE_ORDER} as its {@code valueOrder} (wired
   * at the call site, {@code ReceiptRegisterController}) rather than natural order, so a
   * lowercase-initial display doesn't sort after every uppercase-initial one — matching {@code
   * RegisterService}'s own name-sort convention.
   */
  @Test
  void sortByLookupHonoursCaseInsensitiveValueOrderWhenGiven() {
    Receipt lowercase = receiptCapturedAt(1L, "2026-07-10T10:00:00Z");
    Receipt uppercase = receiptCapturedAt(2L, "2026-07-10T10:00:00Z");
    Map<Long, String> lookup = new HashMap<>(Map.of(1L, "aldi", 2L, "Zalando"));

    List<Receipt> sorted =
        ReceiptSort.sortByLookup(
            List.of(uppercase, lowercase), lookup, String.CASE_INSENSITIVE_ORDER, false);

    assertThat(sorted).extracting(Receipt::receiptId).containsExactly(1L, 2L);
  }

  @Test
  void sortByLookupPlacesMissingEntriesLastAscendingAndFirstDescending() {
    Receipt withValue = receiptCapturedAt(1L, "2026-07-10T10:00:00Z");
    Receipt withoutValue = receiptCapturedAt(2L, "2026-07-10T10:00:00Z");
    // A real HashMap, not Map.of(...): production's map permits the missing-key lookup below.
    Map<Long, String> lookup = new HashMap<>(Map.of(1L, "Rewe"));

    List<Receipt> ascending =
        ReceiptSort.sortByLookup(
            List.of(withValue, withoutValue), lookup, Comparator.naturalOrder(), false);
    List<Receipt> descending =
        ReceiptSort.sortByLookup(
            List.of(withValue, withoutValue), lookup, Comparator.naturalOrder(), true);

    assertThat(ascending).extracting(Receipt::receiptId).containsExactly(1L, 2L);
    assertThat(descending).extracting(Receipt::receiptId).containsExactly(2L, 1L);
  }

  @Test
  void sortByLookupBreaksTiesByCapturedDateThenReceiptIdInTheSortDirection() {
    Receipt earlier = receiptCapturedAt(1L, "2026-07-10T10:00:00Z");
    Receipt later = receiptCapturedAt(2L, "2026-07-20T10:00:00Z");
    // Same value for both — the tiebreak decides.
    Map<Long, String> lookup = new HashMap<>(Map.of(1L, "Rewe", 2L, "Rewe"));

    List<Receipt> descending =
        ReceiptSort.sortByLookup(List.of(earlier, later), lookup, Comparator.naturalOrder(), true);

    assertThat(descending).extracting(Receipt::receiptId).containsExactly(2L, 1L);
  }

  /**
   * A receipt with a configurable id and capture instant, everything else blank — sortByLookup's
   * tiebreak only reads {@link Receipt#receiptId()} and {@link Receipt#capturedAt()}.
   */
  private Receipt receiptCapturedAt(long id, String instant) {
    return new Receipt(
        id,
        "new",
        OffsetDateTime.parse(instant),
        "mobile",
        "originals/2026/07/x.jpg",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
