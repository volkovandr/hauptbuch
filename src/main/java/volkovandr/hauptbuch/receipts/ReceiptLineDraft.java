package volkovandr.hauptbuch.receipts;

import java.math.BigDecimal;
import java.util.List;

/**
 * One line the seeder produced from a parsed item (stage 9e), before it is persisted as a {@code
 * receipt_line} (+ its {@code receipt_line_tag} rows). Carries the resolved ids only — category or
 * transfer target ({@code accountId}), beneficiary ({@code personId}), and the resolved tag ids —
 * unresolved echoes having already been dropped (data-model §13.2/§13.3).
 *
 * @param description the line description
 * @param amount the line amount (the parsed total price)
 * @param accountId resolved category leaf / transfer target, or null (uncategorised)
 * @param personId resolved beneficiary person, or null
 * @param note a free-text note, or null
 * @param sortOrder the line's position
 * @param tagIds the resolved leaf tag ids for this line (possibly empty)
 * @param aiTargetText the AI's raw target term kept for provenance (data-model §13.2): the category
 *     echo, or the transfer signal as {@code transfer: cash} / {@code transfer: card •1234}; null
 *     when the AI named no target
 */
public record ReceiptLineDraft(
    String description,
    BigDecimal amount,
    Long accountId,
    Long personId,
    String note,
    int sortOrder,
    List<Long> tagIds,
    String aiTargetText) {

  /** Defensive copy of the tag ids (the house pattern for record lists). */
  public ReceiptLineDraft {
    tagIds = tagIds == null ? List.of() : List.copyOf(tagIds);
  }
}
