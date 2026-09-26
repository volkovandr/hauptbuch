package volkovandr.hauptbuch.analytics;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import volkovandr.hauptbuch.ledger.RegisterRowView;

/**
 * Turns a {@link DrillDown} into its page's {@link DrillDownView}: each listed posting becomes the
 * register's own row for it (reporting.md §12 — visually identical to the register), with the
 * running figure in the Balance column.
 */
final class DrillDownViewAssembler {

  private DrillDownViewAssembler() {}

  /**
   * {@code drill} as its page's view.
   *
   * @param registerRows the register's rendered rows for the drill-down's postings, in any order
   */
  static DrillDownView assemble(
      DrillDown drill, List<RegisterRowView> registerRows, String baseCurrency, String backUrl) {
    Map<Long, RegisterRowView> byPosting =
        registerRows.stream()
            .collect(Collectors.toMap(RegisterRowView::postingId, Function.identity()));
    List<RegisterRowView> rows = new ArrayList<>(drill.rows().size());
    for (DrillDown.Row row : drill.rows()) {
      RegisterRowView registerRow = byPosting.get(row.posting().postingId());
      Cell running = row.running();
      rows.add(
          registerRow.withBalance(
              ReportTableViewAssembler.format(running, baseCurrency).text(), isNegative(running)));
    }
    return new DrillDownView(
        drill.rowLabel() + " · " + drill.columnLabel(),
        ReportGridBuilder.measureLabel(drill.measure()),
        ReportTableViewAssembler.format(drill.cell(), baseCurrency),
        rows,
        backUrl);
  }

  private static boolean isNegative(Cell running) {
    return running instanceof Cell.Value value && value.amount().compareTo(BigDecimal.ZERO) < 0;
  }
}
