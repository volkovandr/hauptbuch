package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tier: which instructions are sent and how the user turn is assembled. */
class StatementPromptBuilderTest {

  private final StatementPromptBuilder builder = new StatementPromptBuilder();

  @Test
  void usesTheBuiltInDefaultUnlessOverridden() {
    assertThat(builder.build(null)).isEqualTo(builder.defaultInstructions());
    assertThat(builder.build("  ")).isEqualTo(builder.defaultInstructions());
    assertThat(builder.build("  my instructions \n")).isEqualTo("my instructions");
  }

  @Test
  void theDefaultNamesTheOutputShape() {
    assertThat(builder.defaultInstructions())
        .contains("periodStart", "openingBalance", "lines[N]{bookingDate");
  }

  @Test
  void userTextIsTheNoteThenTheStatementText() {
    assertThat(builder.userText(" Beschreibung carries the rate. ", "02.05.2026 ShopAaa -12,50"))
        .isEqualTo(
            "Parse this bank statement. Beschreibung carries the rate.\n\n"
                + "02.05.2026 ShopAaa -12,50");
    assertThat(builder.userText(null, "text")).isEqualTo("Parse this bank statement.\n\ntext");
  }
}
