package volkovandr.hauptbuch.ledger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.ledger.RegisterRowView.CategoryChip;
import volkovandr.hauptbuch.ledger.RegisterRowView.RegisterCategoryCell;
import volkovandr.hauptbuch.ledger.repository.RegisterRepository;
import volkovandr.hauptbuch.shared.MoneyFactory;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * Turns the register's raw {@link RegisterRow}s into fully-rendered {@link RegisterRowView}s — the
 * presentation half of the register read-side (register §2.6–§2.10), split out from {@link
 * RegisterService} so each class stays cohesive (orchestration vs rendering).
 *
 * <p>It owns the display rules: the summarised Category cell ("biggest wins · +n / ⇄ Account"), the
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

  RegisterRowRenderer(RegisterRepository registerRepository) {
    this.registerRepository = registerRepository;
  }

  /**
   * Render the given rows for display: fetch their counterpart legs (for the Category cell), thread
   * the per-account zebra, and format each row.
   *
   * @param rows the raw register rows, in the SQL's {@code (date, txn, posting)} order
   * @param viewed the viewed accounts (so the leg lookup can exclude the rows' own legs)
   */
  List<RegisterRowView> render(List<RegisterRow> rows, List<Long> viewed) {
    Map<Long, RegisterCategoryCell> cellsByTxn = categoryCells(rows, viewed);

    Map<Long, Integer> threadCount = new HashMap<>();
    List<RegisterRowView> views = new ArrayList<>(rows.size());
    for (RegisterRow row : rows) {
      // The zebra alternates per account thread, not per screen row (register §2.8): the nth row of
      // a given account gets the dark shade on even n.
      int index = threadCount.merge(row.accountId(), 1, Integer::sum);
      boolean pending = "pending_review".equals(row.lifecycle());
      views.add(toView(row, cellsByTxn, index % 2 == 0, pending));
    }
    return views;
  }

  private RegisterRowView toView(
      RegisterRow row,
      Map<Long, RegisterCategoryCell> cellsByTxn,
      boolean zebraDark,
      boolean pending) {
    String amount =
        MoneyFormat.display(MoneyFactory.of(row.amount(), row.currencyCode()), base(row));
    String balance =
        pending
            ? "—"
            : MoneyFormat.display(
                MoneyFactory.of(row.runningBalance(), row.currencyCode()), base(row));
    return new RegisterRowView(
        row.postingId(),
        row.transactionId(),
        row.date().toString(),
        row.accountName(),
        row.payeeName(),
        row.accountHue(),
        zebraDark,
        cellsByTxn.getOrDefault(row.transactionId(), new RegisterCategoryCell(List.of(), 0)),
        amount,
        row.amount().signum() > 0,
        balance,
        !pending && row.runningBalance().signum() < 0,
        pending,
        row.reconciliation());
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
   * Summarise each transaction's counterpart legs into its Category cell (register §2.6): chips
   * biggest-magnitude first (the SQL already orders them so), income/expense legs as the category
   * name and own-account legs as {@code ⇄ Account} transfers, capped at {@link #MAX_CATEGORY_CHIPS}
   * with the remainder folded into a {@code · +n} overflow hint.
   */
  private Map<Long, RegisterCategoryCell> categoryCells(List<RegisterRow> rows, List<Long> viewed) {
    List<Long> txnIds = rows.stream().map(RegisterRow::transactionId).distinct().toList();
    // Group the legs by transaction (each group keeps the SQL's biggest-first order), then map each
    // group to its summarised cell.
    Map<Long, List<RegisterCounterpartLeg>> legsByTxn =
        registerRepository.findCounterpartLegs(txnIds, viewed).stream()
            .collect(Collectors.groupingBy(RegisterCounterpartLeg::transactionId));
    return legsByTxn.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> toCell(e.getValue())));
  }

  private static RegisterCategoryCell toCell(List<RegisterCounterpartLeg> legs) {
    List<CategoryChip> chips = new ArrayList<>();
    for (int i = 0; i < legs.size() && i < MAX_CATEGORY_CHIPS; i++) {
      RegisterCounterpartLeg leg = legs.get(i);
      chips.add(new CategoryChip(leg.accountName(), isOwnAccount(leg)));
    }
    int overflow = Math.max(0, legs.size() - MAX_CATEGORY_CHIPS);
    return new RegisterCategoryCell(List.copyOf(chips), overflow);
  }

  /**
   * An asset/liability counterpart is another of your own accounts → a transfer (⇄), not a
   * category.
   */
  private static boolean isOwnAccount(RegisterCounterpartLeg leg) {
    return OWN_ACCOUNT_TYPES.contains(leg.accountType());
  }
}
