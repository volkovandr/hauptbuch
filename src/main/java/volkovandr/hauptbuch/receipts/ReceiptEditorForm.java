package volkovandr.hauptbuch.receipts;

import java.util.ArrayList;
import java.util.List;
import org.springframework.util.MultiValueMap;

/**
 * The post-process editor's submitted state (plan §9f) — the header fields plus the aligned
 * per-line arrays the shared line-editor core emits, bound from the raw request on every round-trip
 * (add/remove/redistribute line, Save). The lists bind as {@code String} (not typed) for the same
 * reason {@code SplitForm} does: an unresolved line carries an empty id/type where a typed slot
 * would fail to bind. The form is the single source of truth — everything submitted is re-emitted
 * so resolved ids survive a re-render.
 *
 * <p>Receipts-local rather than reusing {@code operations.SplitForm}: there is no funding person
 * and no register view-filter here, and {@code SplitFormBinder} is module-internal to {@code
 * operations}. Only the per-line field <em>names</em> are shared (that is the fragment contract),
 * so binding here reads the same names the register does plus this surface's own {@code
 * lineAiTargetText} provenance carrier.
 *
 * @param date raw {@code yyyy-MM-dd} booking date (or blank)
 * @param payeeText the payee picker text
 * @param accountId the chosen paying account id, or null
 * @param currencyCode the chosen header currency
 * @param total the editable total as typed, in the receipt's own (spending) currency
 * @param fundingTotal the cross-currency total off the paying account, as typed (issue
 *     receipts/23); blank for a single-currency receipt
 * @param baseTotal the cross-currency base-currency total, as typed (issue receipts/23); blank
 *     unless neither leg is the book's base currency
 * @param note the header note (9g) — persisted on Save, copied to {@code transaction.note} at
 *     Confirm
 * @param receiptNumber the printed receipt/Beleg number (9g) — prefilled from the parse, editable
 * @param lineDescription per-line parsed item name (editable)
 * @param categoryText per-line category/transfer/person text
 * @param lineCategoryId per-line resolved id (category or transfer-target account id)
 * @param lineCategoryType per-line resolved category type ({@code income}/{@code expense}/blank)
 * @param lineTransferDirection per-line transfer direction ({@code TO}/{@code FROM}/blank)
 * @param linePersonName per-line attributed person name (blank unless a person line)
 * @param linePersonDirection per-line person direction ({@code FOR}/{@code BY}/blank)
 * @param linePersonRevive per-line Restore/Create-new decision for a soft-deleted person name
 * @param lineAmount per-line typed amount
 * @param lineNote per-line note
 * @param lineAiTargetText per-line AI provenance term, carried hidden so Save preserves it
 * @param lineTagIds per-line resolved leaf tag ids
 */
public record ReceiptEditorForm(
    String date,
    String payeeText,
    Long accountId,
    String currencyCode,
    String total,
    String fundingTotal,
    String baseTotal,
    String note,
    String receiptNumber,
    List<String> lineDescription,
    List<String> categoryText,
    List<String> lineCategoryId,
    List<String> lineCategoryType,
    List<String> lineTransferDirection,
    List<String> linePersonName,
    List<String> linePersonDirection,
    List<String> linePersonRevive,
    List<String> lineAmount,
    List<String> lineNote,
    List<String> lineAiTargetText,
    List<List<Long>> lineTagIds) {

  /** Defensively copy the mutable list fields to immutable lists (null-safe). */
  public ReceiptEditorForm {
    lineDescription = lineDescription == null ? List.of() : List.copyOf(lineDescription);
    categoryText = categoryText == null ? List.of() : List.copyOf(categoryText);
    lineCategoryId = lineCategoryId == null ? List.of() : List.copyOf(lineCategoryId);
    lineCategoryType = lineCategoryType == null ? List.of() : List.copyOf(lineCategoryType);
    lineTransferDirection =
        lineTransferDirection == null ? List.of() : List.copyOf(lineTransferDirection);
    linePersonName = linePersonName == null ? List.of() : List.copyOf(linePersonName);
    linePersonDirection =
        linePersonDirection == null ? List.of() : List.copyOf(linePersonDirection);
    linePersonRevive = linePersonRevive == null ? List.of() : List.copyOf(linePersonRevive);
    lineAmount = lineAmount == null ? List.of() : List.copyOf(lineAmount);
    lineNote = lineNote == null ? List.of() : List.copyOf(lineNote);
    lineAiTargetText = lineAiTargetText == null ? List.of() : List.copyOf(lineAiTargetText);
    lineTagIds = lineTagIds == null ? List.of() : List.copyOf(lineTagIds);
  }

  /** The number of lines, driven by the amount array (every line emits one). */
  public int lineCount() {
    return lineAmount.size();
  }

  /** The {@code i}-th value of an aligned array, or {@code ""} when the array is short. */
  static String at(List<String> values, int i) {
    return values != null && i >= 0 && i < values.size() ? values.get(i) : "";
  }

  /** The resolved tag ids of line {@code i} (empty when none). */
  List<Long> tagsAt(int i) {
    return i >= 0 && i < lineTagIds.size() ? lineTagIds.get(i) : List.of();
  }

  /**
   * Bind the form from the raw request. Each per-line array is the request's repeated values for
   * that field name (one per rendered line, in line order); a line's tags are the {@code
   * lineTag&lt; index&gt;} values.
   */
  static ReceiptEditorForm bind(MultiValueMap<String, String> params) {
    List<String> amounts = strings(params, "lineAmount");
    int lineCount = amounts.size();
    List<List<Long>> tags = new ArrayList<>();
    for (int i = 0; i < lineCount; i++) {
      tags.add(longs(params, "lineTag" + i));
    }
    return new ReceiptEditorForm(
        params.getFirst("date"),
        params.getFirst("payeeText"),
        ReceiptEditorText.parseId(params.getFirst("accountId")),
        params.getFirst("currencyCode"),
        params.getFirst("total"),
        params.getFirst("fundingTotal"),
        params.getFirst("baseTotal"),
        params.getFirst("note"),
        params.getFirst("receiptNumber"),
        strings(params, "lineDescription"),
        strings(params, "categoryText"),
        strings(params, "lineCategoryId"),
        strings(params, "lineCategoryType"),
        strings(params, "lineTransferDirection"),
        strings(params, "linePersonName"),
        strings(params, "linePersonDirection"),
        strings(params, "linePersonRevive"),
        amounts,
        strings(params, "lineNote"),
        strings(params, "lineAiTargetText"),
        tags);
  }

  private static List<String> strings(MultiValueMap<String, String> params, String name) {
    List<String> values = params.get(name);
    return values == null ? new ArrayList<>() : new ArrayList<>(values);
  }

  private static List<Long> longs(MultiValueMap<String, String> params, String name) {
    List<Long> ids = new ArrayList<>();
    for (String raw : strings(params, name)) {
      Long id = ReceiptEditorText.parseId(raw);
      if (id != null) {
        ids.add(id);
      }
    }
    return ids;
  }
}
