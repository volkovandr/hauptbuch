package volkovandr.hauptbuch.importer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.categories.CategoryResolution;
import volkovandr.hauptbuch.categories.CategoryResolutionService;
import volkovandr.hauptbuch.categories.CategoryService;
import volkovandr.hauptbuch.categories.TagService;
import volkovandr.hauptbuch.importer.repository.ImportCategoryRepository;
import volkovandr.hauptbuch.importer.repository.ImportCategoryTagRepository;

/**
 * The category map (import.md §5.2, §8; plan d1): resolves every Money category path a staged file
 * references to <strong>one</strong> Hauptbuch category (a semantic posting leaf) plus zero or more
 * tags. {@code Audi:Fuel} → category {@code Transportation - Car - Fuel} + tag {@code Cars:Audi}.
 * The map is many-to-one — several Money paths consolidate onto one category, which is the whole
 * point of mapping 20 years of taxonomy drift onto a small curated tree.
 *
 * <p>One action does both: picking a category (or typing a new {@code Parent - Child} one) and the
 * tag pills are submitted together and written together ({@link #mapResolved} / {@link
 * #bulkMapResolved} <em>replace</em> the row's category and its whole tag set). A missing category
 * is created through {@code categories}' own resolver — the same one the register uses — so there
 * is no second "create a category" path (CLAUDE.md §0). Bulk assignment exists because ~300 paths
 * one at a time would stall the campaign.
 *
 * <p>Nothing here writes to {@code transaction} / {@code posting} — the map only records the
 * resolution; f2 books through {@code LedgerService}, routing to the paying account's currency leaf
 * via {@code CurrencyLeafService} (§5.2 — the map never targets a leaf itself). This is the
 * mutation side; {@link ImportCategoryMapPanel} assembles the read model the review renders.
 */
@Service
public class ImportCategoryMapService {

  private static final Logger LOG = LoggerFactory.getLogger(ImportCategoryMapService.class);

  private final ImportSessionService importSessionService;
  private final ImportCategoryRepository importCategoryRepository;
  private final ImportCategoryTagRepository importCategoryTagRepository;
  private final CategoryService categoryService;
  private final CategoryResolutionService categoryResolutionService;
  private final TagService tagService;

  ImportCategoryMapService(
      ImportSessionService importSessionService,
      ImportCategoryRepository importCategoryRepository,
      ImportCategoryTagRepository importCategoryTagRepository,
      CategoryService categoryService,
      CategoryResolutionService categoryResolutionService,
      TagService tagService) {
    this.importSessionService = importSessionService;
    this.importCategoryRepository = importCategoryRepository;
    this.importCategoryTagRepository = importCategoryTagRepository;
    this.categoryService = categoryService;
    this.categoryResolutionService = categoryResolutionService;
    this.tagService = tagService;
  }

  /**
   * Resolve a typed {@code Parent - Child} category path, <strong>creating</strong> the leaf under
   * its existing parent (import.md §5.2) — the "create a new category" field of the map form, which
   * always means create, exactly as the account map's new-account field always opens an account.
   * Delegates to {@code categories}' resolver so the taxonomy rules (leaves-only, subdivision of a
   * posted-to parent) stay in one place.
   *
   * <p>Deliberately <strong>not</strong> {@code @Transactional}: the resolver may create a category
   * in its own transaction and, on a rejected create, returns {@link CategoryResolution.Refused}
   * rather than throwing — sharing a transaction here would turn that into an {@code
   * UnexpectedRollbackException} at commit (see {@link CategoryResolutionService}). The caller
   * inspects the result and only calls {@link #mapResolved} on a {@link
   * CategoryResolution.Resolved}.
   */
  public CategoryResolution resolveNewCategory(String parentChildPath) {
    return categoryResolutionService.resolveCategory(
        parentChildPath, CategoryResolutionService.DECISION_CREATE);
  }

  /**
   * Map one Money path to a resolved category id and replace its tags in one write (import.md §5.2,
   * §8). {@code tagIds} are the chip field's pills — the register's chip field (register §3.6), so
   * a typed {@code Parent:Child} chip resolves or creates the tag on Enter and its id rides here;
   * the set <em>replaces</em> the row's tags (empty clears them).
   *
   * @throws IllegalStateException if no campaign is open
   * @throws IllegalArgumentException if the row is not in the open campaign, or the target is not a
   *     postable category leaf
   */
  @Transactional
  public void mapResolved(long importCategoryId, long accountId, List<Long> tagIds) {
    ImportCategory row = requireRowInOpenSession(importCategoryId);
    requirePostableCategory(accountId);
    write(importCategoryId, accountId, row.moneyPath(), liveTagIds(tagIds));
  }

  /**
   * Map every ticked Money path to one resolved category id and replace each row's tags with one
   * set (import.md §5.2) — the bulk half of the screen. The selection and the target are validated
   * once, not per row.
   *
   * @throws IllegalStateException if no campaign is open
   * @throws IllegalArgumentException if nothing is ticked, a ticked id is not in the campaign, or
   *     the target is not a postable category leaf
   */
  @Transactional
  public void bulkMapResolved(List<Long> importCategoryIds, long accountId, List<Long> tagIds) {
    List<ImportCategory> rows = requireSelection(importCategoryIds);
    requirePostableCategory(accountId);
    Set<Long> live = liveTagIds(tagIds);
    for (ImportCategory row : rows) {
      write(row.importCategoryId(), accountId, row.moneyPath(), live);
    }
    LOG.info("Import: {} category paths bulk-mapped to category {}", rows.size(), accountId);
  }

  private void write(long importCategoryId, long accountId, String moneyPath, Set<Long> tagIds) {
    importCategoryRepository.mapToCategory(importCategoryId, accountId);
    importCategoryTagRepository.clearTags(importCategoryId);
    tagIds.forEach(tagId -> importCategoryTagRepository.addTag(importCategoryId, tagId));
    LOG.info(
        "Import category path \"{}\" mapped to category {} with tags {}",
        moneyPath,
        accountId,
        tagIds);
  }

  private Set<Long> liveTagIds(List<Long> tagIds) {
    Set<Long> live = new LinkedHashSet<>();
    if (tagIds == null) {
      return live;
    }
    for (Long tagId : tagIds) {
      if (tagId != null && tagService.exists(tagId)) {
        live.add(tagId);
      }
    }
    return live;
  }

  private void requirePostableCategory(long accountId) {
    if (!categoryService.isPostableCategory(accountId)) {
      throw new IllegalArgumentException(
          "That is not a category leaf — pick one of its subcategories, or a real category");
    }
  }

  /**
   * The ticked rows, in one pass over the open campaign's map — a bulk op fires this once, not once
   * per id, since its whole reason to exist is not to stall on ~300 paths (import.md §5.2).
   *
   * @throws IllegalStateException if no campaign is open
   * @throws IllegalArgumentException if nothing is ticked, or a ticked id is not in the campaign
   */
  private List<ImportCategory> requireSelection(List<Long> importCategoryIds) {
    Set<Long> wanted =
        new LinkedHashSet<>(importCategoryIds == null ? List.of() : importCategoryIds);
    if (wanted.isEmpty()) {
      throw new IllegalArgumentException("Tick at least one path to bulk-assign");
    }
    Map<Long, ImportCategory> byId = new HashMap<>();
    for (ImportCategory row : openCampaignRows()) {
      byId.put(row.importCategoryId(), row);
    }
    List<ImportCategory> rows = new ArrayList<>();
    for (Long id : wanted) {
      ImportCategory row = byId.get(id);
      if (row == null) {
        throw new IllegalArgumentException("No category-map row " + id + " in the open campaign");
      }
      rows.add(row);
    }
    return rows;
  }

  private ImportCategory requireRowInOpenSession(long importCategoryId) {
    return openCampaignRows().stream()
        .filter(row -> row.importCategoryId() == importCategoryId)
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "No category-map row " + importCategoryId + " in the open campaign"));
  }

  private List<ImportCategory> openCampaignRows() {
    long sessionId =
        importSessionService
            .currentSession()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No import session is open — start one before mapping categories."))
            .importSessionId();
    return importCategoryRepository.findBySession(sessionId);
  }
}
