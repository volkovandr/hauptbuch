package volkovandr.hauptbuch.importer;

import java.util.List;

/**
 * The account-map panel of the import review (import.md §5.1; plan c1) — one row per Money account
 * name a staged file mentions, plus the option lists the row's form needs. Each row maps to an
 * existing Hauptbuch account or a new one created here; the map is many-to-one, so several rows may
 * resolve to the same account (the merge / junk-account story, §5.1).
 *
 * <p>Person targets and the {@code expect-file} flag (§5.4) arrive in c2, the opening-balance
 * reconciliation (§5.1) in c3 — both as further columns on this same panel.
 *
 * @param rows one per {@code import_account} row, ordered by Money account name
 * @param accountOptions the existing asset / liability accounts a row may map to
 * @param currencyOptions the currencies a new account may be opened in (QIF carries none, §5.1)
 */
public record ImportAccountMap(
    List<Row> rows, List<AccountOption> accountOptions, List<CurrencyOption> currencyOptions) {

  /** Defensive copies of the lists. */
  public ImportAccountMap {
    rows = rows == null ? List.of() : List.copyOf(rows);
    accountOptions = accountOptions == null ? List.of() : List.copyOf(accountOptions);
    currencyOptions = currencyOptions == null ? List.of() : List.copyOf(currencyOptions);
  }

  /**
   * One Money account name and where it currently maps.
   *
   * @param importAccountId the {@code import_account} row id — the form target
   * @param moneyAccountName the Money account name (the map key, §5.1); also the default name for a
   *     new account
   * @param targetAccountId the mapped Hauptbuch account, or null while the row is unmapped
   * @param targetName the mapped account's display name, or null while unmapped
   * @param proposedType {@code asset} / {@code liability} proposed from the file header (§4.1), or
   *     null when no file of this account's own has been staged yet
   */
  public record Row(
      long importAccountId,
      String moneyAccountName,
      Long targetAccountId,
      String targetName,
      String proposedType) {

    /** Whether this row has been resolved to a Hauptbuch account. */
    public boolean mapped() {
      return targetAccountId != null;
    }
  }

  /**
   * An existing account offered in the row's "map to" select.
   *
   * @param accountId the account id
   * @param name its display name
   * @param type {@code asset} or {@code liability}
   */
  public record AccountOption(long accountId, String name, String type) {}

  /**
   * A currency offered when a row creates a new account — code plus human-readable name, a
   * read-only projection of {@code ledger}'s {@code Currency}.
   *
   * @param code ISO-4217 code, e.g. {@code EUR}
   * @param name human-readable name, e.g. {@code Euro}
   */
  public record CurrencyOption(String code, String name) {}
}
