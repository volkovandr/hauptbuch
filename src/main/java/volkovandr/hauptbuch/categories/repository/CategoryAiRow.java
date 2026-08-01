package volkovandr.hauptbuch.categories.repository;

/**
 * One row of the AI-Vocabulary projection (data-model §13.3, plan stage 9d): a live income/expense
 * category node (currency leaves excluded, §6.5) annotated with everything the effective-visibility
 * walk resolves — computed in a recursive CTE ({@link AiVocabularyRepository#projection}) so the
 * inheritance logic lives in the SQL, not in Java. The service assembles the AI-facing tree, the
 * name→leaf resolution set, and the categories-list annotations from a flat list of these.
 *
 * @param accountId the category account
 * @param name its real (un-aliased) name — what the categories screen shows
 * @param type {@code income} or {@code expense} (fixes the visibility default: expense visible)
 * @param parentId parent category, null at top level
 * @param leaf whether this is a semantic leaf — no live non-currency-leaf child; postings and
 *     resolution land only here (data-model §5)
 * @param ownVisible this node's own tri-state flag ({@code null} = inherit / no config row)
 * @param effVisible the resolved effective visibility (own flag, else nearest set ancestor, else
 *     the type default)
 * @param visibleSourceId the account whose set flag drives {@code effVisible}; {@code null} when no
 *     ancestor sets one and the type default applies. Equal to {@code accountId} when set here.
 * @param alias this node's own alias (the AI-facing name), {@code null} if none
 * @param note this node's own AI note, {@code null} if none
 * @param effName the effective name (alias if set, else the real name)
 * @param effPath the root-to-leaf effective path — every ancestor's effective name joined by the
 *     requested separator (a group alias renames its children's paths)
 */
public record CategoryAiRow(
    long accountId,
    String name,
    String type,
    Long parentId,
    boolean leaf,
    Boolean ownVisible,
    boolean effVisible,
    Long visibleSourceId,
    String alias,
    String note,
    String effName,
    String effPath) {}
