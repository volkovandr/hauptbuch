package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.categories.AiVocabularyNode;

/**
 * Unit tier (plan §1.5): the parser prompt assembly (stage 9e). Asserts the vocabulary renders
 * under Income/Expense with effective leaf paths, leaf and group notes attach, and the user text
 * folds in the receipt's AI note — never any ledger content (ARCH-08), only the curated projection.
 */
class ReceiptPromptBuilderTest {

  private final ReceiptPromptBuilder builder = new ReceiptPromptBuilder();

  @Test
  void rendersEffectiveLeafPathsUnderTypeHeaders() {
    List<AiVocabularyNode> vocabulary =
        List.of(
            new AiVocabularyNode(
                "Food",
                "expense",
                null,
                List.of(
                    new AiVocabularyNode("Sweets", "expense", "M&Ms → for Bobby", List.of()),
                    new AiVocabularyNode("Drinks", "expense", null, List.of()))),
            new AiVocabularyNode("bottle deposit returns", "income", null, List.of()));

    String prompt = builder.build(vocabulary, null);

    assertThat(prompt).contains("Income:").contains("Expense:");
    assertThat(prompt).contains("- Food - Sweets — M&Ms → for Bobby");
    assertThat(prompt).contains("- Food - Drinks");
    assertThat(prompt).contains("- bottle deposit returns");
  }

  @Test
  void attachesGroupNoteAboveItsLeaves() {
    List<AiVocabularyNode> vocabulary =
        List.of(
            new AiVocabularyNode(
                "Car",
                "expense",
                "diesel → tag Car:Audi",
                List.of(new AiVocabularyNode("Fuel", "expense", null, List.of()))));

    String prompt = builder.build(vocabulary, null);

    assertThat(prompt).contains("Car — diesel → tag Car:Audi");
    assertThat(prompt).contains("- Car - Fuel");
  }

  @Test
  void useOverrideInstructionsButAlwaysAppendsLiveCategories() {
    List<AiVocabularyNode> vocabulary =
        List.of(new AiVocabularyNode("Food", "expense", null, List.of()));

    String prompt = builder.build(vocabulary, "My custom instructions");

    // The operator's instructions replace the default, but the category list is still injected.
    assertThat(prompt).startsWith("My custom instructions");
    assertThat(prompt).doesNotContain("You extract structured data");
    assertThat(prompt).contains("Categories:").contains("- Food");
  }

  @Test
  void blankOverrideFallsBackToTheDefaultInstructions() {
    String prompt = builder.build(List.of(), "   ");

    assertThat(prompt).contains("You extract structured data");
    assertThat(prompt).isEqualTo(builder.build(List.of(), null));
  }

  @Test
  void renderCategoriesIsJustTheInjectedCategoryBlock() {
    List<AiVocabularyNode> vocabulary =
        List.of(new AiVocabularyNode("Food", "expense", null, List.of()));

    String categories = builder.renderCategories(vocabulary);

    assertThat(categories).startsWith("Categories:");
    assertThat(categories).contains("- Food");
    assertThat(categories).doesNotContain("You extract structured data");
  }

  @Test
  void userTextFoldsInTheAiNote() {
    assertThat(builder.userText("this is fuel")).contains("this is fuel");
    assertThat(builder.userText(null)).isEqualTo("Parse this receipt.");
  }
}
