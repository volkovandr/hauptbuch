package volkovandr.hauptbuch.importer;

import java.util.List;

/**
 * The account-map panel of the import review (import.md §5.1, §5.4; plan c1/c2) — one row per Money
 * account name a staged file mentions, plus the option lists the row's forms need. Each row maps to
 * an existing Hauptbuch account, a new one created here, or a <strong>person</strong> (§5.4). A
 * person target resolves to that person's per-currency leaf at map time, so the row then holds an
 * ordinary {@code account_id} like any other — {@code personTarget} only records that the leaf is a
 * person's, for the summary label. The map is many-to-one, so several rows may resolve to the same
 * account (the merge / junk-account story, §5.1). Every row also carries the {@code expect-file}
 * flag — "is this account's own export still awaited?" — which the commit gate reads.
 *
 * <p>The opening-balance reconciliation (§5.1) arrives in c3 as a further column on this panel.
 *
 * @param rows one per {@code import_account} row, ordered by Money account name
 * @param accountOptions the existing asset / liability accounts a row may map to
 * @param personOptions the live persons a row may map to (§5.4)
 * @param currencyOptions the currencies a new account / person leaf may be opened in (QIF carries
 *     none, §5.1)
 */
public record ImportAccountMap(
    List<Row> rows,
    List<AccountOption> accountOptions,
    List<PersonOption> personOptions,
    List<CurrencyOption> currencyOptions) {

  /** Defensive copies of the lists. */
  public ImportAccountMap {
    rows = rows == null ? List.of() : List.copyOf(rows);
    accountOptions = accountOptions == null ? List.of() : List.copyOf(accountOptions);
    personOptions = personOptions == null ? List.of() : List.copyOf(personOptions);
    currencyOptions = currencyOptions == null ? List.of() : List.copyOf(currencyOptions);
  }

  /**
   * One Money account name and where it currently maps.
   *
   * @param importAccountId the {@code import_account} row id — the forms' target
   * @param moneyAccountName the Money account name (the map key, §5.1); also the default name for a
   *     new account
   * @param targetAccountId the mapped Hauptbuch account (a person's leaf when {@code
   *     personTarget}), or null while the row is unmapped
   * @param targetName the mapped account's display name — the person's name when {@code
   *     personTarget} — or null while unmapped
   * @param personTarget whether {@code targetAccountId} is a person's per-currency leaf (§5.4)
   * @param proposedType {@code asset} / {@code liability} proposed from the file header (§4.1), or
   *     null when no file of this account's own has been staged yet
   * @param expectFile whether this account's own export is still awaited (§5.1) — the commit gate
   *     stays locked while any row is still {@code true}
   */
  public record Row(
      long importAccountId,
      String moneyAccountName,
      Long targetAccountId,
      String targetName,
      boolean personTarget,
      String proposedType,
      boolean expectFile) {

    /** Whether this row has been resolved to a Hauptbuch account (a person leaf included). */
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
   * A live person offered in the row's "map to a person" select (§5.4).
   *
   * @param personId the person id
   * @param name their display name
   */
  public record PersonOption(long personId, String name) {}

  /**
   * A currency offered when a row creates a new account or a person leaf — code plus human-readable
   * name, a read-only projection of {@code ledger}'s {@code Currency}.
   *
   * @param code ISO-4217 code, e.g. {@code EUR}
   * @param name human-readable name, e.g. {@code Euro}
   */
  public record CurrencyOption(String code, String name) {}
}
