package volkovandr.hauptbuch.receipts;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.categories.AiVocabularyNode;

/**
 * Assembles the system prompt for a receipt parse (stage 9e): the parsing instructions, the
 * operator-curated AI Vocabulary rendered flat, and the TOON output skeleton with one worked text
 * example. Pure and unit-tested — it never reads ledger content (ARCH-08); the only DB-derived
 * input is the vocabulary forest, which is itself the sanctioned projection (data-model §13.3).
 *
 * <p>Vocabulary rendering (per the 9e spec): {@code Income:} / {@code Expense:} headers, one
 * effective leaf path per line ({@code Food - Sweets}), a leaf's note appended after an em-dash,
 * and a group's note on a group line above its leaves. The stable prefix/suffix ordering is kept so
 * a cache breakpoint can land here in 9h without reshaping the prompt.
 */
@Component
public class ReceiptPromptBuilder {

  private static final String PATH_SEPARATOR = " - ";
  private static final String INCOME = "income";

  /**
   * The fixed instructions + TOON skeleton — the cacheable prefix, before the volatile vocabulary.
   */
  private static final String INSTRUCTIONS =
      """
      You extract structured data from a photographed shop receipt. Read the image and return the \
      result as TOON (Token-Oriented Object Notation) — nothing else, no prose, no code fence.

      Emit exactly this shape, omitting a field (leaving it blank) whenever the receipt does not \
      show it — never invent a value:

        merchant:
          name: <shop name>
          city: <city>
          country: <country>
        transaction:
          date: <yyyy-mm-dd>
          time: <HH:mm>
          account: <the payment line: "card XXXX1234" with the last 4 digits, or "Bar" for cash>
          totalAmount: <the printed total, digits and a dot decimal, e.g. 45.67>
          currency: <ISO code, e.g. EUR>
          receiptNumber: <the printed receipt/Beleg number>
        items[N]{name,quantity,unitPrice,totalPrice,category,tags,beneficiary,transfer}:
          <one row per line item>

      Worked example:
        merchant:
          name: Total Tankstelle
          city:
          country: Germany
        transaction:
          date: 2026-07-21
          time: 12:13
          account: card XXXX1234
          totalAmount: 45.67
          currency: EUR
          receiptNumber: 4711
        items[3]{name,quantity,unitPrice,totalPrice,category,tags,beneficiary,transfer}:
          Big Bear's Tasty Bread,2,0.7,1.4,Food - Non Sweets,,,
          "Coca Cola, Zero",,,2.13,Food - Drinks,,,
          Diesel Fuel,21.948,1.92,42.14,Car - Fuel,,,

      Rules:
      - category: choose one full path from the Categories list below, copied exactly. If no \
      listed leaf fits an item, leave category blank — never guess a near-miss, never invent one.
      - tags and beneficiary: fill these ONLY when a category's note below tells you to, echoing \
      the exact name the note gives. Put multiple tags in one cell, comma-separated. Else blank.
      - transfer: fill with "cash" or a card's last 4 digits ONLY for a cash-withdrawal line \
      (supermarket Bargeldauszahlung) or an ATM cash-in/out slip. Otherwise blank.
      - special characters: in case a string value contains a comma or starts with a space, \
      double quote the value.
      """;

  /**
   * Build the full system prompt: the instructions (the operator's override when set, else the
   * built-in default) followed by the rendered category list. The categories are always regenerated
   * from the live vocabulary — only the instructions are operator-editable (owner feedback
   * 2026-08-02).
   */
  public String build(List<AiVocabularyNode> vocabulary, String instructionsOverride) {
    String instructions =
        instructionsOverride == null || instructionsOverride.isBlank()
            ? INSTRUCTIONS
            : instructionsOverride.strip();
    return instructions + "\n" + renderCategories(vocabulary);
  }

  /** The built-in default instructions — the baseline the operator edits from (owner feedback). */
  public String defaultInstructions() {
    return INSTRUCTIONS;
  }

  /**
   * The category list exactly as it is injected into the prompt — the read-only "what the AI sees"
   * preview on the prompt-edit screen (owner feedback 2026-08-02).
   */
  public String renderCategories(List<AiVocabularyNode> vocabulary) {
    StringBuilder out = new StringBuilder("Categories:\n");
    appendType(out, "Income:", vocabulary, INCOME);
    appendType(out, "Expense:", vocabulary, "expense");
    return out.toString();
  }

  private void appendType(
      StringBuilder out, String header, List<AiVocabularyNode> vocabulary, String type) {
    out.append(header).append('\n');
    for (AiVocabularyNode root : vocabulary) {
      if (type.equals(root.type())) {
        appendNode(out, root, root.name());
      }
    }
  }

  /**
   * Depth-first: a group with a note prints a heading line above its leaves; leaves print as
   * bullets.
   */
  private void appendNode(StringBuilder out, AiVocabularyNode node, String path) {
    if (node.children().isEmpty()) {
      out.append("- ").append(path);
      if (node.note() != null && !node.note().isBlank()) {
        out.append(" — ").append(node.note().strip());
      }
      out.append('\n');
      return;
    }
    if (node.note() != null && !node.note().isBlank()) {
      out.append(path).append(" — ").append(node.note().strip()).append('\n');
    }
    for (AiVocabularyNode child : node.children()) {
      appendNode(out, child, path + PATH_SEPARATOR + child.name());
    }
  }

  /** The user-turn text accompanying the image: the receipt's AI note, or a neutral default. */
  public String userText(String aiNote) {
    List<String> parts = new ArrayList<>();
    parts.add("Parse this receipt.");
    if (aiNote != null && !aiNote.isBlank()) {
      parts.add(aiNote.strip());
    }
    return String.join(" ", parts);
  }
}
