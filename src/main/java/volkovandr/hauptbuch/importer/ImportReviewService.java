package volkovandr.hauptbuch.importer;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.importer.ImportReview.AccountStatisticsRow;
import volkovandr.hauptbuch.importer.repository.ImportStatisticsRepository;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * Builds the import review render model (import.md §9; plan e′). e′ delivers only the per-account
 * statistics — the verification device the owner ticks against Money's own balances (§9.4); slices
 * c, d and e hang the account map, category map and issues list off the same page.
 *
 * <p>Formatting happens here, not in the template: {@code netSum} is rendered bare in German style
 * (QIF carries no currency, §5.1) and the date span as {@code dd.MM.yyyy}. An empty review — no
 * open campaign, or one with nothing staged — carries no rows.
 */
@Service
public class ImportReviewService {

  private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  private final ImportSessionService importSessionService;
  private final ImportStatisticsRepository importStatisticsRepository;

  ImportReviewService(
      ImportSessionService importSessionService,
      ImportStatisticsRepository importStatisticsRepository) {
    this.importSessionService = importSessionService;
    this.importStatisticsRepository = importStatisticsRepository;
  }

  /**
   * The review of the open campaign, or empty when no campaign is open (the caller redirects to the
   * campaign screen). A campaign with nothing staged yet is a present-but-{@link
   * ImportReview#empty} review, not {@code Optional.empty()}.
   */
  public Optional<ImportReview> review() {
    return importSessionService
        .currentSession()
        .map(session -> importStatisticsRepository.perMoneyAccount(session.importSessionId()))
        .map(ImportReviewService::toReview);
  }

  private static ImportReview toReview(List<ImportAccountStatistics> statistics) {
    return new ImportReview(statistics.stream().map(ImportReviewService::toRow).toList());
  }

  private static AccountStatisticsRow toRow(ImportAccountStatistics statistics) {
    return new AccountStatisticsRow(
        statistics.moneyAccountName(),
        statistics.transactionCount(),
        MoneyFormat.number(statistics.netSum(), 2),
        dateRange(statistics.firstDate(), statistics.lastDate()));
  }

  private static String dateRange(LocalDate first, LocalDate last) {
    if (first == null || last == null) {
      return "—";
    }
    return first.equals(last)
        ? GERMAN_DATE.format(first)
        : GERMAN_DATE.format(first) + " – " + GERMAN_DATE.format(last);
  }
}
