package volkovandr.hauptbuch.importer;

/**
 * The payee figures the import review reports (import.md §5.3; plan d2). Payees are auto-created,
 * never mapped — a fourth map with several thousand rows would sink the campaign — so the review
 * shows only a count: how many distinct payees the staged files name, how many of those appear on
 * just one staged transaction, and how many staged rows carry a name that was <em>entirely</em>
 * destroyed on export (all {@code ?}/whitespace, §4.4) and will therefore book with no payee at
 * all.
 *
 * <p>{@code distinctPayees} / {@code seenOnce} are an approximate figure: they group on the
 * case-folded {@code payee_text}, whereas the actual resolution ({@code
 * PayeeService.resolveImportedPayee}) also parses the {@code Name - City - Country} address and
 * resolves country aliases, which SQL cannot. Close enough for the sanity check the owner runs
 * against Money's own payee list.
 *
 * @param distinctPayees distinct case-folded payee texts across the campaign's staged rows
 * @param seenOnce how many of those appear on exactly one staged transaction
 * @param destroyedRows staged rows whose {@code P} field was wholly destroyed — these book with a
 *     null {@code payee_id}
 */
public record ImportPayeeSummary(long distinctPayees, long seenOnce, long destroyedRows) {

  /** The empty summary — a campaign with nothing staged, or nothing carrying a payee. */
  public static final ImportPayeeSummary EMPTY = new ImportPayeeSummary(0, 0, 0);

  /** True when there is nothing to report — no named payees and no destroyed names. */
  public boolean empty() {
    return distinctPayees == 0 && destroyedRows == 0;
  }
}
