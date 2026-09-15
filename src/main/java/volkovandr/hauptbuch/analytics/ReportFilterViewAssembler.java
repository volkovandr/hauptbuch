package volkovandr.hauptbuch.analytics;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import volkovandr.hauptbuch.accounts.AccountNode;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.analytics.ReportFilterView.Candidate;
import volkovandr.hauptbuch.analytics.ReportFilterView.HierarchySection;
import volkovandr.hauptbuch.analytics.ReportFilterView.NoteSection;
import volkovandr.hauptbuch.analytics.ReportFilterView.OptionRow;
import volkovandr.hauptbuch.analytics.ReportFilterView.OptionSection;
import volkovandr.hauptbuch.analytics.ReportFilterView.PayeeSection;
import volkovandr.hauptbuch.analytics.ReportFilterView.View;
import volkovandr.hauptbuch.categories.CategoryService;
import volkovandr.hauptbuch.categories.TagNode;
import volkovandr.hauptbuch.categories.TagService;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.ledger.CurrencyService;
import volkovandr.hauptbuch.ledger.PayeeService;

/**
 * Builds the Filters group's own view (reporting.md §11a.5, plan stage d3-4) from an already-
 * resolved {@link ReportSpec} — the DB reads {@link ReportFilterView} itself does not need: the
 * three hierarchy candidate trees ({@code categories}, {@code accounts}, {@code categories}' tags)
 * and the four flat reference lists (payees, persons, currencies, plus the two fixed vocabularies).
 * The node/option row logic itself lives in {@link ReportFilterView}, unit-tested without a
 * container; this class is the thin, DB-touching half, the same split {@link
 * volkovandr.hauptbuch.ledger.RegisterFilterViewAssembler}/{@code RegisterFilterView} already use.
 */
// CouplingBetweenObjects: one section per FilterField, each needing its own reference-data read —
// the same shape ReportSettingsView is already suppressed for (ReportSpec's own vocabulary types),
// now crossing module boundaries to reach that reference data rather than staying in one package.
@SuppressWarnings("PMD.CouplingBetweenObjects")
@Component
class ReportFilterViewAssembler {

  private static final List<String> RECONCILIATION_VALUES =
      List.of("unreconciled", "cleared", "reconciled");

  private final CategoryService categoryService;
  private final AccountService accountService;
  private final TagService tagService;
  private final PayeeService payeeService;
  private final PersonService personService;
  private final CurrencyService currencyService;

  ReportFilterViewAssembler(
      CategoryService categoryService,
      AccountService accountService,
      TagService tagService,
      PayeeService payeeService,
      PersonService personService,
      CurrencyService currencyService) {
    this.categoryService = categoryService;
    this.accountService = accountService;
    this.tagService = tagService;
    this.payeeService = payeeService;
    this.personService = personService;
    this.currencyService = currencyService;
  }

  View build(ReportSpec spec, MultiValueMap<String, String> allParams) {
    List<ReportFilter> filters = spec.filters();
    return new View(
        hierarchySection(
            FilterField.CATEGORY,
            accountCandidates(categoryService.manageableCategories()),
            FilterLevel.POSTING,
            filters,
            allParams),
        hierarchySection(
            FilterField.ACCOUNT,
            accountCandidates(
                accountService.findLiveByTypesWithDepth(
                    ScopeDimensionMismatch.accountTypesFor(Dimension.ACCOUNT))),
            FilterLevel.TRANSACTION,
            filters,
            allParams),
        hierarchySection(
            FilterField.TAG,
            tagCandidates(tagService.findLiveWithDepth()),
            FilterLevel.TRANSACTION,
            filters,
            allParams),
        payeeSection(filters, allParams),
        optionSection(
            FilterField.PERSON,
            "Person",
            personOptions(),
            true,
            FilterLevel.TRANSACTION,
            filters,
            allParams),
        optionSection(
            FilterField.CURRENCY,
            "Currency",
            currencyOptions(),
            true,
            FilterLevel.POSTING,
            filters,
            allParams),
        optionSection(
            FilterField.ACCOUNT_TYPE,
            "Account type",
            accountTypeOptions(),
            false,
            FilterLevel.TRANSACTION,
            filters,
            allParams),
        optionSection(
            FilterField.RECONCILIATION,
            "Reconciliation",
            reconciliationOptions(),
            true,
            FilterLevel.POSTING,
            filters,
            allParams),
        noteSection(filters, allParams));
  }

  private static Optional<ReportFilter> find(List<ReportFilter> filters, FilterField field) {
    return filters.stream().filter(f -> f.field() == field).findFirst();
  }

  private static List<Candidate> accountCandidates(List<AccountNode> nodes) {
    return nodes.stream()
        .filter(n -> !n.account().currencyLeaf())
        .map(
            n ->
                new Candidate(
                    n.account().accountId(), n.account().parentId(), n.account().name(), n.depth()))
        .toList();
  }

  private static List<Candidate> tagCandidates(List<TagNode> nodes) {
    return nodes.stream()
        .map(n -> new Candidate(n.tag().tagId(), n.tag().parentId(), n.tag().name(), n.depth()))
        .toList();
  }

  private HierarchySection hierarchySection(
      FilterField field,
      List<Candidate> candidates,
      FilterLevel defaultLevel,
      List<ReportFilter> filters,
      MultiValueMap<String, String> allParams) {
    Optional<ReportFilter> filter = find(filters, field);
    List<String> tickedValues = filter.map(ReportFilter::values).orElse(List.of());
    FilterLevel level = filter.map(ReportFilter::level).orElse(defaultLevel);
    return new HierarchySection(
        dimensionLikeLabel(field),
        ReportFilterView.nodeRows(candidates, tickedValues),
        level == FilterLevel.TRANSACTION,
        ReportFilterView.withoutFilter(allParams, field));
  }

  private OptionSection optionSection(
      FilterField field,
      String label,
      List<OptionRow> allOptions,
      boolean showLevelSwitch,
      FilterLevel defaultLevel,
      List<ReportFilter> filters,
      MultiValueMap<String, String> allParams) {
    Optional<ReportFilter> filter = find(filters, field);
    List<String> tickedValues = filter.map(ReportFilter::values).orElse(List.of());
    FilterLevel level = filter.map(ReportFilter::level).orElse(defaultLevel);
    List<OptionRow> ticked =
        allOptions.stream()
            .map(o -> new OptionRow(o.value(), o.label(), tickedValues.contains(o.value())))
            .toList();
    return new OptionSection(
        label,
        ticked,
        showLevelSwitch,
        level == FilterLevel.TRANSACTION,
        ReportFilterView.withoutFilter(allParams, field));
  }

  private PayeeSection payeeSection(
      List<ReportFilter> filters, MultiValueMap<String, String> allParams) {
    Optional<ReportFilter> filter = find(filters, FilterField.PAYEE);
    boolean matches = filter.map(f -> f.operator() == FilterOperator.MATCHES).orElse(false);
    List<String> tickedIds =
        matches ? List.of() : filter.map(ReportFilter::values).orElse(List.of());
    String regex = matches ? filter.orElseThrow().values().get(0) : "";
    List<OptionRow> options =
        payeeService.findAllLive().stream()
            .map(
                p ->
                    new OptionRow(
                        String.valueOf(p.payeeId()),
                        p.name(),
                        tickedIds.contains(String.valueOf(p.payeeId()))))
            .toList();
    return new PayeeSection(
        options, matches, regex, ReportFilterView.withoutFilter(allParams, FilterField.PAYEE));
  }

  private NoteSection noteSection(
      List<ReportFilter> filters, MultiValueMap<String, String> allParams) {
    Optional<ReportFilter> filter = find(filters, FilterField.NOTE);
    String value = filter.map(f -> f.values().get(0)).orElse("");
    return new NoteSection(value, ReportFilterView.withoutFilter(allParams, FilterField.NOTE));
  }

  private List<OptionRow> personOptions() {
    return personService.findAllLive().stream()
        .map(p -> new OptionRow(String.valueOf(p.personId()), p.name(), false))
        .toList();
  }

  private List<OptionRow> currencyOptions() {
    return currencyService.findAll().stream()
        .map(c -> new OptionRow(c.code(), c.code() + " — " + c.name(), false))
        .toList();
  }

  private List<OptionRow> accountTypeOptions() {
    return ReportSettingsView.ACCOUNT_TYPES.stream()
        .map(type -> new OptionRow(type, ReportSettingsView.capitalize(type), false))
        .toList();
  }

  private List<OptionRow> reconciliationOptions() {
    return RECONCILIATION_VALUES.stream()
        .map(value -> new OptionRow(value, ReportSettingsView.capitalize(value), false))
        .toList();
  }

  private static String dimensionLikeLabel(FilterField field) {
    return switch (field) {
      case CATEGORY -> "Category";
      case ACCOUNT -> "Account";
      case TAG -> "Tag";
      default -> throw new IllegalArgumentException("Not a hierarchy field: " + field);
    };
  }
}
