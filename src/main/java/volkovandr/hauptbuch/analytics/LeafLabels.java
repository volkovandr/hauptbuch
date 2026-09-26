package volkovandr.hauptbuch.analytics;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import volkovandr.hauptbuch.analytics.repository.LeafAccount;
import volkovandr.hauptbuch.analytics.repository.NodeKey;
import volkovandr.hauptbuch.analytics.repository.TopLevelNode;

/**
 * Which leaf of a dimension a leaf-grain group belongs to, and how a raw export labels it
 * (reporting.md §13): an account or a tag by its path from the root ({@code Cash:Cash-USD}), a debt
 * leaf beneath "Personal debts" by its owner and currency, as the Report's own tree shows it, and a
 * flat dimension's value as the Report labels it.
 */
final class LeafLabels {

  private static final String NO_PAYEE_KEY = "none";
  private static final String NO_PAYEE_LABEL = "(No payee)";
  private static final String NESTED_LABEL_SEPARATOR = " / ";

  private final Map<Long, LeafAccount> accounts;
  private final Map<String, String> tagPaths;
  private final Map<String, String> payeeNames;
  private final List<String> tickedTagPaths;

  /**
   * Labels over {@code accounts}, {@code tags} and {@code payees}.
   *
   * @param tickedTagKeys the tags a Tag filter on amounts booked ticks — the only tags the Report
   *     shows (§6.3), so the only subtrees raw exports; empty when there is no such filter
   */
  LeafLabels(
      List<LeafAccount> accounts,
      List<TopLevelNode> tags,
      List<TopLevelNode> payees,
      List<String> tickedTagKeys) {
    this.accounts =
        accounts.stream().collect(Collectors.toMap(LeafAccount::accountId, Function.identity()));
    this.tagPaths = tags.stream().collect(Collectors.toMap(TopLevelNode::key, TopLevelNode::label));
    this.payeeNames =
        payees.stream().collect(Collectors.toMap(TopLevelNode::key, TopLevelNode::label));
    this.tickedTagPaths =
        tickedTagKeys.stream().map(tagPaths::get).filter(path -> path != null).toList();
  }

  /**
   * One leaf of an axis.
   *
   * @param key its key, {@code "<outer>|<inner>"} when the axis nests two dimensions
   * @param label its label, the two paths joined by {@code " / "} when nested
   * @param type the account type its cell's sign flips by (data-model §4.1); {@code null} for a
   *     dimension spanning more than one type
   */
  record Leaf(String key, String label, String type) {}

  /**
   * The leaf of the axis carrying {@code outer} (and {@code inner}, when it nests one) that a group
   * of {@code accountId}'s postings with {@code tagId} and {@code payeeId} belongs to — the one
   * total leaf when the axis carries no dimension; {@code null} when the group is in none — a Tag
   * axis's untagged postings, a Person axis's own accounts.
   */
  Leaf leaf(Dimension outer, Dimension inner, long accountId, Long tagId, Long payeeId) {
    if (outer == null) {
      return new Leaf(AxisNode.TOTAL_KEY, "Total", null);
    }
    Leaf outerLeaf = leaf(outer, accountId, tagId, payeeId);
    if (outerLeaf == null || inner == null) {
      return outerLeaf;
    }
    Leaf innerLeaf = leaf(inner, accountId, tagId, payeeId);
    return innerLeaf == null
        ? null
        : new Leaf(
            outerLeaf.key() + "|" + innerLeaf.key(),
            outerLeaf.label() + NESTED_LABEL_SEPARATOR + innerLeaf.label(),
            innerLeaf.type());
  }

  private Leaf leaf(Dimension dimension, long accountId, Long tagId, Long payeeId) {
    return switch (dimension) {
      case TAG -> tagLeaf(tagId);
      case PAYEE -> payeeLeaf(payeeId);
      case DATE -> throw new IllegalArgumentException("Date is not a leaf dimension.");
      default -> accountDimensionLeaf(dimension, accounts.get(accountId));
    };
  }

  /**
   * A dimension read off the posting's account: the account itself, its owner, currency or type.
   */
  private static Leaf accountDimensionLeaf(Dimension dimension, LeafAccount account) {
    return switch (dimension) {
      case CATEGORY, ACCOUNT -> accountLeaf(account);
      case PERSON -> personLeaf(account);
      case CURRENCY -> new Leaf(account.currencyCode(), account.currencyCode(), null);
      default ->
          new Leaf(account.type(), ReportSettingsView.capitalize(account.type()), account.type());
    };
  }

  /** A live tag inside the ticked subtrees, if any are ticked; {@code null} for any other. */
  private Leaf tagLeaf(Long tagId) {
    String path = tagId == null ? null : tagPaths.get(tagId.toString());
    if (path == null || !(tickedTagPaths.isEmpty() || isTicked(path))) {
      return null;
    }
    return new Leaf(tagId.toString(), path, null);
  }

  private boolean isTicked(String path) {
    return tickedTagPaths.stream()
        .anyMatch(ticked -> path.equals(ticked) || path.startsWith(ticked + ":"));
  }

  /** A debt leaf's owner; {@code null} for any other account — Person counts debt leaves only. */
  private static Leaf personLeaf(LeafAccount account) {
    return account.personId() == null
        ? null
        : new Leaf(account.personId().toString(), account.personName(), "asset");
  }

  private static Leaf accountLeaf(LeafAccount account) {
    String label =
        account.personId() == null
            ? account.path()
            : NodeKey.PERSONAL_DEBTS_LABEL
                + ":"
                + account.personName()
                + ":"
                + account.currencyCode();
    return new Leaf(String.valueOf(account.accountId()), label, account.type());
  }

  private Leaf payeeLeaf(Long payeeId) {
    if (payeeId == null) {
      return new Leaf(NO_PAYEE_KEY, NO_PAYEE_LABEL, null);
    }
    String key = payeeId.toString();
    String name = payeeNames.get(key);
    return name == null ? null : new Leaf(key, name, null);
  }
}
