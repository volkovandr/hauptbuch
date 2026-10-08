package volkovandr.hauptbuch.statements;

import java.util.List;
import java.util.Map;
import volkovandr.hauptbuch.ledger.TransactionTag;
import volkovandr.hauptbuch.operations.DockEditModel;
import volkovandr.hauptbuch.operations.DockEntry;

/**
 * The translations between the statement dock's fields ({@link DockInput}), the register dock's
 * model of a booked transaction ({@link DockEditModel}) and the commit path's {@link DockEntry}, so
 * the line dock and the extras dock build them the same way.
 */
final class DockForms {

  private DockForms() {}

  /** The dock's fields as a booked transaction would fill the register's dock. */
  static DockInput inputOf(DockEditModel model) {
    return new DockInput(
        model.date(),
        orEmpty(model.payeeText()),
        model.categoryId(),
        orEmpty(model.categoryEntryText()),
        model.transferDirection(),
        null,
        null,
        null,
        orEmpty(model.note()),
        model.tags().stream().map(TransactionTag::tagId).toList(),
        model.amount(),
        model.categoryCurrencyCode(),
        model.categoryAmount(),
        model.baseAmount());
  }

  /**
   * The commit path's entry for editing a booked transaction from the dock's fields, with its
   * funding leg on {@code accountId}.
   */
  static DockEntry editEntry(
      DockInput input, long transactionId, long accountId, String amountText) {
    return DockEntry.editOnAccount(
            transactionId,
            input.date(),
            accountId,
            input.payeeText(),
            input.categoryId() == null ? 0 : input.categoryId(),
            amountText,
            input.note())
        .withCrossCurrency(input.categoryCurrencyCode(), input.categoryAmount(), input.baseAmount())
        .withTransfer(input.transferDirection())
        .withPerson(input.personName(), input.personDirection(), input.personRevive())
        .withTags(input.tagId());
  }

  /** Whether the dock names no category, transfer target or person to book against. */
  static boolean noCounterpart(DockInput input) {
    boolean person = notBlank(input.personName()) && notBlank(input.personDirection());
    return input.categoryId() == null && !person;
  }

  /** The chips for the given tag ids, labelled from {@code labels}. */
  static List<TransactionTag> chips(List<Long> tagIds, Map<Long, String> labels) {
    return tagIds.stream().map(id -> new TransactionTag(id, labels.get(id))).toList();
  }

  static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  /** The bank's counterparty and description as one line. */
  static String bankText(StatementLine line) {
    return (orEmpty(line.counterparty()) + " " + orEmpty(line.description())).strip();
  }
}
