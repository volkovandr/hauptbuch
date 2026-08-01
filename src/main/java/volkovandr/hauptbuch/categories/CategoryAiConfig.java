package volkovandr.hauptbuch.categories;

/**
 * One category node's AI-vocabulary configuration row (data-model §13.3, plan stage 9d): the
 * operator's curated overrides on how the receipt parser sees that category. At most one row per
 * category node ({@code unique(account_id)}); the <em>absence</em> of a row means "inherit
 * everything" — no alias, no note, visibility inherited.
 *
 * <p>{@code visible} is a deliberate tri-state (nullable {@link Boolean}): {@code TRUE} = always
 * visible to the AI, {@code FALSE} = hidden, {@code null} = inherit (the nearest ancestor with a
 * set flag, else the type default — expense visible, income hidden). There are no propagation
 * writes; a group's flag is an inheritance lever the effective-visibility walk reads, never a mask
 * stamped onto children.
 *
 * @param categoryAiConfigId surrogate PK; null for a not-yet-persisted row
 * @param accountId the category (an {@code income}/{@code expense} account) this config attaches to
 * @param visible tri-state visibility ({@code null} = inherit)
 * @param alias what the AI sees instead of the real name; null = use the real name
 * @param aiNote per-category prompt guidance (mirrors {@code receipt.ai_note}); null = none
 */
public record CategoryAiConfig(
    Long categoryAiConfigId, long accountId, Boolean visible, String alias, String aiNote) {}
