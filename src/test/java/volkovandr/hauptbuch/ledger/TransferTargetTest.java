package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.ledger.TransferTarget.Direction;
import volkovandr.hauptbuch.ledger.TransferTarget.Parsed;

/**
 * Unit tier (plan §1.5): the transfer-target vocabulary the Category field round-trips (register
 * §3.5, plan stage 7d.3) — the option value the datalist offers and the parse the counterpart
 * resolver reads back are one definition, so they must agree.
 */
class TransferTargetTest {

  @Test
  void formatsToOptionWithTheDirectionGlyph() {
    assertThat(TransferTarget.option(Direction.TO, "Visa")).isEqualTo("To → Visa");
  }

  @Test
  void formatsFromOptionWithTheDirectionGlyph() {
    assertThat(TransferTarget.option(Direction.FROM, "Visa")).isEqualTo("From ← Visa");
  }

  @Test
  void parsesTheToOptionItProduced() {
    Parsed parsed = TransferTarget.parse(TransferTarget.option(Direction.TO, "Giro")).orElseThrow();
    assertThat(parsed.direction()).isEqualTo(Direction.TO);
    assertThat(parsed.accountName()).isEqualTo("Giro");
  }

  @Test
  void parsesTheFromOptionItProduced() {
    Parsed parsed =
        TransferTarget.parse(TransferTarget.option(Direction.FROM, "Giro")).orElseThrow();
    assertThat(parsed.direction()).isEqualTo(Direction.FROM);
    assertThat(parsed.accountName()).isEqualTo("Giro");
  }

  @Test
  void plainCategoryTextIsNotTransfer() {
    assertThat(TransferTarget.parse("Food")).isEmpty();
    assertThat(TransferTarget.parse("Food - Milk")).isEmpty();
  }

  @Test
  void nullTextIsNotTransfer() {
    assertThat(TransferTarget.parse(null)).isEmpty();
  }
}
