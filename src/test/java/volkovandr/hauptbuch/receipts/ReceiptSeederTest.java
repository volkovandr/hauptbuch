package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.PayingAccountDetector;
import volkovandr.hauptbuch.categories.AiVocabularyService;
import volkovandr.hauptbuch.categories.TagService;
import volkovandr.hauptbuch.debts.Person;
import volkovandr.hauptbuch.debts.PersonMatch;
import volkovandr.hauptbuch.debts.PersonService;

/**
 * Unit tier (plan §1.5): the lenient seeding logic (stage 9e, data-model §13.1/§13.2). Every AI
 * echo is resolved against the mocked live entities; a miss drops silently, a bad header field
 * stays null, and the seed is produced whatever the resolvers say — the fixing surface is
 * post-process (9f), not here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReceiptSeederTest {

  @Mock private AiVocabularyService aiVocabularyService;
  @Mock private TagService tagService;
  @Mock private PersonService personService;
  @Mock private PayingAccountDetector payingAccountDetector;

  private ReceiptSeeder seeder() {
    return new ReceiptSeeder(aiVocabularyService, tagService, personService, payingAccountDetector);
  }

  private static Account account(long id) {
    return new Account(id, "n", "asset", null, "EUR", null, null, null, null, false, false);
  }

  @Test
  void seedsHeaderAndResolvesCategoryLeaf() {
    when(payingAccountDetector.detect("card XXXX1234")).thenReturn(Optional.of(account(7L)));
    when(aiVocabularyService.resolveTerm("Food - Sweets")).thenReturn(OptionalLong.of(42L));

    ParsedReceipt parsed =
        new ParsedReceipt(
            new ParsedMerchant("Rewe", "Berlin", "Germany"),
            new ParsedTransaction(
                "2026-07-21", "12:13", "card XXXX1234", new BigDecimal("5.00"), "EUR", "4711"),
            List.of(
                new ParsedItem(
                    "Haribo",
                    null,
                    null,
                    new BigDecimal("2.50"),
                    "Food - Sweets",
                    null,
                    null,
                    null)));

    SeededReceipt seeded = seeder().seed(parsed);

    ParsedHeader header = seeded.header();
    assertThat(header.merchantText()).isEqualTo("Rewe");
    assertThat(header.merchantCity()).isEqualTo("Berlin");
    assertThat(header.receiptDate()).isEqualTo(LocalDate.of(2026, 7, 21));
    assertThat(header.receiptTime()).isEqualTo(LocalTime.of(12, 13));
    assertThat(header.totalAmount()).isEqualByComparingTo("5.00");
    assertThat(header.accountId()).isEqualTo(7L);
    assertThat(seeded.lines()).hasSize(1);
    ReceiptLineDraft line = seeded.lines().get(0);
    assertThat(line.description()).isEqualTo("Haribo");
    assertThat(line.amount()).isEqualByComparingTo("2.50");
    assertThat(line.accountId()).isEqualTo(42L);
  }

  @Test
  void unresolvedCategorySeedsUncategorised() {
    when(aiVocabularyService.resolveTerm(any())).thenReturn(OptionalLong.empty());

    ParsedReceipt parsed =
        new ParsedReceipt(
            null,
            null,
            List.of(
                new ParsedItem("Mystery", null, null, BigDecimal.ONE, "Nope", null, null, null)));

    assertThat(seeder().seed(parsed).lines().get(0).accountId()).isNull();
  }

  @Test
  void foldsQuantityGreaterThanOneIntoDescription() {
    when(aiVocabularyService.resolveTerm(any())).thenReturn(OptionalLong.empty());

    ParsedReceipt parsed =
        new ParsedReceipt(
            null,
            null,
            List.of(
                new ParsedItem(
                    "Bread",
                    BigDecimal.valueOf(2),
                    new BigDecimal("0.7"),
                    new BigDecimal("1.4"),
                    "",
                    null,
                    null,
                    null)));

    assertThat(seeder().seed(parsed).lines().get(0).description()).isEqualTo("2× Bread");
  }

  @Test
  void resolvesTransferTargetAndDoesNotFallBackToCategory() {
    when(payingAccountDetector.detect("cash")).thenReturn(Optional.of(account(9L)));

    ParsedReceipt parsed =
        new ParsedReceipt(
            null,
            null,
            List.of(
                new ParsedItem(
                    "Cashback",
                    null,
                    null,
                    new BigDecimal("50"),
                    "Food - Sweets",
                    null,
                    null,
                    "cash")));

    ReceiptLineDraft line = seeder().seed(parsed).lines().get(0);
    assertThat(line.accountId()).isEqualTo(9L); // the transfer target, not a category
  }

  @Test
  void resolvesBeneficiaryToLivePerson() {
    when(personService.matchExact("Bobby"))
        .thenReturn(new PersonMatch.Live(new Person(3L, "Bobby", null)));
    when(aiVocabularyService.resolveTerm(any())).thenReturn(OptionalLong.empty());

    ParsedReceipt parsed =
        new ParsedReceipt(
            null,
            null,
            List.of(
                new ParsedItem(
                    "Sweets", null, null, BigDecimal.valueOf(2), "", null, "Bobby", null)));

    assertThat(seeder().seed(parsed).lines().get(0).personId()).isEqualTo(3L);
  }

  @Test
  void dropsUnresolvedTagAndBeneficiaryEchoes() {
    when(personService.matchExact("Ghost")).thenReturn(new PersonMatch.NotFound());
    when(tagService.resolveExistingChip(any())).thenReturn(Optional.empty());
    when(aiVocabularyService.resolveTerm(any())).thenReturn(OptionalLong.empty());

    ParsedReceipt parsed =
        new ParsedReceipt(
            null,
            null,
            List.of(
                new ParsedItem("X", null, null, BigDecimal.ONE, "", "Car:Audi", "Ghost", null)));

    ReceiptLineDraft line = seeder().seed(parsed).lines().get(0);
    assertThat(line.personId()).isNull();
    assertThat(line.tagIds()).isEmpty();
  }

  @Test
  void resolvesTagEchoesToLeafIds() {
    when(tagService.resolveExistingChip("Car:Audi"))
        .thenReturn(Optional.of(new TagService.ResolvedChip(11L, "Car:Audi")));
    when(tagService.resolveExistingChip("Vacation"))
        .thenReturn(Optional.of(new TagService.ResolvedChip(12L, "Vacation")));
    when(aiVocabularyService.resolveTerm(any())).thenReturn(OptionalLong.empty());

    ParsedReceipt parsed =
        new ParsedReceipt(
            null,
            null,
            List.of(
                new ParsedItem(
                    "Fuel",
                    null,
                    null,
                    new BigDecimal("40"),
                    "",
                    "Car:Audi, Vacation",
                    null,
                    null)));

    assertThat(seeder().seed(parsed).lines().get(0).tagIds()).containsExactly(11L, 12L);
  }

  @Test
  void badDateAndTimeStayNull() {
    ParsedReceipt parsed =
        new ParsedReceipt(
            null, new ParsedTransaction("not-a-date", "99:99", null, null, "EUR", null), List.of());

    ParsedHeader header = seeder().seed(parsed).header();
    assertThat(header.receiptDate()).isNull();
    assertThat(header.receiptTime()).isNull();
    assertThat(header.currencyCode()).isEqualTo("EUR");
  }
}
