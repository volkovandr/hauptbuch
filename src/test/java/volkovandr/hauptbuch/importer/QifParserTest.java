package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (plan §a2): canonical records built from fixture text. The fixtures are synthetic and
 * sanitized, reproducing shapes a real sample export proved (import.md §14 v0.2) without
 * reproducing the source files themselves (personal data, never committed).
 */
class QifParserTest {

  private final QifParser parser = new QifParser();

  @Test
  void proposesAssetForBankHeader() {
    String text =
        """
        !Type:Bank
        D26/11'2011
        T700,290.00
        PBank24.ru
        LOtherIncome:interest
        ^
        """;

    ImportedFile file = parser.parse(text);

    assertThat(file.proposedAccountType()).isEqualTo("asset");
    assertThat(file.transactions()).hasSize(1);
    ImportedTransaction transaction = file.transactions().get(0);
    assertThat(transaction.rawDate()).isEqualTo("26/11'2011");
    assertThat(transaction.payeeText()).isEqualTo("Bank24.ru");
    assertThat(transaction.lines()).hasSize(1);
    ImportedLine line = transaction.lines().get(0);
    assertThat(line.amount()).isEqualByComparingTo(new BigDecimal("700290.00"));
    assertThat(line.target()).isEqualTo(new ImportedTarget.CategoryPath("OtherIncome:interest"));
  }

  @Test
  void proposesLiabilityForCcardHeader() {
    String text =
        """
        !Type:CCard
        D19/07'2016
        T-161.07
        PSome Merchant
        LFood
        ^
        """;

    assertThat(parser.parse(text).proposedAccountType()).isEqualTo("liability");
  }

  @Test
  void proposesAssetForOtherAssetHeader() {
    String text = "!Type:Oth A\nD01/01'2020\nT1.00\nLFood\n^\n";

    assertThat(parser.parse(text).proposedAccountType()).isEqualTo("asset");
  }

  @Test
  void proposesLiabilityForOtherLiabilityHeader() {
    String text = "!Type:Oth L\nD01/01'2020\nT1.00\nLFood\n^\n";

    assertThat(parser.parse(text).proposedAccountType()).isEqualTo("liability");
  }

  @Test
  void rejectsInvestmentAccount() {
    String text = "!Type:Invst\nD01/01'2020\nT1.00\nLFood\n^\n";

    assertThatThrownBy(() -> parser.parse(text))
        .isInstanceOf(QifRejectedException.class)
        .hasMessageContaining("Invst");
  }

  @Test
  void rejectsUnrecognisedHeader() {
    assertThatThrownBy(() -> parser.parse("!Type:Whatever\nD01/01'2020\nT1.00\nLFood\n^\n"))
        .isInstanceOf(QifRejectedException.class);
  }

  @Test
  void parsesSimpleTransferLine() {
    String text =
        """
        !Type:Bank
        D11/03'2014
        CX
        T-599,999.64
        L[Bank24ru-EUR]
        ^
        """;

    ImportedTransaction transaction = parser.parse(text).transactions().get(0);

    assertThat(transaction.clearedStatus()).isEqualTo(ClearedStatus.RECONCILED);
    assertThat(transaction.lines().get(0).target())
        .isEqualTo(new ImportedTarget.AccountReference("Bank24ru-EUR"));
  }

  @Test
  void keepsReferenceNumberSeparateFromMemo() {
    String text =
        """
        !Type:Bank
        D01/01'2020
        T-30.00
        PBank24.ru
        MSome note
        N1234
        LHHold:banking-fees
        ^
        """;

    ImportedTransaction transaction = parser.parse(text).transactions().get(0);

    assertThat(transaction.memo()).isEqualTo("Some note");
    assertThat(transaction.referenceNumber()).isEqualTo("1234");
  }

  @Test
  void ignoresAddressLines() {
    String text =
        """
        !Type:Bank
        D01/01'2020
        T-2.00
        PSome Payee
        AStreet 1
        ACity
        LFood
        ^
        """;

    ImportedTransaction transaction = parser.parse(text).transactions().get(0);

    assertThat(transaction.payeeText()).isEqualTo("Some Payee");
  }

  @Test
  void rejectsSplitRecord() {
    String text =
        """
        !Type:Bank
        D01/01'2020
        T-10.00
        LFood
        SFood
        $-10.00
        ^
        """;

    assertThatThrownBy(() -> parser.parse(text)).isInstanceOf(QifRejectedException.class);
  }

  @Test
  void fallsBackToDuplicateAmountFieldWhenPrimaryIsMissing() {
    String text = "!Type:Bank\nD01/01'2020\nU5.00\nLFood\n^\n";

    assertThat(parser.parse(text).transactions().get(0).lines().get(0).amount())
        .isEqualByComparingTo("5.00");
  }

  @Test
  void rejectsRecordMissingDateField() {
    assertThatThrownBy(() -> parser.parse("!Type:Bank\nT1.00\nLFood\n^\n"))
        .isInstanceOf(QifRejectedException.class);
  }

  @Test
  void rejectsRecordMissingTargetField() {
    assertThatThrownBy(() -> parser.parse("!Type:Bank\nD01/01'2020\nT1.00\n^\n"))
        .isInstanceOf(QifRejectedException.class);
  }

  @Test
  void parsesMultipleTransactionsInFileOrder() {
    String text =
        """
        !Type:Bank
        D01/01'2020
        T-2.00
        PCoffee Shop
        LFood
        ^
        D02/01'2020
        T-599,999.64
        L[Bank24ru-EUR]
        ^
        D03/01'2020
        T707.46
        PSalary Inc
        MMonthly pay
        LOtherIncome
        ^
        """;

    ImportedFile file = parser.parse(text);

    assertThat(file.transactions()).hasSize(3);

    ImportedTransaction first = file.transactions().get(0);
    assertThat(first.rawDate()).isEqualTo("01/01'2020");
    assertThat(first.payeeText()).isEqualTo("Coffee Shop");
    assertThat(first.lines().get(0).amount()).isEqualByComparingTo("-2.00");
    assertThat(first.lines().get(0).target()).isEqualTo(new ImportedTarget.CategoryPath("Food"));

    // The second record carries no P/M field at all — proves nothing leaks over from the first
    // record's payee/memo (each record gets its own fresh RawFields, QifParser.toTransaction).
    ImportedTransaction second = file.transactions().get(1);
    assertThat(second.rawDate()).isEqualTo("02/01'2020");
    assertThat(second.payeeText()).isNull();
    assertThat(second.memo()).isNull();
    assertThat(second.lines().get(0).target())
        .isEqualTo(new ImportedTarget.AccountReference("Bank24ru-EUR"));

    ImportedTransaction third = file.transactions().get(2);
    assertThat(third.rawDate()).isEqualTo("03/01'2020");
    assertThat(third.payeeText()).isEqualTo("Salary Inc");
    assertThat(third.memo()).isEqualTo("Monthly pay");
    assertThat(third.lines().get(0).amount()).isEqualByComparingTo("707.46");
    assertThat(third.lines().get(0).target())
        .isEqualTo(new ImportedTarget.CategoryPath("OtherIncome"));
  }
}
