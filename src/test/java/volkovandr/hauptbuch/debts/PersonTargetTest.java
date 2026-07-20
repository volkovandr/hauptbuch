package volkovandr.hauptbuch.debts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PersonTargetTest {

  @Test
  void optionBuildsForPrefix() {
    assertThat(PersonTarget.option(PersonTarget.Direction.FOR, "Max")).isEqualTo("for Max");
  }

  @Test
  void optionBuildsByPrefix() {
    assertThat(PersonTarget.option(PersonTarget.Direction.BY, "Max")).isEqualTo("by Max");
  }

  @Test
  void parseReadsForPrefix() {
    Optional<PersonTarget.Parsed> parsed = PersonTarget.parse("for Max");

    assertThat(parsed).isPresent();
    assertThat(parsed.get().direction()).isEqualTo(PersonTarget.Direction.FOR);
    assertThat(parsed.get().personName()).isEqualTo("Max");
  }

  @Test
  void parseReadsByPrefix() {
    Optional<PersonTarget.Parsed> parsed = PersonTarget.parse("by Max");

    assertThat(parsed).isPresent();
    assertThat(parsed.get().direction()).isEqualTo(PersonTarget.Direction.BY);
    assertThat(parsed.get().personName()).isEqualTo("Max");
  }

  @Test
  void parseStripsSurroundingWhitespace() {
    Optional<PersonTarget.Parsed> parsed = PersonTarget.parse("  for   Max  ");

    assertThat(parsed).isPresent();
    assertThat(parsed.get().personName()).isEqualTo("Max");
  }

  @Test
  void parseReturnsEmptyForPlainCategoryText() {
    assertThat(PersonTarget.parse("Food")).isEmpty();
  }

  @Test
  void parseReturnsEmptyForNull() {
    assertThat(PersonTarget.parse(null)).isEmpty();
  }

  @Test
  void parseReturnsEmptyWhenNameIsBlank() {
    assertThat(PersonTarget.parse("for ")).isEmpty();
    assertThat(PersonTarget.parse("by   ")).isEmpty();
  }

  @Test
  void parseIsCaseSensitiveOnThePrefix() {
    // "For"/"By" (capitalised) are not the reserved keyword — a category could legitimately be
    // named that way, so only the exact lowercase prefix triggers person parsing (register §3.5).
    assertThat(PersonTarget.parse("For Max")).isEmpty();
    assertThat(PersonTarget.parse("By Max")).isEmpty();
  }
}
