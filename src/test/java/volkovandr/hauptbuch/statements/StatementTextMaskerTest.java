package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (CLAUDE.md §6): what a statement's text is masked of before the operator sees it —
 * the operator's own identifiers however they are grouped, any IBAN-shaped string, a labelled BIC
 * — and what is deliberately left alone.
 */
class StatementTextMaskerTest {

  private static final List<String> OWN = List.of("XX00 1111 2222");

  @Test
  void ownIdentifierIsMaskedWhateverItsGroupingOrCase() {
    String text = "Konto XX00 1111 2222 und xx0011112222 und XX00  1111  2222.";

    assertThat(StatementTextMasker.mask(text, OWN))
        .isEqualTo("Konto [ACCOUNT] und [ACCOUNT] und [ACCOUNT].");
  }

  @Test
  void anyOtherIbanShapedStringIsMaskedGroupedOrNot() {
    String text = "An DE89 3704 0044 0532 0130 00 und GB29NWBK60161331926819 gebucht";

    assertThat(StatementTextMasker.mask(text, List.of()))
        .isEqualTo("An [IBAN] und [IBAN] gebucht");
  }

  @Test
  void aBicIsMaskedOnlyWhenALabelIntroducesIt() {
    String text = "BIC: ABCDXXYY und SWIFT-Code ABCDXXYYZZZ aber TRANSFER und BANKNAME bleiben";

    assertThat(StatementTextMasker.mask(text, List.of()))
        .isEqualTo("BIC: [BIC] und SWIFT-Code [BIC] aber TRANSFER und BANKNAME bleiben");
  }

  @Test
  void counterpartyNamesAmountsAndDatesStay() {
    String text = "02.05.2026 ShopAaa GmbH -1.234,56 EUR Kartenzahlung";

    assertThat(StatementTextMasker.mask(text, OWN)).isEqualTo(text);
  }

  @Test
  void ownIdentifierInsideALongerNumberOrAcrossLinesIsLeftAlone() {
    List<String> own = List.of("12345678");
    String text = "Ref 912345678 und 1234\n5678 und 12345678.";

    assertThat(StatementTextMasker.mask(text, own))
        .isEqualTo("Ref 912345678 und 1234\n5678 und [ACCOUNT].");
  }

  @Test
  void blankIdentifiersAreIgnored() {
    assertThat(StatementTextMasker.mask("nothing here", List.of("", "  "))).isEqualTo("nothing here");
  }
}
