package volkovandr.hauptbuch.analytics;

import java.util.List;
import java.util.Set;

/**
 * What a flow measure counts (reporting.md §6.1): every transaction sums to zero by construction,
 * so a Report must state which account types and/or subtrees it means. Carries the four scope
 * defaults (§6.4) as explicit fields — soft-deleted accounts/transactions and voided transactions
 * are excluded unconditionally and have no field here.
 *
 * @param accountTypes the {@code account.type} values in scope; empty means every type
 * @param accountSubtreeRoots restrict further to these subtrees (and their descendants); empty
 *     means no additional restriction
 * @param includeClosedAccounts closed accounts are in scope by default (§6.4)
 * @param includePendingReview {@code pending_review} transactions are out of scope by default
 *     (§6.4)
 */
public record Scope(
    Set<String> accountTypes,
    List<Long> accountSubtreeRoots,
    boolean includeClosedAccounts,
    boolean includePendingReview) {

  /** Defensively copies the collections to immutable ones. */
  public Scope {
    accountTypes = Set.copyOf(accountTypes);
    accountSubtreeRoots = List.copyOf(accountSubtreeRoots);
  }

  /** A scope restricted to the given account types, with the default toggles (§6.4). */
  public static Scope ofTypes(String... accountTypes) {
    return new Scope(Set.of(accountTypes), List.of(), true, false);
  }
}
