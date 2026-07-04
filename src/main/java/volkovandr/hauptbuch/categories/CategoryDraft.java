package volkovandr.hauptbuch.categories;

/**
 * A new category as submitted to {@link CategoryService#createCategory} — before it is resolved
 * into one or two new account rows (creating a child under a posted-to leaf triggers subdivision,
 * data-model §6.5).
 *
 * <p>Unlike {@code AccountDraft}, there is no currency field: the user thinks of a category
 * semantically ("Food"), not per-currency ("Food EUR"). Every category leaf this screen creates is
 * denominated in the book's base currency; a foreign-currency leaf is provisioned later by real
 * usage, not guessed at here (plan stage 6b).
 *
 * @param name display name; must not be blank
 * @param type {@code income} or {@code expense} — the two types the categories screen manages
 * @param parentId optional parent category; null for a new top-level category
 */
public record CategoryDraft(String name, String type, Long parentId) {}
