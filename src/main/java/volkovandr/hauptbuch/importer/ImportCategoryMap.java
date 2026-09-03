package volkovandr.hauptbuch.importer;

import java.util.List;

/**
 * The category-map panel of the import review (import.md §5.2; plan d1) — one row per Money
 * category path a staged file references, plus the option lists its forms need. Each path maps to
 * <em>one</em> Hauptbuch category (a semantic posting leaf, never a currency leaf) <em>and</em>
 * zero or more tags: {@code Audi:Fuel} → category {@code Transportation - Car - Fuel} + tag {@code
 * Cars:Audi}. ~300 Money paths collapse onto a much smaller curated tree, so the screen also offers
 * bulk assignment (map or tag a multi-select at once).
 *
 * <p>Sign evidence per row — how many staged lines on the path are debits vs credits — makes
 * income-vs-expense obvious before the owner picks (import.md §5.2). Assembled apart from the
 * mutation service, the same render-model-assembler shape as {@link ImportAccountMapPanel}.
 *
 * @param rows one per {@code import_category} row, ordered by Money path
 * @param categoryOptions the postable category leaves a row may map to (currency leaves and groups
 *     excluded by {@code categories}, §5.2), by {@code Parent - Child} path
 * @param tagOptions the canonical {@code Parent:Child} labels of every live tag — the tag field's
 *     datalist suggestions, exactly as the register's chip field uses (register §3.6)
 */
public record ImportCategoryMap(
    List<Row> rows, List<CategoryOption> categoryOptions, List<String> tagOptions) {

  /** Defensive copies of the lists. */
  public ImportCategoryMap {
    rows = rows == null ? List.of() : List.copyOf(rows);
    categoryOptions = categoryOptions == null ? List.of() : List.copyOf(categoryOptions);
    tagOptions = tagOptions == null ? List.of() : List.copyOf(tagOptions);
  }

  /**
   * One Money category path and where it currently maps.
   *
   * @param importCategoryId the {@code import_category} row id — the forms' target
   * @param moneyPath the full Money category path (the map key, §5.2)
   * @param targetAccountId the mapped Hauptbuch category, or null while unmapped
   * @param targetPath the mapped category's {@code Parent - Child} path, or null while unmapped
   * @param debitLineCount staged lines on this path with a positive (spend) amount — sign evidence
   * @param creditLineCount staged lines on this path with a negative (receipt) amount — sign
   *     evidence
   * @param proposedType the type the majority sign suggests — {@code expense} when debits are at
   *     least as many as credits (a refund is an ordinary negative line), {@code income} otherwise,
   *     or null when no staged line references the path yet; the raw counts are shown regardless
   * @param tags the tags currently attached to this path (§5.2, §8)
   */
  public record Row(
      long importCategoryId,
      String moneyPath,
      Long targetAccountId,
      String targetPath,
      long debitLineCount,
      long creditLineCount,
      String proposedType,
      List<Tag> tags) {

    /** Defensive copy. */
    public Row {
      tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /** Whether this row has been resolved to a Hauptbuch category. */
    public boolean mapped() {
      return targetAccountId != null;
    }
  }

  /**
   * A category offered in a row's "map to" select.
   *
   * @param accountId the leaf category's account id
   * @param path its full {@code Parent - Child} display path
   */
  public record CategoryOption(long accountId, String path) {}

  /**
   * One tag currently on a map row — an id and its canonical {@code Parent:Child} label, so the tag
   * field can pre-render the pills the register's chip fragment expects.
   *
   * @param tagId the tag id (the hidden field the "Save tags" form submits)
   * @param label the canonical {@code Parent:Child} label shown on the pill
   */
  public record Tag(long tagId, String label) {}
}
