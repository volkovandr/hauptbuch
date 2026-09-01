package volkovandr.hauptbuch.importer;

/**
 * A row of the category map (import.md §5.2), keyed by the full Money path ({@code Audi:Fuel}),
 * accumulated across every file in the campaign. Plan b3 creates it <strong>unmapped</strong> —
 * {@code accountId} and {@code proposedType} null, the sign counters at zero — as each file's
 * category paths are folded in; slice d resolves the semantic category node, its tags, and the sign
 * evidence.
 *
 * @param importCategoryId surrogate PK; null for a not-yet-persisted row
 * @param importSessionId the campaign this map belongs to
 * @param moneyPath the full Money category path — the map key, unique per session
 * @param accountId the semantic Hauptbuch category account this maps to (d1), or null while
 *     unmapped; never a currency leaf (§5.2)
 * @param debitLineCount how many staged lines on this path are positive — sign evidence (d1)
 * @param creditLineCount how many staged lines on this path are negative — sign evidence (d1)
 * @param proposedType {@code income} / {@code expense} proposed from the sign evidence (d1), or
 *     null
 */
public record ImportCategory(
    Long importCategoryId,
    Long importSessionId,
    String moneyPath,
    Long accountId,
    int debitLineCount,
    int creditLineCount,
    String proposedType) {}
