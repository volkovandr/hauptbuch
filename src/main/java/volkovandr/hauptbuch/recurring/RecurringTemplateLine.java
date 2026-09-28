package volkovandr.hauptbuch.recurring;

import java.math.BigDecimal;

/**
 * One stored line of a recurring template (data-model §14.1), mirroring {@code SplitLineDraft}. A
 * line is a category ({@code accountId} is the semantic category node), a transfer ({@code
 * accountId} is the real own account, with {@code transferDirection}), or a person ({@code
 * personId} with {@code personDirection}). Its tags live in {@code recurring_template_line_tag}.
 *
 * @param recurringTemplateLineId surrogate PK
 * @param recurringTemplateId the owning template
 * @param accountId the category node or transfer account, or null for a person line
 * @param transferDirection {@code TO}/{@code FROM} for a transfer line, else null
 * @param personId the attributed person for a person line, else null
 * @param personDirection {@code FOR}/{@code BY} alongside {@code personId}, else null
 * @param amount the line's amount as the split panel takes it: a magnitude, negative for a storno
 * @param note the posting-level note, or null
 * @param sortOrder the line's position within the template
 */
public record RecurringTemplateLine(
    long recurringTemplateLineId,
    long recurringTemplateId,
    Long accountId,
    String transferDirection,
    Long personId,
    String personDirection,
    BigDecimal amount,
    String note,
    int sortOrder) {}
