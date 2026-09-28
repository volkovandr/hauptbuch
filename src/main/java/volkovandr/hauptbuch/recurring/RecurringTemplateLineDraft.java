package volkovandr.hauptbuch.recurring;

import java.math.BigDecimal;
import java.util.List;

/**
 * One line of a {@link RecurringTemplateDraft}, as written by a save. The fields are those of
 * {@link RecurringTemplateLine}; its position comes from its index in the draft's line list.
 *
 * @param accountId the category node or transfer account, or null for a person line
 * @param transferDirection {@code TO}/{@code FROM} for a transfer line, else null
 * @param personId the attributed person for a person line, else null
 * @param personDirection {@code FOR}/{@code BY} alongside {@code personId}, else null
 * @param amount a magnitude, negative for a storno
 * @param note the posting-level note, or null
 * @param tagIds this line's tags, which land on its own leg; never null
 */
public record RecurringTemplateLineDraft(
    Long accountId,
    String transferDirection,
    Long personId,
    String personDirection,
    BigDecimal amount,
    String note,
    List<Long> tagIds) {

  /** Defensively copy the tag ids (the house pattern for record lists). */
  public RecurringTemplateLineDraft {
    tagIds = tagIds == null ? List.of() : List.copyOf(tagIds);
  }
}
