package volkovandr.hauptbuch.receipts;

/**
 * The previous/next receipt ids around the one open on the processing screen, within the filtered,
 * ordered register list (§6) — what the screen's ↑ prev / ↓ next navigation walks. Either side is
 * null at the ends of the list (or when the open receipt is no longer in the filtered set).
 *
 * @param prev the earlier-captured neighbour, or null if this is the first
 * @param next the later-captured neighbour, or null if this is the last
 */
public record ReceiptNeighbours(Long prev, Long next) {

  /** No neighbours — a receipt not present in the filtered list. */
  public static final ReceiptNeighbours NONE = new ReceiptNeighbours(null, null);
}
