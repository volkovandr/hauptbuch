package volkovandr.hauptbuch.importer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.accounts.AccountPath;
import volkovandr.hauptbuch.categories.CategoryService;
import volkovandr.hauptbuch.importer.repository.ImportCategoryRepository;
import volkovandr.hauptbuch.importer.repository.ImportCategoryTagRepository;
import volkovandr.hauptbuch.importer.repository.ImportStatisticsRepository;
import volkovandr.hauptbuch.ledger.LedgerService;

/**
 * Assembles the category-map panel of the import review (import.md §5.2; plan d1) — the read model
 * {@link ImportReviewService} folds into the review page. Same render-model-assembler shape as
 * {@link ImportAccountMapPanel} / {@link ImportOpeningBalancePanel}; kept apart from {@link
 * ImportCategoryMapService}, which owns the mutations (mapping a path, replacing a row's tags).
 *
 * <p>A pure projection over {@code import_category}, its tag junction, the per-path sign evidence
 * ({@link ImportStatisticsRepository#perCategoryPath}), and the option lists a row's forms need:
 * the postable category leaves from {@code categories} (currency leaves and groups already
 * excluded, §5.2) and the live tag labels from {@code ledger} (the register's chip-field datalist).
 */
@Service
class ImportCategoryMapPanel {

  private final ImportCategoryRepository importCategoryRepository;
  private final ImportCategoryTagRepository importCategoryTagRepository;
  private final ImportStatisticsRepository importStatisticsRepository;
  private final CategoryService categoryService;
  private final LedgerService ledgerService;

  ImportCategoryMapPanel(
      ImportCategoryRepository importCategoryRepository,
      ImportCategoryTagRepository importCategoryTagRepository,
      ImportStatisticsRepository importStatisticsRepository,
      CategoryService categoryService,
      LedgerService ledgerService) {
    this.importCategoryRepository = importCategoryRepository;
    this.importCategoryTagRepository = importCategoryTagRepository;
    this.importStatisticsRepository = importStatisticsRepository;
    this.categoryService = categoryService;
    this.ledgerService = ledgerService;
  }

  ImportCategoryMap forSession(long importSessionId) {
    Map<String, ImportCategorySignEvidence> evidenceByPath = new HashMap<>();
    for (ImportCategorySignEvidence evidence :
        importStatisticsRepository.perCategoryPath(importSessionId)) {
      evidenceByPath.put(evidence.moneyPath(), evidence);
    }

    List<AccountPath> categoryPaths = categoryService.postableCategoryPaths();
    Map<Long, String> pathByAccountId = new HashMap<>();
    for (AccountPath path : categoryPaths) {
      pathByAccountId.put(path.accountId(), path.path());
    }

    Map<Long, List<Long>> tagIdsByRow =
        importCategoryTagRepository.tagIdsBySession(importSessionId);
    List<Long> allTagIds = new ArrayList<>();
    tagIdsByRow.values().forEach(allTagIds::addAll);
    Map<Long, String> tagLabels = ledgerService.labelsForTagIds(allTagIds);

    List<ImportCategoryMap.Row> rows =
        importCategoryRepository.findBySession(importSessionId).stream()
            .map(
                row ->
                    row(
                        row,
                        evidenceByPath.get(row.moneyPath()),
                        pathByAccountId,
                        tagIdsByRow.getOrDefault(row.importCategoryId(), List.of()),
                        tagLabels))
            .toList();

    List<ImportCategoryMap.CategoryOption> options =
        categoryPaths.stream()
            .map(p -> new ImportCategoryMap.CategoryOption(p.accountId(), p.path()))
            .toList();

    return new ImportCategoryMap(rows, options, ledgerService.liveTagLabels());
  }

  private static ImportCategoryMap.Row row(
      ImportCategory row,
      ImportCategorySignEvidence evidence,
      Map<Long, String> pathByAccountId,
      List<Long> tagIds,
      Map<Long, String> tagLabels) {
    long debits = evidence == null ? 0 : evidence.debitLineCount();
    long credits = evidence == null ? 0 : evidence.creditLineCount();
    List<ImportCategoryMap.Tag> tags = new ArrayList<>();
    for (Long tagId : tagIds) {
      String label = tagLabels.get(tagId);
      if (label != null) {
        tags.add(new ImportCategoryMap.Tag(tagId, label));
      }
    }
    return new ImportCategoryMap.Row(
        row.importCategoryId(),
        row.moneyPath(),
        row.accountId(),
        row.accountId() == null ? null : pathByAccountId.get(row.accountId()),
        debits,
        credits,
        proposedType(debits, credits),
        tags);
  }

  /**
   * The type the sign evidence suggests: {@code expense} when the path's staged lines are mostly
   * spends (a refund on an expense category is an ordinary negative line, so the majority sign
   * wins), {@code income} when mostly receipts, null when the path has no staged line yet (an
   * orphan map row from a removed file, §5). The raw counts are always shown beside the row, so an
   * evenly-split path is legible even though the hint calls it an expense.
   */
  private static String proposedType(long debits, long credits) {
    if (debits == 0 && credits == 0) {
      return null;
    }
    return debits >= credits ? "expense" : "income";
  }
}
