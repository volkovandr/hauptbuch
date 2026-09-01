package volkovandr.hauptbuch.importer;

/**
 * What a QIF {@code L} or {@code S} line names (import.md §3), with any {@code /Class} suffix
 * already split off onto {@link ImportedLine#className()}: a Money category path, unresolved until
 * the category map (§5.2), or an {@code [Account]} reference marking the line as a transfer,
 * unresolved until the account map (§5.1). The canonical representation carries only the raw source
 * text — an id is never resolved here (§3: the parser "never resolves a name to an id").
 */
public sealed interface ImportedTarget {

  /** A Money category path, e.g. {@code Audi:Fuel} — the full path is the map key (§5.2). */
  record CategoryPath(String path) implements ImportedTarget {}

  /**
   * A transfer to/from another Money account, named by its raw account name (the bracketed {@code
   * [Account]} form, brackets stripped) — resolved through the account map (§5.1). When the name is
   * the very account the file is for, the transaction is Money's opening-balance self-transfer and
   * {@link ImportedTransaction#openingBalance()} is set.
   */
  record AccountReference(String accountName) implements ImportedTarget {}
}
