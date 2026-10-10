package volkovandr.hauptbuch.statements;

import org.springframework.stereotype.Component;

/**
 * Assembles the prompts for a statement parse (statements.md §3.2): the parsing instructions — the
 * operator's override when set, else the built-in default — and the user turn, which is the
 * profile's AI note followed by the statement text. Pure; it reads nothing from the ledger
 * (ARCH-08).
 */
@Component
class StatementPromptBuilder {

  private static final String INSTRUCTIONS =
      """
      You extract structured data from the text of a bank account statement. The text may be \
      German or English and may include a letter, notes and legal text around the transaction \
      table; only the booking table and the two balances matter. Return the result as TOON \
      (Token-Oriented Object Notation) — nothing else, no prose, no code fence.

      Emit exactly this shape, leaving a field blank whenever the statement does not show it — \
      never invent a value:

        statement:
          periodStart: <yyyy-mm-dd>
          periodEnd: <yyyy-mm-dd>
          openingBalance: <the balance at the start of the period, digits and a dot decimal>
          closingBalance: <the balance at the end of the period>
        lines[N]{bookingDate,valueDate,amount,counterparty,description,bankCategory,\
      originalAmount,originalCurrency,originalRate}:
          <one row per booking, in the order printed>

      Worked example:
        statement:
          periodStart: 2026-05-01
          periodEnd: 2026-05-31
          openingBalance: 1200.50
          closingBalance: 1138.00
        lines[2]{bookingDate,valueDate,amount,counterparty,description,bankCategory,\
      originalAmount,originalCurrency,originalRate}:
          2026-05-02,2026-05-02,-12.50,ShopAaa,Card payment ShopAaa,Groceries,,,
          2026-05-09,2026-05-10,-50.00,"ShopBbb, Ltd",Card payment 55.00 USD at 1.10,\
      Shopping,55.00,USD,1.10

      Rules:
      - dates: always yyyy-mm-dd. Read the statement's own date format carefully (day.month.year \
      in German statements); never swap day and month.
      - amount: signed, in the account's currency, dot decimal and no thousands separator. Money \
      leaving the account is negative, money arriving is positive. Read the Soll/Haben or \
      debit/credit marking and any trailing minus sign correctly.
      - originalAmount, originalCurrency, originalRate: fill ONLY when the line shows a charge in \
      another currency (amount, ISO code and exchange rate as printed). Otherwise leave blank.
      - bankCategory: the bank's own category label for the line, copied as printed, else blank.
      - counterparty and description: copied as printed. In case a value contains a comma or \
      starts with a space, double quote the value.
      - openingBalance and closingBalance: the balances as printed, with the sign of the balance \
      itself; leave blank if absent.
      - skip every row that is not a booking (headings, running-balance rows, totals, notes).
      """;

  /** The instructions to send: the operator's override when set, else the built-in default. */
  String build(String instructionsOverride) {
    return instructionsOverride == null || instructionsOverride.isBlank()
        ? INSTRUCTIONS
        : instructionsOverride.strip();
  }

  /** The built-in default — the baseline the operator edits from. */
  String defaultInstructions() {
    return INSTRUCTIONS;
  }

  /** The user turn: a neutral request, the profile's AI note when there is one, then the text. */
  String userText(String aiNote, String statementText) {
    StringBuilder out = new StringBuilder("Parse this bank statement.");
    if (aiNote != null && !aiNote.isBlank()) {
      out.append(' ').append(aiNote.strip());
    }
    return out.append("\n\n").append(statementText).toString();
  }
}
