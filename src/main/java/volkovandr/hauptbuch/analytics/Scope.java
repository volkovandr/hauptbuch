package volkovandr.hauptbuch.analytics;

import java.util.Set;

/**
 * What a flow measure counts (reporting.md §6.1): every transaction sums to zero by construction,
 * so a Report must state which account types it means. Carries the four scope defaults (§6.4) as
 * explicit fields — soft-deleted accounts/transactions and voided transactions are excluded
 * unconditionally and have no field here.
 *
 * <p>Scope no longer names account subtrees (reporting.md §6.1, d3): it compiled to exactly the SQL
 * of an "amounts booked to" Account/Category filter, so the editor would have offered two controls
 * for one meaning. Narrowing below an account type is a posting-level filter's job.
 *
 * @param accountTypes the {@code account.type} values in scope; empty means every type
 * @param includeClosedAccounts closed accounts are in scope by default (§6.4)
 * @param includePendingReview {@code pending_review} transactions are out of scope by default
 *     (§6.4)
 */
public record Scope(
    Set<String> accountTypes, boolean includeClosedAccounts, boolean includePendingReview) {

  /** Defensively copies the collection to an immutable one. */
  public Scope {
    accountTypes = Set.copyOf(accountTypes);
  }

  /** A scope restricted to the given account types, with the default toggles (§6.4). */
  public static Scope ofTypes(String... accountTypes) {
    return new Scope(Set.of(accountTypes), true, false);
  }
}
