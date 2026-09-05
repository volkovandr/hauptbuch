package volkovandr.hauptbuch.importer;

import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.accounts.AccountPath;
import volkovandr.hauptbuch.categories.CategoryService;
import volkovandr.hauptbuch.importer.repository.ImportAccountRepository;
import volkovandr.hauptbuch.importer.repository.ImportCategoryRepository;
import volkovandr.hauptbuch.importer.repository.ImportMirrorRepository;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * Assembles the review's issues list and commit-gate state (import.md §9.3; plan e4) — the read
 * model {@link ImportReviewService} folds into the review page. Same render-model-assembler shape
 * as {@link ImportAccountMapPanel} / {@link ImportCategoryMapPanel} / {@link
 * ImportOpeningBalancePanel}: a pure projection, no mutation.
 *
 * <p>Scopes the account and category maps to rows still <strong>referenced</strong> by a live
 * staged row ({@link ImportAccountRepository#findReferencedBySession} / {@link
 * ImportCategoryRepository#findReferencedBySession}) — an orphan row a file removal left behind
 * (plan b3/e4) is neither a real gap nor a real blocker, so it is excluded rather than nagged over.
 * A referenced category row is additionally checked against {@link
 * CategoryService#postableCategoryPaths()}, fetched <strong>once</strong> per session (like {@link
 * ImportCategoryMapPanel} already does, rather than one {@code isPostableCategory} query per
 * referenced row) — a mapped id can stop being a postable leaf if the owner subdivides it
 * mid-campaign, a leaves-only violation that would otherwise only surface at commit ({@code
 * .scratch/import/issues/01}) — and counted alongside the never-mapped rows, marked {@code stale}.
 *
 * <p>The unresolved-park <strong>leg</strong> count is passed in by {@link ImportReviewService}
 * rather than queried here again — it already fetches the same {@code parkedCrossCurrencyLegs} list
 * for the cross-currency panel's own rows, so a second identical query per page render is avoided.
 * It is a leg count, not a transfer count: an unresolved pair with both sightings staged
 * contributes two parked legs.
 */
@Service
class ImportIssuesPanel {

  private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  private final ImportAccountRepository importAccountRepository;
  private final ImportCategoryRepository importCategoryRepository;
  private final ImportMirrorRepository importMirrorRepository;
  private final CategoryService categoryService;

  ImportIssuesPanel(
      ImportAccountRepository importAccountRepository,
      ImportCategoryRepository importCategoryRepository,
      ImportMirrorRepository importMirrorRepository,
      CategoryService categoryService) {
    this.importAccountRepository = importAccountRepository;
    this.importCategoryRepository = importCategoryRepository;
    this.importMirrorRepository = importMirrorRepository;
    this.categoryService = categoryService;
  }

  ImportIssues forSession(long importSessionId, long unresolvedParkLegCount) {
    List<ImportAccount> referencedAccounts =
        importAccountRepository.findReferencedBySession(importSessionId);
    List<ImportIssues.UnmappedRow> unmappedAccounts =
        referencedAccounts.stream()
            .filter(row -> row.accountId() == null)
            .map(row -> new ImportIssues.UnmappedRow(row.importAccountId(), row.moneyAccountName()))
            .toList();
    List<ImportIssues.UnmappedRow> expectingFile =
        referencedAccounts.stream()
            .filter(ImportAccount::expectFile)
            .map(row -> new ImportIssues.UnmappedRow(row.importAccountId(), row.moneyAccountName()))
            .toList();

    // One fetch for the whole session, not one query per row (ImportCategoryMapPanel's pattern).
    Set<Long> postableCategoryIds = new HashSet<>();
    for (AccountPath path : categoryService.postableCategoryPaths()) {
      postableCategoryIds.add(path.accountId());
    }
    List<ImportIssues.CategoryRow> unmappedCategories =
        importCategoryRepository.findReferencedBySession(importSessionId).stream()
            .filter(
                row -> row.accountId() == null || !postableCategoryIds.contains(row.accountId()))
            .map(
                row ->
                    new ImportIssues.CategoryRow(
                        row.importCategoryId(), row.moneyPath(), row.accountId() != null))
            .toList();

    List<ImportIssues.MirrorRow> unresolvedMirrors =
        importMirrorRepository.unresolvedSplitMirrors(importSessionId).stream()
            .map(ImportIssuesPanel::toMirrorRow)
            .toList();

    return new ImportIssues(
        unmappedAccounts,
        expectingFile,
        unmappedCategories,
        unresolvedParkLegCount,
        unresolvedMirrors);
  }

  private static ImportIssues.MirrorRow toMirrorRow(ImportUnresolvedMirror mirror) {
    return new ImportIssues.MirrorRow(
        GERMAN_DATE.format(mirror.date()),
        mirror.moneyAccountName(),
        mirror.mirrorMoneyAccountName(),
        MoneyFormat.number(mirror.amount(), 2));
  }
}
