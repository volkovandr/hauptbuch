package volkovandr.hauptbuch.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import volkovandr.hauptbuch.accounts.repository.AccountRepository;

/**
 * Unit tier (plan §1.5): the paying-account detection rules (data-model §13.4) — the operator's
 * labels matched as substrings of whatever the AI called the payment line, the cash fallback that
 * needs a currency to pick between per-currency cash accounts, and the deliberate refusal to guess
 * when neither fires. The candidate list arrives pre-ordered from the repository (its ordering is
 * covered in the SQL-logic tier), so these tests hand it back in order and assert what the rules do
 * with it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayingAccountDetectorTest {

  private static final String EUR = "EUR";
  private static final String CHF = "CHF";

  @Mock private AccountRepository accountRepository;

  private static AccountDetectionCandidate card(long id, String currency, String labels) {
    return new AccountDetectionCandidate(id, currency, labels, false);
  }

  private static AccountDetectionCandidate cash(long id, String currency) {
    return new AccountDetectionCandidate(id, currency, null, true);
  }

  private PayingAccountDetector detector(AccountDetectionCandidate... candidates) {
    when(accountRepository.findDetectionCandidates(any())).thenReturn(List.of(candidates));
    return new PayingAccountDetector(accountRepository);
  }

  @Test
  void labelMatchesAsSubstringOfThePaymentLine() {
    PayingAccountDetector detector = detector(card(7L, EUR, "card, 1234"));

    // The three shapes real parses produce — including the bare 'card' that carries no digits.
    assertThat(detector.detect("card", EUR)).hasValue(7L);
    assertThat(detector.detect("XXXX1234", EUR)).hasValue(7L);
    assertThat(detector.detect("card XXXX1234", EUR)).hasValue(7L);
  }

  @Test
  void labelMatchingIgnoresCaseAndSurroundingSpace() {
    PayingAccountDetector detector = detector(card(7L, EUR, " GiroCard , 1234 "));

    assertThat(detector.detect("Paid by girocard", EUR)).hasValue(7L);
    assertThat(detector.detect("GIROCARD", EUR)).hasValue(7L);
  }

  @Test
  void firstMatchWinsInCandidateOrder() {
    // Two cards sharing a printed last-4 — legitimate, hence no uniqueness constraint on labels.
    PayingAccountDetector detector = detector(card(1L, EUR, "1234"), card(2L, EUR, "1234"));

    assertThat(detector.detect("card XXXX1234", EUR)).hasValue(1L);
  }

  @Test
  void labelsWithinAnAccountAreTriedInTheOrderGiven() {
    PayingAccountDetector detector =
        detector(card(1L, EUR, "visa"), card(2L, EUR, "girocard, visa"));

    // 'visa' hits the first candidate before the second's own 'visa' is reached.
    assertThat(detector.detect("visa 1234", EUR)).hasValue(1L);
    assertThat(detector.detect("girocard 1234", EUR)).hasValue(2L);
  }

  @Test
  void blankLabelsNeverMatch() {
    // An empty string is a substring of everything — a trailing comma must not match every receipt.
    PayingAccountDetector detector = detector(card(1L, EUR, "1234, ,"), card(2L, EUR, ""));

    assertThat(detector.detect("Bar", EUR)).isEmpty();
    assertThat(detector.detect("anything at all", EUR)).isEmpty();
  }

  @Test
  void anExplicitLabelBeatsTheBuiltInCashKeywords() {
    // 'Barclaycard' contains 'bar'; the operator's label must win over the built-in vocabulary.
    PayingAccountDetector detector = detector(card(1L, EUR, "barclaycard"), cash(2L, EUR));

    assertThat(detector.detect("Barclaycard", EUR)).hasValue(1L);
  }

  @Test
  void cashResolvesToTheCashAccountOfTheReceiptCurrency() {
    PayingAccountDetector detector = detector(cash(1L, CHF), cash(2L, EUR));

    assertThat(detector.detect("Bar", CHF)).hasValue(1L);
    assertThat(detector.detect("cash", EUR)).hasValue(2L);
    assertThat(detector.detect("Bargeldauszahlung", CHF)).hasValue(1L);
  }

  @Test
  void duplicateCashMarkersTakeTheFirstCandidate() {
    // Not expected, not refused: the repository's name ordering makes it the first alphabetically.
    PayingAccountDetector detector = detector(cash(1L, EUR), cash(2L, EUR));

    assertThat(detector.detect("cash", EUR)).hasValue(1L);
  }

  @Test
  void cashWithoutAnIdentifiedCurrencyResolvesEmpty() {
    PayingAccountDetector detector = detector(cash(1L, EUR));

    assertThat(detector.detect("cash", null)).isEmpty();
    assertThat(detector.detect("cash", "  ")).isEmpty();
  }

  @Test
  void cashInAnotherCurrencyThanTheReceiptResolvesEmpty() {
    PayingAccountDetector detector = detector(cash(1L, EUR));

    assertThat(detector.detect("cash", CHF)).isEmpty();
  }

  @Test
  void unrecognisedSignalsResolveEmptyRatherThanGuessing() {
    PayingAccountDetector detector = detector(card(1L, EUR, "1234"), cash(2L, EUR));

    assertThat(detector.detect(null, EUR)).isEmpty();
    assertThat(detector.detect("  ", EUR)).isEmpty();
    assertThat(detector.detect("Kundenkarte 9999", EUR)).isEmpty();
  }

  @Test
  void accountsWithNoLabelsAreSkipped() {
    PayingAccountDetector detector = detector(card(1L, EUR, null), card(2L, EUR, "1234"));

    assertThat(detector.detect("card XXXX1234", EUR)).hasValue(2L);
  }

  @Test
  void theParsedCurrencyIsNormalisedBeforeUse() {
    // Raw model output, not a validated code — lowercase and padding must still match.
    PayingAccountDetector detector = detector(cash(1L, EUR));

    assertThat(detector.detect("cash", " eur ")).hasValue(1L);
  }
}
