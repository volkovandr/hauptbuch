package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.operations.SplitEntry;
import volkovandr.hauptbuch.operations.SplitLineDraft;

/**
 * Unit tier (§1.5): the shape Confirm hands to the register's split commit path (plan §9g). Pure
 * over its inputs — no ledger is touched here; what matters is that a receipt draft arrives as the
 * split entry {@code DockSplitService} already knows how to balance, so the receipt path grows no
 * second commit entry-point of its own.
 */
class ReceiptSplitEntriesTest {

  private static final long CASH = 1L;
  private static final long FUEL = 2L;
  private static final long SAVINGS = 3L;
  private static final long PAYEE = 9L;

  @Test
  void headerBecomesTheFundingLegAndTransactionNote() {
    SplitEntry entry = entry(categoryLine("42,14", FUEL));

    assertThat(entry.transactionId()).isNull(); // a re-entry books afresh, never re-threads
    assertThat(entry.date()).isEqualTo("2026-08-03");
    assertThat(entry.accountId()).isEqualTo(CASH);
    assertThat(entry.payeeId()).isEqualTo(PAYEE);
    assertThat(entry.payeeText()).isNull();
    assertThat(entry.note()).isEqualTo("Weekly shop");
  }

  @Test
  void singleCurrencyByConstruction() {
    SplitEntry entry = entry(categoryLine("42,14", FUEL));

    // The gate refuses a header currency other than the paying account's, so the cross-currency
    // fields have nothing to carry (a cross-currency receipt commit is backlogged, plan §14).
    assertThat(entry.spendingCurrencyCode()).isNull();
    assertThat(entry.fundingTotal()).isNull();
    assertThat(entry.baseTotal()).isNull();
    assertThat(entry.tagIds()).isEmpty(); // receipts have no transaction-level tag field
  }

  @Test
  void categoryLineCarriesItsIdAmountNoteAndTags() {
    SplitEntry entry = entry(categoryLine("42,14", FUEL));

    SplitLineDraft line = entry.lines().get(0);
    assertThat(line.categoryId()).isEqualTo(FUEL);
    assertThat(line.amount()).isEqualTo("42,14");
    assertThat(line.note()).isEqualTo("full tank");
    assertThat(line.tagIds()).containsExactly(7L);
    assertThat(line.transferDirection()).isNull();
    assertThat(line.personName()).isNull();
  }

  @Test
  void transferLineKeepsItsDirection() {
    SplitEntry entry =
        entry(
            new WorkingLine(
                "Cashback",
                "To → Savings",
                String.valueOf(SAVINGS),
                "",
                "TO",
                "",
                "",
                "",
                "20,00",
                "",
                "transfer: cash",
                List.of()));

    SplitLineDraft line = entry.lines().get(0);
    assertThat(line.categoryId()).isEqualTo(SAVINGS);
    assertThat(line.transferDirection()).isEqualTo("TO");
  }

  @Test
  void beneficiaryLineCarriesNoIdSoItsLeafIsProvisionedAtCommit() {
    SplitEntry entry =
        entry(
            new WorkingLine(
                "Max's share",
                "for Max",
                "",
                "",
                "",
                "Max",
                "FOR",
                "",
                "10,00",
                "",
                "",
                List.of()));

    SplitLineDraft line = entry.lines().get(0);
    assertThat(line.categoryId()).isNull();
    assertThat(line.personName()).isEqualTo("Max");
    assertThat(line.personDirection()).isEqualTo("FOR");
  }

  @Test
  void skipsBlankLines() {
    SplitEntry entry = entry(categoryLine("42,14", FUEL), WorkingLine.blank(""));

    assertThat(entry.lines()).hasSize(1);
  }

  // ── same-leg merge (issue 15): group by category/transfer/person identity + tags + note ──────

  @Test
  void twoLinesSameCategorySameTagsSameNoteMergeIntoOneSummedPosting() {
    SplitEntry entry = entry(categoryLine("10,00", FUEL), categoryLine("5,50", FUEL));

    assertThat(entry.lines()).hasSize(1);
    SplitLineDraft line = entry.lines().get(0);
    assertThat(line.categoryId()).isEqualTo(FUEL);
    assertThat(line.amount()).isEqualTo("15,50");
    assertThat(line.note()).isEqualTo("full tank");
    assertThat(line.tagIds()).containsExactly(7L);
  }

  @Test
  void linesWithDifferentTagsStaySeparate() {
    WorkingLine first = categoryLine("10,00", FUEL);
    WorkingLine second = withTags(categoryLine("5,50", FUEL), 8L);

    SplitEntry entry = entry(first, second);

    assertThat(entry.lines()).hasSize(2);
  }

  @Test
  void linesWithDifferentNotesStaySeparate() {
    WorkingLine first = categoryLine("10,00", FUEL);
    WorkingLine second = withNote(categoryLine("5,50", FUEL), "second tank");

    SplitEntry entry = entry(first, second);

    assertThat(entry.lines()).hasSize(2);
  }

  @Test
  void lineWithItsOwnNoteNeverMergesEvenIfTheOtherLineHasNone() {
    WorkingLine annotated = withNote(categoryLine("10,00", FUEL), "keep separate");
    WorkingLine plain = withNote(categoryLine("5,50", FUEL), "");

    SplitEntry entry = entry(annotated, plain);

    assertThat(entry.lines()).hasSize(2);
  }

  @Test
  void mergedAmountAbsorbsaStornoLineCorrectly() {
    // A deposit-return line (a storno on an expense category) nets against the other line, not
    // sums magnitudes (register §3.8 mixed-split rule, agent brief acceptance criterion).
    SplitEntry entry = entry(categoryLine("10,00", FUEL), categoryLine("-2,00", FUEL));

    assertThat(entry.lines()).hasSize(1);
    assertThat(entry.lines().get(0).amount()).isEqualTo("8,00");
  }

  @Test
  void repeatedTransferLinesSameDirectionMerge() {
    WorkingLine transfer = transferLine("20,00");

    SplitEntry entry = entry(transfer, transfer);

    assertThat(entry.lines()).hasSize(1);
    assertThat(entry.lines().get(0).amount()).isEqualTo("40,00");
  }

  @Test
  void repeatedPersonLinesSamePersonAndDirectionMerge() {
    WorkingLine forMax = personLine("Max", "FOR", "10,00");

    SplitEntry entry = entry(forMax, forMax);

    assertThat(entry.lines()).hasSize(1);
    SplitLineDraft line = entry.lines().get(0);
    assertThat(line.personName()).isEqualTo("Max");
    assertThat(line.personDirection()).isEqualTo("FOR");
    assertThat(line.amount()).isEqualTo("20,00");
  }

  @Test
  void personLinesWithDifferentDirectionsStaySeparate() {
    SplitEntry entry = entry(personLine("Max", "FOR", "10,00"), personLine("Max", "BY", "3,00"));

    assertThat(entry.lines()).hasSize(2);
  }

  @Test
  void mergedGroupThatNetsToExactlyZeroProducesNoPosting() {
    // A storno that fully cancels its sibling line nets to zero (register §3.8) — booking a
    // zero-amount posting would be pure noise, so the merge drops it (code-review finding).
    SplitEntry entry =
        entry(
            categoryLine("10,00", FUEL),
            categoryLine("-10,00", FUEL),
            categoryLine("3,00", SAVINGS));

    assertThat(entry.lines()).hasSize(1);
    assertThat(entry.lines().get(0).categoryId()).isEqualTo(SAVINGS);
  }

  @Test
  void mergePreservesFirstOccurrenceOrder() {
    SplitEntry entry =
        entry(
            categoryLine("10,00", FUEL), categoryLine("1,00", SAVINGS), categoryLine("5,00", FUEL));

    assertThat(entry.lines()).extracting(SplitLineDraft::categoryId).containsExactly(FUEL, SAVINGS);
    assertThat(entry.lines().get(0).amount()).isEqualTo("15,00");
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private static SplitEntry entry(WorkingLine... lines) {
    ReceiptEditorForm form =
        WorkingLine.toForm(
            "2026-08-03", "Rewe", CASH, "EUR", "42,14", "Weekly shop", "B-42", List.of(lines));
    return ReceiptSplitEntries.of(receiptWithPayee(), form);
  }

  private static WorkingLine categoryLine(String amount, long categoryId) {
    return new WorkingLine(
        "Diesel",
        "Car - Fuel",
        String.valueOf(categoryId),
        "expense",
        "",
        "",
        "",
        "",
        amount,
        "full tank",
        "Fuel",
        List.of(7L));
  }

  private static WorkingLine withTags(WorkingLine line, Long... tagIds) {
    return new WorkingLine(
        line.description(),
        line.categoryText(),
        line.categoryId(),
        line.categoryType(),
        line.transferDirection(),
        line.personName(),
        line.personDirection(),
        line.personRevive(),
        line.amount(),
        line.note(),
        line.aiTargetText(),
        List.of(tagIds));
  }

  private static WorkingLine withNote(WorkingLine line, String note) {
    return new WorkingLine(
        line.description(),
        line.categoryText(),
        line.categoryId(),
        line.categoryType(),
        line.transferDirection(),
        line.personName(),
        line.personDirection(),
        line.personRevive(),
        line.amount(),
        note,
        line.aiTargetText(),
        line.tags());
  }

  private static WorkingLine transferLine(String amount) {
    return new WorkingLine(
        "Cashback",
        "To → Savings",
        String.valueOf(SAVINGS),
        "",
        "TO",
        "",
        "",
        "",
        amount,
        "",
        "transfer: cash",
        List.of());
  }

  private static WorkingLine personLine(String name, String direction, String amount) {
    return new WorkingLine(
        name + "'s share",
        "for " + name,
        "",
        "",
        "",
        name,
        direction,
        "",
        amount,
        "",
        "",
        List.of());
  }

  /** A receipt as Save has just persisted it — only its resolved payee is read here. */
  private static Receipt receiptWithPayee() {
    return new Receipt(
        1L,
        "processed",
        null,
        "pc",
        "orig.jpg",
        "edit.jpg",
        "{}",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        CASH,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        PAYEE,
        null);
  }
}
