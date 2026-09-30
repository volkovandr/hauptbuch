package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The deterministic TOON repair (receipt-processing/33): text in, repaired text + notes out. */
class ToonRepairTest {

  private static final String HEADER_COLS =
      "{name,quantity,unitPrice,totalPrice,category,tags,beneficiary,transfer}:";

  private final ToonRepair repair = new ToonRepair();
  private final ToonReceiptDecoder decoder = new ToonReceiptDecoder();

  private static String body(int declared, String... rows) {
    String head =
        "merchant:\n  name: ShopAaa\n  city:\n  country: Germany\ntransaction:\n"
            + "  date: 2026-07-21\n"
            + "  time: 12:13\n  account: Bar\n  totalAmount: 5.00\n  currency: EUR\n"
            + "  receiptNumber: 1\nitems["
            + declared
            + ']'
            + HEADER_COLS;
    return rows.length == 0 ? head : head + "\n  " + String.join("\n  ", rows);
  }

  @Test
  void quotesNameWithUnquotedComma() {
    ToonRepair.Result result = repair.repair(body(1, "Item One 3,5,,,1.69,Food,,,"));

    assertThat(result.text()).isEqualTo(body(1, "\"Item One 3,5\",,,1.69,Food,,,"));
    assertThat(result.changes()).containsExactly("row 1: quoted the name");
    assertThat(decoder.decode(result.text()).orElseThrow().items().get(0).name())
        .isEqualTo("Item One 3,5");
  }

  @Test
  void escapesInnerQuoteAndWrapsTheName() {
    ToonRepair.Result result = repair.repair(body(1, "Pizza 12\" big,1,2,2,Food,,,"));

    assertThat(result.text()).isEqualTo(body(1, "\"Pizza 12\\\" big\",1,2,2,Food,,,"));
    assertThat(result.changes()).containsExactly("row 1: fixed the quotes in the name");
  }

  @Test
  void normalisesWronglyQuotedName() {
    ToonRepair.Result result = repair.repair(body(1, "\"Item \"One\" Two\",1,2,2,Food,,,"));

    assertThat(result.text()).isEqualTo(body(1, "\"Item \\\"One\\\" Two\",1,2,2,Food,,,"));
  }

  @Test
  void leavesCorrectlyQuotedNameUntouched() {
    String clean = body(1, "\"Item One, Two\",1,2,2,Food,,,");

    ToonRepair.Result result = repair.repair(clean);

    assertThat(result.text()).isEqualTo(clean);
    assertThat(result.changes()).isEmpty();
    assertThat(result.warnings()).isEmpty();
  }

  @Test
  void doesNotMisSplitQuotedMultiTagCell() {
    String clean = body(1, "Item One,1,2,2,Food,\"Trip A,Trip B\",,");

    assertThat(repair.repair(clean).text()).isEqualTo(clean);
    ToonRepair.Result broken = repair.repair(body(1, "Item, One,1,2,2,Food,\"Trip A,Trip B\",,"));
    assertThat(broken.text()).isEqualTo(body(1, "\"Item, One\",1,2,2,Food,\"Trip A,Trip B\",,"));
  }

  @Test
  void rewritesItemCountThatIsTooHighOrTooLow() {
    String rows = "Item One,1,2,2,Food,,,";

    ToonRepair.Result high = repair.repair(body(5, rows));
    ToonRepair.Result low = repair.repair(body(0, rows, rows));

    assertThat(high.text()).isEqualTo(body(1, rows));
    assertThat(high.changes()).containsExactly("items[5] → items[1]");
    assertThat(low.text()).isEqualTo(body(2, rows, rows));
  }

  @Test
  void stripsCodeFenceAndLeadingProse() {
    String clean = body(1, "Item One,1,2,2,Food,,,");

    ToonRepair.Result result = repair.repair("Here you go:\n```toon\n" + clean + "\n```");

    assertThat(result.text()).isEqualTo(clean);
    assertThat(result.changes())
        .containsExactly("removed the code fence", "removed text before merchant:");
  }

  @Test
  void warnsAboutRowWithTooFewCommasAndLeavesItAlone() {
    String text = body(2, "Item One,1,2,2,Food,,,", "Item Two,1,2");

    ToonRepair.Result result = repair.repair(text);

    assertThat(result.text()).isEqualTo(text);
    assertThat(result.changes()).isEmpty();
    assertThat(result.warnings()).hasSize(1);
    assertThat(result.warnings().get(0)).startsWith("row 2:");
  }

  @Test
  void warnsAboutIncompleteLastRowAndKeepsItCounted() {
    String text = body(2, "Item One,1,2,2,Food,,,", "Item Two,1,2");

    assertThat(repair.repair(text).warnings().get(0)).contains("cut off");
  }

  @Test
  void cleanBodyComesBackUnchanged() {
    String clean = body(2, "Item One,1,2,2,Food,,,", "\"Item, Two\",,,3,Food,,,");

    ToonRepair.Result result = repair.repair(clean);

    assertThat(result.text()).isEqualTo(clean);
    assertThat(result.changes()).isEmpty();
    assertThat(result.warnings()).isEmpty();
  }

  @Test
  void blankAndHeaderlessInputIsLeftAlone() {
    assertThat(repair.repair("").text()).isEmpty();
    assertThat(repair.repair("just some words").text()).isEqualTo("just some words");
    assertThat(repair.repair(null).text()).isEmpty();
  }

  @Test
  void repairingTwiceEqualsRepairingOnce() {
    String broken =
        "```\n" + body(9, "Item, One 3,5,,,1.69,Food,,,", "Pizza 12\" big,1,2,2,Food,,,") + "\n```";

    ToonRepair.Result once = repair.repair(broken);
    ToonRepair.Result twice = repair.repair(once.text());

    assertThat(twice.text()).isEqualTo(once.text());
    assertThat(twice.changes()).isEmpty();
  }

  @Test
  void stripsOpeningFenceEvenWithoutMerchantLine() {
    ToonRepair.Result result = repair.repair("```\nitems[0]{name,quantity}:\n```");

    assertThat(result.text()).isEqualTo("items[0]{name,quantity}:");
  }

  @Test
  void leavesTablesWhoseFirstColumnIsNotTheNameAlone() {
    String text = "items[9]{quantity,name}:\n  1,Item, One";

    assertThat(repair.repair(text).text()).isEqualTo(text);
  }
}
