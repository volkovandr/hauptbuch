package volkovandr.hauptbuch.importer;

import java.util.ArrayList;
import java.util.List;

/**
 * The review's issues list and commit-gate state (import.md §9.3; plan e4) — the exceptions the
 * campaign carries besides the maps themselves: accounts and category paths still unmapped or
 * stale, accounts still awaiting their own export, and still-parked cross-currency transfer legs.
 * Orphan map rows — a Money name or path no live staged file or posting still references, left
 * behind by a file removal (§5) — are excluded from every list here by {@link ImportIssuesPanel};
 * they are harmless clutter, not a blocker (plan e4's "orphan map rows" decision).
 *
 * <p><strong>Unparseable lines and split-sum mismatches</strong>, both named in import.md §9's
 * original issues-list sketch, carry no row here: §4.5 settled that either condition rejects the
 * <strong>whole file</strong> before anything stages, so neither can ever appear in staged data —
 * prevented upstream, not tracked here.
 *
 * <p><strong>Destroyed-payee counts</strong> likewise carry no row of their own: the payee panel
 * ({@link ImportPayeeSummary}) already reports and links to that count; this list is only for
 * issues that panel does not already surface.
 *
 * <p>{@link #locked()} answers the commit gate's three conditions this class owns (§9): every
 * referenced account is mapped and not still expecting a file, every referenced category path is
 * mapped to a still-postable leaf, and no cross-currency transfer leg is still parked. The gate's
 * fourth condition — the ledger duplicate scan — is f1's, not yet built, and is not represented
 * here.
 */
public record ImportIssues(
    List<UnmappedRow> unmappedAccounts,
    List<UnmappedRow> expectingFile,
    List<CategoryRow> unmappedCategories,
    long unresolvedParkLegCount) {

  /** The empty issues list — a campaign with nothing to flag. */
  public static final ImportIssues EMPTY = new ImportIssues(List.of(), List.of(), List.of(), 0);

  /** Defensive copies of the lists. */
  public ImportIssues {
    unmappedAccounts = unmappedAccounts == null ? List.of() : List.copyOf(unmappedAccounts);
    expectingFile = expectingFile == null ? List.of() : List.copyOf(expectingFile);
    unmappedCategories = unmappedCategories == null ? List.of() : List.copyOf(unmappedCategories);
  }

  /** True when nothing here needs the owner's attention. */
  public boolean empty() {
    return unmappedAccounts.isEmpty()
        && expectingFile.isEmpty()
        && unmappedCategories.isEmpty()
        && unresolvedParkLegCount == 0;
  }

  /**
   * Whether the commit gate is locked (§9) — every condition this class can answer. The fourth
   * condition, the ledger duplicate scan, is f1's and not represented here, so an unlocked result
   * from this method alone is not yet "ready to commit".
   */
  public boolean locked() {
    return !unmappedAccounts.isEmpty()
        || !expectingFile.isEmpty()
        || !unmappedCategories.isEmpty()
        || unresolvedParkLegCount > 0;
  }

  /** One line per unmet gate condition, in a stable order — the locked banner's explanation. */
  public List<String> lockReasons() {
    List<String> reasons = new ArrayList<>();
    if (!expectingFile.isEmpty()) {
      reasons.add(expectingFile.size() + " account(s) still expecting a file");
    }
    if (!unmappedAccounts.isEmpty()) {
      reasons.add(unmappedAccounts.size() + " account(s) not yet mapped");
    }
    if (!unmappedCategories.isEmpty()) {
      reasons.add(
          unmappedCategories.size() + " category path(s) not yet mapped or no longer postable");
    }
    if (unresolvedParkLegCount > 0) {
      // A leg count, not a transfer count: an unresolved pair with both sightings staged
      // contributes two parked legs (ImportMirrorRepository#parkedCrossCurrencyLegs), so this can
      // overstate the number of transfers awaiting the owner — "leg(s)" says exactly what is
      // counted rather than implying a transfer tally.
      reasons.add(unresolvedParkLegCount + " still-parked cross-currency leg(s)");
    }
    return reasons;
  }

  /**
   * One unmapped, or still-{@code expect-file}, account-map row.
   *
   * @param importAccountId the row id — the account map's scroll anchor
   * @param moneyAccountName the Money account name
   */
  public record UnmappedRow(long importAccountId, String moneyAccountName) {}

  /**
   * One unmapped or stale category-map row.
   *
   * @param importCategoryId the row id — the category map's scroll anchor
   * @param moneyPath the Money category path
   * @param stale true when the row carries a mapped {@code account_id} that no longer resolves to a
   *     postable category leaf — a mid-campaign subdivision, {@code .scratch/import/issues/01} —
   *     rather than never having been mapped at all
   */
  public record CategoryRow(long importCategoryId, String moneyPath, boolean stale) {}
}
