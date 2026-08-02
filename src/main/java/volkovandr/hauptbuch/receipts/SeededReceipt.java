package volkovandr.hauptbuch.receipts;

import java.util.List;

/**
 * The whole result of seeding a decoded parse (stage 9e): the denormalised header and the draft
 * lines, ready for the analyse writer to persist. Even a zero-item parse produces a valid seed (an
 * empty line list) — that still flips the receipt to {@code processed}; post-process (9f) is the
 * fixing surface (data-model §13.1).
 *
 * @param header the denormalised header
 * @param lines the draft lines (possibly empty)
 */
public record SeededReceipt(ParsedHeader header, List<ReceiptLineDraft> lines) {

  /** Defensive copy of the line list (the house pattern for record lists). */
  public SeededReceipt {
    lines = lines == null ? List.of() : List.copyOf(lines);
  }
}
