package volkovandr.hauptbuch.analytics;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.analytics.repository.PostingValue;
import volkovandr.hauptbuch.analytics.repository.PostingValueRepository;

/**
 * A Report figure's drill-down (reporting.md §12): the postings behind one cell or total, with a
 * running column that ends on it.
 *
 * <p>The posting set is not re-derived: the engine re-renders the Report with every turnover group
 * carrying the ids of the postings it summed ({@link ReportEngine#drillSource}), and the figure's
 * list is the ids of exactly the groups {@link CellValuation} matched for it. A total's list is the
 * union of the cells it sums — its body cells along the row or down the column, top-level only, as
 * {@link ReportGridBuilder} sums them. So the list is the figure's postings by construction, across
 * every dimension, filter, promoted node and nesting the engine knows.
 *
 * <p>Closing-balance figures are not drilled yet (plan slice f, work package f2): their list is
 * empty.
 */
@Service
class ReportDrillDown {

  private final ReportEngine engine;
  private final PostingValueRepository postingValueRepository;

  ReportDrillDown(ReportEngine engine, PostingValueRepository postingValueRepository) {
    this.engine = engine;
    this.postingValueRepository = postingValueRepository;
  }

  /** {@code address}'s drill-down in {@code spec}, rendered as of today. */
  DrillDown drill(ReportSpec spec, Set<String> expandedKeys, CellAddress address) {
    return drill(spec, expandedKeys, address, LocalDate.now());
  }

  /**
   * {@link #drill(ReportSpec, Set, CellAddress)} with an injectable "today".
   *
   * @param expandedKeys the expansion the Report was shown with — {@code null} for {@code auto}
   *     (§9.2), exactly as the page rendered it
   */
  DrillDown drill(ReportSpec spec, Set<String> expandedKeys, CellAddress address, LocalDate today) {
    DrillSource source = engine.drillSource(spec, today, expandedKeys);
    ReportGrid grid = source.grid();
    Measure measure = spec.measures().get(address.measureIndex());
    Cell figure = address.figureOn(spec, grid);
    List<DrillDown.Row> rows =
        source.cellContext() != null && DrillDown.isDrillable(figure, measure)
            ? listRows(source, address, measure)
            : List.of();
    return new DrillDown(
        address.rowLabelOn(grid), address.columnLabelOn(spec, grid), measure, figure, rows);
  }

  private List<DrillDown.Row> listRows(DrillSource source, CellAddress address, Measure measure) {
    String baseCurrency = source.cellContext().baseCurrency();
    Map<Long, List<DrillSource.Membership>> membershipsByPosting =
        source.postingsBehind(address, measure);
    List<PostingValue> values =
        postingValueRepository.postingValues(
            List.copyOf(membershipsByPosting.keySet()), baseCurrency);
    // A posting in two of a total's cells is listed once per cell, as the total counts it.
    List<RunningColumn.Entry> entries =
        values.stream()
            .flatMap(
                value ->
                    membershipsByPosting.get(value.postingId()).stream()
                        .map(m -> new RunningColumn.Entry(value, m.creditNatural(), m.addend())))
            .toList();
    List<Cell> running = RunningColumn.of(measure, baseCurrency, entries);
    return IntStream.range(0, entries.size())
        .mapToObj(i -> new DrillDown.Row(entries.get(i).posting(), running.get(i)))
        .toList();
  }
}
