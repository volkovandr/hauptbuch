package volkovandr.hauptbuch.ledger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.ledger.RegisterRowView.CategoryChip;
import volkovandr.hauptbuch.ledger.RegisterRowView.RegisterCategoryCell;
import volkovandr.hauptbuch.ledger.repository.RegisterRepository;
import volkovandr.hauptbuch.ledger.repository.TagReadRepository;
import volkovandr.hauptbuch.shared.MoneyFactory;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * Turns the register's raw {@link RegisterRow}s into fully-rendered {@link RegisterRowView}s — the
 * presentation half of the register read-side (register §2.6–§2.10), split out from {@link
 * RegisterService} so each class stays cohesive (orchestration vs rendering).
 *
 * <p>It owns the display rules: the summarised Category cell ("biggest wins · +n / → Account"), the
 * same-hue zebra that alternates per account thread rather than per screen row (§2.8), German money
 * formatting (§2.9), and the income / negative-balance / pending flags (§2.8/§2.10). All of it is
 * pure over its inputs, unit-tested with the repository mocked (CLAUDE.md §6).
 */
@Component
class RegisterRowRenderer {

  /**
   * The account types that are your own accounts — a counterpart of one is a transfer, not a
   * category.
   */
  private static final List<String> OWN_ACCOUNT_TYPES = List.of("asset", "liability");

  /** Up to this many counterpart chips are shown in the Category cell; the rest fold into "+n". */
  private static final int MAX_CATEGORY_CHIPS = 3;

  private final RegisterRepository registerRepository;
  private final TagReadRepository tagReadRepository;
  private final PersonService personService;

  RegisterRowRenderer(
      RegisterRepository registerRepository,
      TagReadRepository tagReadRepository,
      PersonService personService) {
    this.registerRepository = registerRepository;
    this.tagReadRepository = tagReadRepository;
    this.personService = personService;
  }

  /**
   * Render the given rows for display: fetch their counterpart legs (for the Category cell), thread
   * the per-account zebra, and format each row.
   *
   * @param rows the raw register rows, in the SQL's {@code (date, txn, posting)} order
   */
  List<RegisterRowView> render(List<RegisterRow> rows) {
    Map<Long, List<RegisterCounterpartLeg>> legsByTxn = legsByTransaction(rows);
    // The tags of the rows' own postings (register §3.6) — one lookup for the whole page.
    Map<Long, List<String>> tagsByPosting =
        tagReadRepository.labelsByPosting(rows.stream().map(RegisterRow::postingId).toList());
    // The person names of every leg on screen — the rows' own legs and their counterparts alike —
    // resolved in one round-trip (register §2.6, plan stage 8c). A person's debt leaf renders its
    // owner's real name, never the cosmetic personal.<CUR>; a leg absent from this map is an
    // ordinary account.
    Map<Long, String> personNames = personNames(rows, legsByTxn);

    Map<Long, Integer> threadCount = new HashMap<>();
    List<RegisterRowView> views = new ArrayList<>(rows.size());
    for (RegisterRow row : rows) {
      // The zebra alternates per account thread, not per screen row (register §2.8): the nth row of
      // a given account gets the dark shade on even n.
      int index = threadCount.merge(row.accountId(), 1, Integer::sum);
      boolean pending = "pending_review".equals(row.lifecycle());
      RegisterCategoryCell cell =
          cellFor(row, legsByTxn.getOrDefault(row.transactionId(), List.of()), personNames);
      views.add(
          toView(
              row,
              cell,
              index % 2 == 0,
              pending,
              tagsByPosting.getOrDefault(row.postingId(), List.of()),
              personNames.get(row.accountId())));
    }
    return views;
  }

  /**
   * The owning person's name for every leg on screen (register §2.6, plan stage 8c): the union of
   * the rows' own account ids and every counterpart leg's account id, resolved through {@code
   * debts} in a single lookup so no row triggers its own query.
   */
  private Map<Long, String> personNames(
      List<RegisterRow> rows, Map<Long, List<RegisterCounterpartLeg>> legsByTxn) {
    Set<Long> accountIds = new LinkedHashSet<>();
    for (RegisterRow row : rows) {
      accountIds.add(row.accountId());
    }
    for (List<RegisterCounterpartLeg> legs : legsByTxn.values()) {
      for (RegisterCounterpartLeg leg : legs) {
        accountIds.add(leg.accountId());
      }
    }
    return personService.personNamesForAccounts(accountIds);
  }

  private RegisterRowView toView(
      RegisterRow row,
      RegisterCategoryCell cell,
      boolean zebraDark,
      boolean pending,
      List<String> tags,
      String personName) {
    String amount =
        MoneyFormat.display(MoneyFactory.of(row.amount(), row.currencyCode()), base(row));
    String balance =
        pending
            ? "—"
            : MoneyFormat.display(
                MoneyFactory.of(row.runningBalance(), row.currencyCode()), base(row));
    // A person's debt leg shows the owner's name with a direction arrow, not the cosmetic leaf name
    // (register §2.6, plan stage 8c). The arrow side is set by the leg's sign in the template.
    boolean accountPerson = personName != null;
    return new RegisterRowView(
        row.postingId(),
        row.transactionId(),
        row.date().toString(),
        accountPerson ? personName : row.accountName(),
        accountPerson,
        row.payeeName(),
        row.accountHue(),
        zebraDark,
        cell,
        amount,
        row.amount().signum() > 0,
        balance,
        !pending && row.runningBalance().signum() < 0,
        pending,
        row.reconciliation(),
        tags);
  }

  /**
   * A base-currency code for the row's own currency-aware display: the row already knows whether it
   * is base ({@link RegisterRow#baseCurrency()}); passing the row's currency as the "base" when it
   * is base renders it bare, and any non-matching code renders it with a symbol (register §2.9).
   */
  private static String base(RegisterRow row) {
    return row.baseCurrency() ? row.currencyCode() : "";
  }

  /**
   * Every transaction's legs, grouped by transaction and keeping the SQL's biggest-magnitude-first
   * order, so each row can pick out <em>its own</em> counterpart legs (register §2.6). Fetching all
   * legs (rather than pre-excluding the viewed accounts) is what lets a transfer between two viewed
   * accounts show the other account in each row's Category cell — the per-row exclusion happens in
   * {@link #cellFor}, so one viewed account's leg is never wrongly dropped from another's row.
   */
  private Map<Long, List<RegisterCounterpartLeg>> legsByTransaction(List<RegisterRow> rows) {
    List<Long> txnIds = rows.stream().map(RegisterRow::transactionId).distinct().toList();
    return registerRepository.findTransactionLegs(txnIds).stream()
        .collect(Collectors.groupingBy(RegisterCounterpartLeg::transactionId));
  }

  /**
   * Summarise a row's counterpart legs into its Category cell (register §2.6): every leg of the
   * transaction <em>other than the row's own</em>, biggest-magnitude first, income/expense legs as
   * the category name and own-account legs as a direction-arrowed transfer target (plan stage
   * 7d.3), capped at {@link #MAX_CATEGORY_CHIPS} with the remainder folded into a {@code · +n}
   * overflow hint.
   */
  private static RegisterCategoryCell cellFor(
      RegisterRow row, List<RegisterCounterpartLeg> legs, Map<Long, String> personNames) {
    List<RegisterCounterpartLeg> counterparts =
        legs.stream().filter(leg -> leg.accountId() != row.accountId()).toList();
    List<CategoryChip> chips = new ArrayList<>();
    for (int i = 0; i < counterparts.size() && i < MAX_CATEGORY_CHIPS; i++) {
      RegisterCounterpartLeg leg = counterparts.get(i);
      chips.add(chipFor(leg, personNames.get(leg.accountId())));
    }
    int overflow = Math.max(0, counterparts.size() - MAX_CATEGORY_CHIPS);
    return new RegisterCategoryCell(List.copyOf(chips), overflow);
  }

  /**
   * One counterpart chip (register §2.6). A person's debt leg (its account id resolves to a {@code
   * personName}) is a person chip — {@code → Name} on a debit, {@code Name →} on a credit. Any
   * other own account is a transfer chip whose arrow follows the leg's flow — a debit (+) received
   * the money ({@code → Account}), a credit (−) sent it ({@code ← Account}). An income/expense leg
   * is a plain category name.
   */
  private static CategoryChip chipFor(RegisterCounterpartLeg leg, String personName) {
    boolean credit = leg.amount().signum() < 0;
    if (personName != null) {
      return new CategoryChip(personName, false, credit, true);
    }
    return new CategoryChip(leg.accountName(), isOwnAccount(leg), credit, false);
  }

  /**
   * An asset/liability counterpart is another of your own accounts → a transfer (arrowed), not a
   * category.
   */
  private static boolean isOwnAccount(RegisterCounterpartLeg leg) {
    return OWN_ACCOUNT_TYPES.contains(leg.accountType());
  }
}
