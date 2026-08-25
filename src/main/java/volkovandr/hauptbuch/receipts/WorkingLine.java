package volkovandr.hauptbuch.receipts;

import java.util.ArrayList;
import java.util.List;

/**
 * One line of the post-process editor as it round-trips (plan §9f) — the string fields the shared
 * line-editor fragment emits, kept as a cohesive unit so the seeder, the assembler, and the
 * add/remove/redistribute round-trips manipulate a list of these rather than a dozen parallel
 * arrays. Immutable: the {@code with*} builders return copies, and {@link #from}/{@link #toForm}
 * convert to and from the flat {@link ReceiptEditorForm} the request binds.
 *
 * @param description the parsed item name
 * @param categoryText the category/transfer/person picker text
 * @param categoryId the resolved category or transfer-target account id, or blank
 * @param categoryType the resolved category type ({@code income}/{@code expense}), or blank
 * @param transferDirection the resolved transfer direction ({@code TO}/{@code FROM}), or blank
 * @param personName the attributed person's name, or blank
 * @param personDirection the person direction ({@code FOR}/{@code BY}), or blank
 * @param personRevive the Restore/Create-new decision for a soft-deleted person, or blank
 * @param amount the typed amount
 * @param note the line note
 * @param aiTargetText the AI's raw target term kept for provenance, or blank
 * @param tags the resolved leaf tag ids
 */
record WorkingLine(
    String description,
    String categoryText,
    String categoryId,
    String categoryType,
    String transferDirection,
    String personName,
    String personDirection,
    String personRevive,
    String amount,
    String note,
    String aiTargetText,
    List<Long> tags) {

  WorkingLine {
    tags = tags == null ? List.of() : List.copyOf(tags);
  }

  static WorkingLine blank(String amount) {
    return new WorkingLine("", "", "", "", "", "", "", "", amount, "", "", List.of());
  }

  boolean isEmpty() {
    return amount.isBlank()
        && description.isBlank()
        && categoryId.isBlank()
        && personName.isBlank();
  }

  WorkingLine withAmount(String value) {
    return new WorkingLine(
        description,
        categoryText,
        categoryId,
        categoryType,
        transferDirection,
        personName,
        personDirection,
        personRevive,
        value,
        note,
        aiTargetText,
        tags);
  }

  WorkingLine withCategoryText(String value) {
    return new WorkingLine(
        description,
        value,
        categoryId,
        categoryType,
        transferDirection,
        personName,
        personDirection,
        personRevive,
        amount,
        note,
        aiTargetText,
        tags);
  }

  WorkingLine withCategory(String id, String type) {
    return new WorkingLine(
        description, categoryText, id, type, "", "", "", "", amount, note, aiTargetText, tags);
  }

  WorkingLine withTransfer(String id, String direction) {
    return new WorkingLine(
        description, categoryText, id, "", direction, "", "", "", amount, note, aiTargetText, tags);
  }

  WorkingLine withPerson(String name, String direction) {
    return new WorkingLine(
        description,
        categoryText,
        "",
        "",
        "",
        name,
        direction,
        "",
        amount,
        note,
        aiTargetText,
        tags);
  }

  /** Read the working lines out of a bound form (one per line, aligned across the arrays). */
  static List<WorkingLine> from(ReceiptEditorForm form) {
    List<WorkingLine> lines = new ArrayList<>();
    for (int i = 0; i < form.lineCount(); i++) {
      lines.add(lineAt(form, i));
    }
    return lines;
  }

  private static WorkingLine lineAt(ReceiptEditorForm form, int i) {
    return new WorkingLine(
        ReceiptEditorForm.at(form.lineDescription(), i),
        ReceiptEditorForm.at(form.categoryText(), i),
        ReceiptEditorForm.at(form.lineCategoryId(), i),
        ReceiptEditorForm.at(form.lineCategoryType(), i),
        ReceiptEditorForm.at(form.lineTransferDirection(), i),
        ReceiptEditorForm.at(form.linePersonName(), i),
        ReceiptEditorForm.at(form.linePersonDirection(), i),
        ReceiptEditorForm.at(form.linePersonRevive(), i),
        ReceiptEditorForm.at(form.lineAmount(), i),
        ReceiptEditorForm.at(form.lineNote(), i),
        ReceiptEditorForm.at(form.lineAiTargetText(), i),
        form.tagsAt(i));
  }

  /** Re-emit a flat form from the header and a working-line list. */
  static ReceiptEditorForm toForm(ReceiptEditorHeader header, List<WorkingLine> lines) {
    List<String> description = new ArrayList<>();
    List<String> categoryText = new ArrayList<>();
    List<String> categoryId = new ArrayList<>();
    List<String> categoryType = new ArrayList<>();
    List<String> transferDirection = new ArrayList<>();
    List<String> personName = new ArrayList<>();
    List<String> personDirection = new ArrayList<>();
    List<String> personRevive = new ArrayList<>();
    List<String> amount = new ArrayList<>();
    List<String> note = new ArrayList<>();
    List<String> aiTargetText = new ArrayList<>();
    List<List<Long>> tags = new ArrayList<>();
    for (WorkingLine line : lines) {
      description.add(line.description());
      categoryText.add(line.categoryText());
      categoryId.add(line.categoryId());
      categoryType.add(line.categoryType());
      transferDirection.add(line.transferDirection());
      personName.add(line.personName());
      personDirection.add(line.personDirection());
      personRevive.add(line.personRevive());
      amount.add(line.amount());
      note.add(line.note());
      aiTargetText.add(line.aiTargetText());
      tags.add(line.tags());
    }
    return new ReceiptEditorForm(
        header.date(),
        header.payeeText(),
        header.accountId(),
        header.currencyCode(),
        header.total(),
        header.fundingTotal(),
        header.baseTotal(),
        header.note(),
        header.receiptNumber(),
        description,
        categoryText,
        categoryId,
        categoryType,
        transferDirection,
        personName,
        personDirection,
        personRevive,
        amount,
        note,
        aiTargetText,
        tags);
  }
}
