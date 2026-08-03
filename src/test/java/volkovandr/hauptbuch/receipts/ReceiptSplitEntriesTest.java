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
