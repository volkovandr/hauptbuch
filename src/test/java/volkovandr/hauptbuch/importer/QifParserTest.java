package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (plan §a2–§a4): canonical records built from fixture text. The fixtures are synthetic
 * and sanitized, reproducing shapes a real sample export proved (import.md §14 v0.2) without
 * reproducing the source files themselves (personal data, never committed).
 */
class QifParserTest {

  private final QifParser parser = new QifParser();

  /**
   * The file itself never names its account (§4.1) — the tests state one the way the owner does.
   */
  private ImportedFile parse(String text) {
    return parser.parse("Test Account", text);
  }

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

    ImportedFile file = parse(text);

    assertThat(file.proposedAccountType()).isEqualTo("asset");
    assertThat(file.transactions()).hasSize(1);
    ImportedTransaction transaction = file.transactions().get(0);
    assertThat(transaction.rawDate()).isEqualTo("26/11'2011");
    assertThat(transaction.payeeText()).isEqualTo("Bank24.ru");
    assertThat(transaction.openingBalance()).isFalse();
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

    assertThat(parse(text).proposedAccountType()).isEqualTo("liability");
  }

  @Test
  void proposesAssetForOtherAssetHeader() {
    String text = "!Type:Oth A\nD01/01'2020\nT1.00\nLFood\n^\n";

    assertThat(parse(text).proposedAccountType()).isEqualTo("asset");
  }

  @Test
  void proposesLiabilityForOtherLiabilityHeader() {
    String text = "!Type:Oth L\nD01/01'2020\nT1.00\nLFood\n^\n";

    assertThat(parse(text).proposedAccountType()).isEqualTo("liability");
  }

  @Test
  void rejectsInvestmentAccount() {
    String text = "!Type:Invst\nD01/01'2020\nT1.00\nLFood\n^\n";

    assertThatThrownBy(() -> parse(text))
        .isInstanceOf(QifRejectedException.class)
        .hasMessageContaining("Invst");
  }

  @Test
  void rejectsUnrecognisedHeader() {
    assertThatThrownBy(() -> parse("!Type:Whatever\nD01/01'2020\nT1.00\nLFood\n^\n"))
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

    ImportedTransaction transaction = parse(text).transactions().get(0);

    assertThat(transaction.clearedStatus()).isEqualTo(ClearedStatus.RECONCILED);
    assertThat(transaction.openingBalance()).isFalse();
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

    ImportedTransaction transaction = parse(text).transactions().get(0);

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

    ImportedTransaction transaction = parse(text).transactions().get(0);

    assertThat(transaction.payeeText()).isEqualTo("Some Payee");
  }

  @Test
  void fallsBackToDuplicateAmountFieldWhenPrimaryIsMissing() {
    String text = "!Type:Bank\nD01/01'2020\nU5.00\nLFood\n^\n";

    assertThat(parse(text).transactions().get(0).lines().get(0).amount())
        .isEqualByComparingTo("5.00");
  }

  @Test
  void rejectsRecordMissingDateField() {
    assertThatThrownBy(() -> parse("!Type:Bank\nT1.00\nLFood\n^\n"))
        .isInstanceOf(QifRejectedException.class);
  }

  @Test
  void rejectsRecordMissingTargetField() {
    assertThatThrownBy(() -> parse("!Type:Bank\nD01/01'2020\nT1.00\n^\n"))
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

    ImportedFile file = parse(text);

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

  // ---------------------------------------------------------------------------
  // a4 — splits, transfers, opening balances, destroyed payees & accounts
  // ---------------------------------------------------------------------------

  @Test
  void rejectsBlankMoneyAccountNameButAllowsNullWhenNotYetKnown() {
    assertThatThrownBy(() -> parser.parse("  ", "!Type:Bank\nD01/01'2020\nT1.00\nLFood\n^\n"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(parser.parse(null, "!Type:Bank\nD01/01'2020\nT1.00\nLFood\n^\n").transactions())
        .hasSize(1);
  }

  @Test
  void detectsTheAccountNameFromTheOpeningBalanceSelfTransfer() {
    String text =
        """
        !Type:Bank
        D25/12'2013
        T0.00
        CX
        POpening Balance
        L[Bank24ru-EUR]
        ^
        D28/07'2014
        T-5.00
        PBaker
        LFood
        ^
        """;

    assertThat(parser.detectAccountName(text)).contains("Bank24ru-EUR");
    // the deduced name also lands in the referenced set even when none was passed in
    assertThat(parser.parse(null, text).referencedAccountNames()).contains("Bank24ru-EUR");
  }

  @Test
  void detectsNoAccountNameWhenTheFileHasNoOpeningBalanceRecord() {
    assertThat(parser.detectAccountName("!Type:Bank\nD01/01'2020\nT-5.00\nLFood\n^\n")).isEmpty();
  }

  @Test
  void flagsTheOpeningBalanceByItsPayeeMarkerWithoutBeingToldTheAccountName() {
    String text = "!Type:CCard\nD19/07'2016\nT0.00\nCX\nPOpening Balance\nL[Advanzia-MC]\n^\n";

    assertThat(parser.parse(null, text).transactions().get(0).openingBalance()).isTrue();
  }

  @Test
  void parsesSplitIntoOneLinePerLeg() {
    // The header L simply repeats the first S line (import.md §7, Q-IMP-1) — it is not a fourth
    // leg.
    String text =
        """
        !Type:CCard
        D19/07'2016
        T-161.07
        PCitygoRentals
        LVacation:car-rental
        SVacation:car-rental
        $-115.05
        SVacation:entertainment
        $-46.02
        ^
        """;

    ImportedTransaction transaction = parse(text).transactions().get(0);

    assertThat(transaction.payeeText()).isEqualTo("CitygoRentals");
    assertThat(transaction.lines())
        .extracting(line -> line.amount().toPlainString(), ImportedLine::target)
        .containsExactly(
            tuple("-115.05", new ImportedTarget.CategoryPath("Vacation:car-rental")),
            tuple("-46.02", new ImportedTarget.CategoryPath("Vacation:entertainment")));
  }

  @Test
  void putsSplitLineMemoOnTheLine() {
    String text =
        """
        !Type:Bank
        D01/01'2020
        T-10.00
        LFood
        SFood
        Elunch with a client
        $-7.00
        STransport
        Ebus fare
        $-3.00
        ^
        """;

    ImportedTransaction transaction = parse(text).transactions().get(0);

    assertThat(transaction.lines())
        .extracting(ImportedLine::memo)
        .containsExactly("lunch with a client", "bus fare");
  }

  @Test
  void rejectsSplitWhoseLinesDoNotSumToTheTotal() {
    String text =
        """
        !Type:Bank
        D01/01'2020
        T-10.00
        LFood
        SFood
        $-7.00
        STransport
        $-2.00
        ^
        """;

    assertThatThrownBy(() -> parse(text))
        .isInstanceOf(QifRejectedException.class)
        .hasMessageContaining("-9")
        .hasMessageContaining("-10");
  }

  @Test
  void rejectsSplitLegWithNoAmount() {
    String text =
        """
        !Type:Bank
        D01/01'2020
        T-10.00
        LFood
        SFood
        $-7.00
        STransport
        ^
        """;

    assertThatThrownBy(() -> parse(text)).isInstanceOf(QifRejectedException.class);
  }

  @Test
  void parsesSplitContainingTransferLeg() {
    // A real split whose second leg is a transfer to a person account (import.md §7, Q-IMP-1).
    String text =
        """
        !Type:CCard
        D19/07'2016
        T-161.07
        PCitygoRentals
        LVacation:car-rental
        SVacation:car-rental
        $-115.05
        S[Debt-Volkov]
        $-46.02
        ^
        """;

    ImportedFile file = parse(text);
    ImportedTransaction transaction = file.transactions().get(0);

    assertThat(transaction.lines().get(1).target())
        .isEqualTo(new ImportedTarget.AccountReference("Debt-Volkov"));
    assertThat(file.referencedAccountNames()).contains("Debt-Volkov");
  }

  @Test
  void flagsTheSelfTransferAsOpeningBalance() {
    String text =
        """
        !Type:CCard
        D19/07'2016
        T0.00
        CX
        POpening Balance
        L[Advanzia-MC]
        ^
        """;

    ImportedTransaction transaction = parser.parse("Advanzia-MC", text).transactions().get(0);

    assertThat(transaction.openingBalance()).isTrue();
    assertThat(transaction.lines().get(0).target())
        .isEqualTo(new ImportedTarget.AccountReference("Advanzia-MC"));
  }

  @Test
  void doesNotFlagTransferToAnotherAccountAsOpeningBalance() {
    String text =
        """
        !Type:Bank
        D19/07'2016
        T-100.00
        L[Some Other Account]
        ^
        """;

    assertThat(parser.parse("Advanzia-MC", text).transactions().get(0).openingBalance()).isFalse();
  }

  @Test
  void dropsAnEntirelyDestroyedPayee() {
    String text =
        """
        !Type:Bank
        D01/01'2020
        T-5.00
        P???? ????
        LFood
        ^
        """;

    ImportedTransaction transaction = parse(text).transactions().get(0);
    assertThat(transaction.payeeText()).isNull();
    assertThat(transaction.payeeDestroyed()).isTrue();
  }

  @Test
  void keepsPartiallyDestroyedPayeeVerbatim() {
    String text =
        """
        !Type:Bank
        D01/01'2020
        T-5.00
        P???????? Rewe
        LFood
        ^
        """;

    ImportedTransaction transaction = parse(text).transactions().get(0);
    assertThat(transaction.payeeText()).isEqualTo("???????? Rewe");
    assertThat(transaction.payeeDestroyed()).isFalse();
  }

  @Test
  void marksAnAbsentPayeeAsNotDestroyed() {
    String text = "!Type:Bank\nD01/01'2020\nT-5.00\nLFood\n^\n";

    ImportedTransaction transaction = parse(text).transactions().get(0);
    assertThat(transaction.payeeText()).isNull();
    assertThat(transaction.payeeDestroyed()).isFalse();
  }

  @Test
  void rejectsFileThatReferencesDestroyedAccountName() {
    String text =
        """
        !Type:Bank
        D01/01'2020
        T-5.00
        L[???? ?????????]
        ^
        """;

    assertThatThrownBy(() -> parse(text))
        .isInstanceOf(QifRejectedException.class)
        .hasMessageContaining("destroyed");
  }

  @Test
  void collectsEveryReferencedAccountName() {
    String text =
        """
        !Type:Bank
        D01/01'2020
        T-5.00
        L[Commerzbank-main]
        ^
        D02/01'2020
        T-6.00
        LFood
        SFood
        $-2.00
        S[Cash-RUR]
        $-4.00
        ^
        """;

    assertThat(parser.parse("Bank24ru-EUR", text).referencedAccountNames())
        .containsExactlyInAnyOrder("Bank24ru-EUR", "Commerzbank-main", "Cash-RUR");
  }

  @Test
  void splitsTheClassSuffixOffTheCategoryPath() {
    String text =
        """
        !Type:Bank
        D01/01'2020
        T7.64
        LOtherIncome/holiday-fund
        ^
        """;

    ImportedLine line = parse(text).transactions().get(0).lines().get(0);

    assertThat(line.target()).isEqualTo(new ImportedTarget.CategoryPath("OtherIncome"));
    assertThat(line.className()).isEqualTo("holiday-fund");
  }

  @Test
  void dropsDestroyedClassSuffixRatherThanTagging() {
    String text =
        """
        !Type:Bank
        D01/01'2020
        T7.64
        LOtherIncome/??????
        ^
        """;

    ImportedLine line = parse(text).transactions().get(0).lines().get(0);

    assertThat(line.target()).isEqualTo(new ImportedTarget.CategoryPath("OtherIncome"));
    assertThat(line.className()).isNull();
  }

  @Test
  void splitsTheClassSuffixOffTransferTarget() {
    String text =
        """
        !Type:Bank
        D01/01'2020
        T-50.00
        L[Savings]/holiday-fund
        ^
        """;

    ImportedLine line = parse(text).transactions().get(0).lines().get(0);

    assertThat(line.target()).isEqualTo(new ImportedTarget.AccountReference("Savings"));
    assertThat(line.className()).isEqualTo("holiday-fund");
  }
}
