package volkovandr.hauptbuch.analytics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.analytics.repository.NodeKey;
import volkovandr.hauptbuch.analytics.repository.ReportQueryRepository;
import volkovandr.hauptbuch.analytics.repository.TopLevelNode;

/**
 * The row/column candidates of a Report's axis (reporting.md §7.3): every live node a dimension can
 * show, including ones with no activity, so an all-blank row can be suppressed rather than never
 * listed. With a filter on the dimension's own field, its ticked nodes are promoted to the top
 * level instead ({@link PromotedNodes}, reporting issue 08). Split out of {@link
 * ReportDataFetcher}, which fetches the data these candidates are then filled with.
 */
@Component
class AxisCandidates {

  private final ReportQueryRepository queryRepository;

  AxisCandidates(ReportQueryRepository queryRepository) {
    this.queryRepository = queryRepository;
  }

  /**
   * The row/column candidates for a dimension, keyed by their stable key. {@link
   * Dimension#ACCOUNT_TYPE} needs no query — its candidates are exactly the scope's types
   * themselves, capitalized for display. A filter on the dimension's own field promotes its ticked
   * nodes to the top level instead ({@link PromotedNodes}, reporting issue 08): only they under
   * "booked to", they plus every real root under "touching" — {@link #withoutUntouchedRoots} drops
   * the roots the data shows untouched.
   */
  Map<String, TopLevelNode> candidatesFor(Dimension nonDateDim, ReportSpec spec) {
    Scope scope = spec.scope();
    List<String> types = List.copyOf(scope.accountTypes());
    Optional<ReportFilter> ownFilter = PromotedNodes.ownFilter(nonDateDim, spec);
    List<TopLevelNode> nodes =
        ownFilter.isPresent()
            ? promotedCandidates(nonDateDim, spec, ownFilter.get().level(), types)
            : topLevelCandidates(nonDateDim, types, scope);
    return nodes.stream()
        .collect(Collectors.toMap(TopLevelNode::key, n -> n, (a, b) -> a, LinkedHashMap::new));
  }

  private List<TopLevelNode> promotedCandidates(
      Dimension dimension, ReportSpec spec, FilterLevel level, List<String> types) {
    List<Long> ids = PromotedNodes.ids(dimension, spec);
    List<TopLevelNode> nodes = new ArrayList<>();
    if (dimension == Dimension.TAG) {
      nodes.addAll(queryRepository.promotedTagCandidates(ids));
    } else {
      boolean includeClosed = spec.scope().includeClosedAccounts();
      ScopeDimensionMismatch.ownAccountTypes(dimension, types)
          .ifPresent(
              ownTypes -> {
                nodes.addAll(
                    queryRepository.promotedAccountCandidates(ids, ownTypes, includeClosed));
                if (PromotedNodes.promotesPersonalDebts(dimension, spec)) {
                  nodes.addAll(personalDebtsCandidate(ownTypes, includeClosed));
                }
              });
    }
    if (level == FilterLevel.TRANSACTION) {
      nodes.addAll(topLevelCandidates(dimension, types, spec.scope()));
      nodes.sort(Comparator.comparing(TopLevelNode::label, String.CASE_INSENSITIVE_ORDER));
    }
    return nodes;
  }

  /**
   * The "Personal debts" node alone, when any debt leaf is in scope — ticked in the Account filter,
   * it is a promoted top-level node like any other (reporting issues 08, 11).
   */
  private List<TopLevelNode> personalDebtsCandidate(List<String> ownTypes, boolean includeClosed) {
    return queryRepository.topLevelAccounts(ownTypes, includeClosed).stream()
        .filter(n -> NodeKey.PERSONAL_DEBTS.equals(n.key()))
        .toList();
  }

  private List<TopLevelNode> topLevelCandidates(
      Dimension nonDateDim, List<String> types, Scope scope) {
    List<TopLevelNode> nodes;
    if (nonDateDim == Dimension.CATEGORY || nonDateDim == Dimension.ACCOUNT) {
      nodes =
          ScopeDimensionMismatch.ownAccountTypes(nonDateDim, types)
              .map(
                  ownTypes ->
                      queryRepository.topLevelAccounts(ownTypes, scope.includeClosedAccounts()))
              .orElse(List.of());
    } else if (nonDateDim == Dimension.TAG) {
      nodes = queryRepository.topLevelTags();
    } else if (nonDateDim == Dimension.PAYEE) {
      nodes = queryRepository.payeeCandidates();
    } else if (nonDateDim == Dimension.PERSON) {
      nodes = queryRepository.personCandidates();
    } else if (nonDateDim == Dimension.CURRENCY) {
      nodes = queryRepository.currencyCandidates();
    } else if (nonDateDim == Dimension.ACCOUNT_TYPE) {
      nodes = accountTypeCandidates(types);
    } else {
      nodes = List.of();
    }
    return nodes;
  }

  private static List<TopLevelNode> accountTypeCandidates(List<String> types) {
    List<TopLevelNode> nodes = new ArrayList<>();
    for (String type : types) {
      nodes.add(new TopLevelNode(type, capitalize(type), type));
    }
    return nodes;
  }

  private static String capitalize(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  /**
   * The child candidates for one expanded node — {@code innerDim}'s own top-level breakdown (stage
   * e's cross-dimension nesting, §3) or, when there is no second dimension at all, {@code
   * outerDim}'s own direct children (§9.1's same-dimension "expand one node" case, recursing to
   * arbitrary depth — {@code parentKey} may itself be a depth &gt; 0 composite key, see {@link
   * NodeKey#ofLastSegment}). Split out so {@link ReportEngine} can determine each candidate's
   * {@link AxisNode#expandable} flag before deciding what — if anything — to fetch data for. A
   * promoted node (issue 08) is never listed as a child: it is a top-level node of its own. The
   * "Personal debts" node's children are people, and a person's are their debt leaves (issue 06).
   */
  List<TopLevelNode> childCandidatesFor(Dimension outerDim, String parentKey, ReportSpec spec) {
    NodeKey parent = NodeKey.ofLastSegment(parentKey);
    List<Long> promoted = PromotedNodes.ids(outerDim, spec);
    if (outerDim == Dimension.TAG) {
      return queryRepository.childTagCandidates(parent.id(), promoted);
    }
    boolean includeClosed = spec.scope().includeClosedAccounts();
    return switch (parent.kind()) {
      case PERSONAL_DEBTS -> queryRepository.debtPeopleCandidates(includeClosed);
      case PERSON -> queryRepository.debtLeafCandidates(parent.id(), includeClosed);
      case NODE -> queryRepository.childAccountCandidates(parent.id(), includeClosed, promoted);
    };
  }
}
