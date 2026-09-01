package volkovandr.hauptbuch.importer;

/**
 * The import campaign lifecycle states (import.md §2), stored verbatim in the {@code
 * import_session.state} check constraint.
 *
 * <p>Coded as {@code String} constants rather than a Java {@code enum} to match the project's
 * text-coded column convention ({@code account.type}, {@code receipt.state}) and map straight
 * through {@code JdbcClient} without a converter — the same choice {@link
 * volkovandr.hauptbuch.importer.repository.ImportSessionRepository}'s row mapping relies on.
 */
public final class ImportSessionState {

  /** The one campaign accepting uploads — at most one exists at a time (§2). */
  public static final String OPEN = "open";

  /** The commit succeeded; staging is spent (f2). */
  public static final String COMMITTED = "committed";

  /** Abandoned without committing — the feature's only "undo" (§2). */
  public static final String DISCARDED = "discarded";

  private ImportSessionState() {}
}
