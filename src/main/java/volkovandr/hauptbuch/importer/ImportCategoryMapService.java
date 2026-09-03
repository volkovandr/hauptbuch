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
 * <p>Bulk assignment ({@link #bulkMapToCategory}, {@link #bulkAddTag}) exists because ~300 paths
 * one at a time would stall the campaign. Nothing here writes to {@code transaction} / {@code
 * posting} — the map only records the resolution; f2 books through {@code LedgerService}, routing
 * to the paying account's currency leaf via {@code CurrencyLeafService} (§5.2 — the map never
 * targets a leaf itself).
 *
 * <p>This is the mutation side; {@link ImportCategoryMapPanel} assembles the read model the review
 * renders (the {@link ImportAccountMapService} / {@link ImportAccountMapPanel} split).
 */
@Service
public class ImportCategoryMapService {

  private static final Logger LOG = LoggerFactory.getLogger(ImportCategoryMapService.class);

  private final ImportSessionService importSessionService;
  private final ImportCategoryRepository importCategoryRepository;
  private final ImportCategoryTagRepository importCategoryTagRepository;
  private final CategoryService categoryService;
  private final TagService tagService;

  ImportCategoryMapService(
      ImportSessionService importSessionService,
      ImportCategoryRepository importCategoryRepository,
      ImportCategoryTagRepository importCategoryTagRepository,
      CategoryService categoryService,
      TagService tagService) {
    this.importSessionService = importSessionService;
    this.importCategoryRepository = importCategoryRepository;
    this.importCategoryTagRepository = importCategoryTagRepository;
    this.categoryService = categoryService;
    this.tagService = tagService;
  }

  /**
   * Map one Money path to a Hauptbuch category (import.md §5.2). The target must be a postable
   * category leaf — {@code categories} refuses a group or an auto-managed currency leaf, so a Money
   * path can never land on one.
   *
   * @throws IllegalStateException if no campaign is open
   * @throws IllegalArgumentException if the row is not in the open campaign, or the target is not a
   *     postable category leaf
   */
  @Transactional
  public void mapToCategory(long importCategoryId, long accountId) {
    ImportCategory row = requireRowInOpenSession(importCategoryId);
    requirePostableCategory(accountId, row.moneyPath());
    importCategoryRepository.mapToCategory(importCategoryId, accountId);
    LOG.info("Import category path \"{}\" mapped to category {}", row.moneyPath(), accountId);
  }

  /**
   * Replace one map row's tags with the pills its field currently shows (import.md §5.2, §8). The
   * field is the register's chip field (register §3.6): typing a {@code Parent:Child} chip and
   * pressing Enter resolves — or creates, this being the taxonomy-consolidation moment — the tag
   * through {@code categories}' {@link TagService#resolveChip} and appends a pill carrying its id;
   * "Save tags" then submits that id set, which this rewrites the row's junction rows to. An empty
   * set clears the row's tags. Unknown or soft-deleted ids are dropped rather than rejected — the
   * pill set is machine-produced, not typed.
   *
   * @throws IllegalStateException if no campaign is open
   * @throws IllegalArgumentException if the row is not in the open campaign
   */
  @Transactional
  public void setTags(long importCategoryId, List<Long> tagIds) {
    ImportCategory row = requireRowInOpenSession(importCategoryId);
    Set<Long> live = liveTagIds(tagIds);
    importCategoryTagRepository.clearTags(importCategoryId);
    live.forEach(tagId -> importCategoryTagRepository.addTag(importCategoryId, tagId));
    LOG.debug("Import category path \"{}\" tags set to {}", row.moneyPath(), live);
  }

  /**
   * Map every selected Money path to one category (import.md §5.2) — the bulk half of the screen.
   * Each row is validated as being in the open campaign; the target is validated once.
   *
   * @throws IllegalStateException if no campaign is open
   * @throws IllegalArgumentException if any row is not in the open campaign, or the target is not a
   *     postable category leaf
   */
  @Transactional
  public void bulkMapToCategory(List<Long> importCategoryIds, long accountId) {
    List<ImportCategory> rows = requireSelection(importCategoryIds);
    requirePostableCategory(accountId, rows.get(0).moneyPath());
    rows.forEach(row -> importCategoryRepository.mapToCategory(row.importCategoryId(), accountId));
    LOG.info("Import: {} category paths bulk-mapped to category {}", rows.size(), accountId);
  }

  /**
   * Add one tag to every selected Money path (import.md §5.2, §8) — {@code Audi:Fuel}, {@code
   * Audi:Insurance}, {@code Audi:Repair} all want the same {@code Cars:Audi}. The chip is resolved
   * (or created) once and <em>added</em> to each row — it does not replace the rows' other tags.
   *
   * @throws IllegalStateException if no campaign is open
   * @throws IllegalArgumentException if any row is not in the open campaign
   */
  @Transactional
  public void bulkAddTag(List<Long> importCategoryIds, String chip) {
    List<ImportCategory> rows = requireSelection(importCategoryIds);
    long tagId =
        tagService
            .resolveChip(chip)
            .orElseThrow(() -> new IllegalArgumentException("Type a tag as Parent:Child"))
            .tagId();
    rows.forEach(row -> importCategoryTagRepository.addTag(row.importCategoryId(), tagId));
    LOG.info("Import: tag {} added to {} category paths", tagId, rows.size());
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

  private void requirePostableCategory(long accountId, String moneyPath) {
    if (!categoryService.isPostableCategory(accountId)) {
      throw new IllegalArgumentException(
          "\""
              + moneyPath
              + "\" must map to a category leaf — not a group or an auto-managed currency leaf");
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
