package volkovandr.hauptbuch.importer;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.importer.ImportReview.AccountStatisticsRow;
import volkovandr.hauptbuch.importer.ImportReview.CrossCurrencyParkRow;
import volkovandr.hauptbuch.importer.repository.ImportStatisticsRepository;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * Builds the import review render model (import.md §9). e′ delivers the per-account statistics —
 * the verification device the owner ticks against Money's own balances (§9.4); c1 adds the account
 * map (§5.1) and c3 the opening-balance reconciliation cells beside it ({@link
 * ImportOpeningBalancePanel}); d1 the category map and d2 the payee summary (§5.3); e2b adds the
 * cross-currency panel ({@link ImportCrossCurrencyParkService}). The full issues list hangs off the
 * page in e4.
 *
 * <p>Formatting happens here, not in the template: {@code netSum} is rendered bare in German style
 * (QIF carries no currency, §5.1) and the date span as {@code dd.MM.yyyy}. An empty review — no
 * open campaign, or one with nothing staged — carries no statistics rows.
 */
@Service
public class ImportReviewService {

  private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  private final ImportSessionService importSessionService;
  private final ImportStatisticsRepository importStatisticsRepository;
  private final ImportAccountMapPanel importAccountMapPanel;
  private final ImportOpeningBalancePanel importOpeningBalancePanel;
  private final ImportCategoryMapPanel importCategoryMapPanel;
  private final ImportCrossCurrencyParkService importCrossCurrencyParkService;

  ImportReviewService(
      ImportSessionService importSessionService,
      ImportStatisticsRepository importStatisticsRepository,
      ImportAccountMapPanel importAccountMapPanel,
      ImportOpeningBalancePanel importOpeningBalancePanel,
      ImportCategoryMapPanel importCategoryMapPanel,
      ImportCrossCurrencyParkService importCrossCurrencyParkService) {
    this.importSessionService = importSessionService;
    this.importStatisticsRepository = importStatisticsRepository;
    this.importAccountMapPanel = importAccountMapPanel;
    this.importOpeningBalancePanel = importOpeningBalancePanel;
    this.importCategoryMapPanel = importCategoryMapPanel;
    this.importCrossCurrencyParkService = importCrossCurrencyParkService;
  }

  /**
   * The review of the open campaign, or empty when no campaign is open (the caller redirects to the
   * campaign screen). A campaign with nothing staged yet is a present-but-{@link
   * ImportReview#empty} review, not {@code Optional.empty()}.
   */
  public Optional<ImportReview> review() {
    return importSessionService
        .currentSession()
        .map(
            session ->
                new ImportReview(
                    importStatisticsRepository.perMoneyAccount(session.importSessionId()).stream()
                        .map(ImportReviewService::toRow)
                        .toList(),
                    importAccountMapPanel.forSession(session.importSessionId()),
                    importOpeningBalancePanel.forSession(session.importSessionId()),
                    importCategoryMapPanel.forSession(session.importSessionId()),
                    importStatisticsRepository.payeeResolution(session.importSessionId()),
                    importCrossCurrencyParkService
                        .parksForSession(session.importSessionId())
                        .stream()
                        .map(ImportReviewService::toRow)
                        .toList()));
  }

  private static AccountStatisticsRow toRow(ImportAccountStatistics statistics) {
    return new AccountStatisticsRow(
        statistics.moneyAccountName(),
        statistics.transactionCount(),
        MoneyFormat.number(statistics.netSum(), 2),
        dateRange(statistics.firstDate(), statistics.lastDate()));
  }

  private static CrossCurrencyParkRow toRow(ImportCrossCurrencyPark park) {
    return new CrossCurrencyParkRow(
        park.importPostingId(),
        GERMAN_DATE.format(park.date()),
        park.nearMoneyAccountName(),
        park.farMoneyAccountName(),
        MoneyFormat.number(park.amount(), 2),
        park.farExpectFile());
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
