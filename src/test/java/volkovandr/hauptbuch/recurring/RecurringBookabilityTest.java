package volkovandr.hauptbuch.recurring;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.Person;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.recurring.repository.RecurringTemplateRepository;

/**
 * Unit tier (CLAUDE.md §6): a template books only while everything it names is still usable (data-
 * model §14.3, recurring sub-plan slice f). The dock itself would post to a closed account, or
 * route a category that has since gained subcategories into one of them, so the run checks first
 * and names what is wrong; the failure is then recorded on the template.
 */
@ExtendWith(MockitoExtension.class)
class RecurringBookabilityTest {

  private static final long TEMPLATE_ID = 5L;
  private static final long BANK_ID = 7L;
  private static final long STREAMING_ID = 11L;
  private static final long SAVINGS_ID = 12L;
  private static final long MAX_ID = 13L;

  @Mock private RecurringTemplateRepository repository;
  @Mock private AccountService accountService;
  @Mock private PersonService personService;

  private RecurringBookability bookability() {
    return new RecurringBookability(repository, accountService, personService);
  }

  private static RecurringTemplate template(Long accountId, Long personId) {
    return new RecurringTemplate(
        TEMPLATE_ID,
        "Streaming",
        LocalDate.of(2026, 1, 31),
        "month",
        1,
        null,
        0,
        "auto",
        LocalDate.of(2026, 9, 27),
        false,
        null,
        null,
        accountId,
        personId,
        personId == null ? null : "BY",
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static RecurringTemplateLine line(
      Long accountId, String transferDirection, Long personId, String personDirection) {
    return new RecurringTemplateLine(
        1L,
        TEMPLATE_ID,
        accountId,
        transferDirection,
        personId,
        personDirection,
        BigDecimal.TEN,
        null,
        0);
  }

  private static Account account(
      long id, String name, String type, LocalDate closedAt, OffsetDateTime deletedAt) {
    return new Account(
        id, name, type, null, "EUR", null, null, closedAt, deletedAt, false, false, false);
  }

  private void has(Account account) {
    when(accountService.findById(account.accountId())).thenReturn(Optional.of(account));
  }

  private void openBank() {
    has(account(BANK_ID, "BankAaa-EUR", "asset", null, null));
  }

  private void lines(RecurringTemplateLine... lines) {
    when(repository.findLines(TEMPLATE_ID)).thenReturn(List.of(lines));
  }

  @Test
  void acceptsTemplateWhoseReferencesAreAllUsable() {
    openBank();
    has(account(STREAMING_ID, "Streaming", "expense", null, null));
    has(account(SAVINGS_ID, "BankBbb-EUR", "asset", null, null));
    when(accountService.findChildrenOf(STREAMING_ID))
        .thenReturn(
            List.of(
                new Account(
                    20L,
                    "CHF",
                    "expense",
                    STREAMING_ID,
                    "CHF",
                    null,
                    null,
                    null,
                    null,
                    true,
                    false,
                    false)));
    when(personService.findById(MAX_ID)).thenReturn(Optional.of(new Person(MAX_ID, "Max", null)));
    lines(
        line(STREAMING_ID, null, null, null),
        line(SAVINGS_ID, "TO", null, null),
        line(null, null, MAX_ID, "FOR"));

    assertThatCode(() -> bookability().requireBookable(template(BANK_ID, null)))
        .doesNotThrowAnyException();
  }

  @Test
  void refusesClosedFundingAccount() {
    has(account(BANK_ID, "BankAaa-EUR", "asset", LocalDate.of(2026, 9, 1), null));

    assertThatIllegalStateException()
        .isThrownBy(() -> bookability().requireBookable(template(BANK_ID, null)))
        .withMessage("Account 'BankAaa-EUR' is closed");
  }

  @Test
  void refusesClosedTransferTarget() {
    openBank();
    has(account(SAVINGS_ID, "BankBbb-EUR", "asset", LocalDate.of(2026, 9, 1), null));
    lines(line(SAVINGS_ID, "TO", null, null));

    assertThatIllegalStateException()
        .isThrownBy(() -> bookability().requireBookable(template(BANK_ID, null)))
        .withMessage("Account 'BankBbb-EUR' is closed");
  }

  @Test
  void refusesDeletedCategory() {
    openBank();
    has(account(STREAMING_ID, "Streaming", "expense", null, OffsetDateTime.now()));
    lines(line(STREAMING_ID, null, null, null));

    assertThatIllegalStateException()
        .isThrownBy(() -> bookability().requireBookable(template(BANK_ID, null)))
        .withMessage("Category 'Streaming' was deleted");
  }

  @Test
  void refusesCategoryThatGainedSubcategories() {
    openBank();
    has(account(STREAMING_ID, "Streaming", "expense", null, null));
    when(accountService.findChildrenOf(STREAMING_ID))
        .thenReturn(List.of(account(21L, "Video", "expense", null, null)));
    lines(line(STREAMING_ID, null, null, null));

    assertThatIllegalStateException()
        .isThrownBy(() -> bookability().requireBookable(template(BANK_ID, null)))
        .withMessage("Category 'Streaming' has subcategories: pick one of them in the template");
  }

  @Test
  void refusesDeletedFundingPerson() {
    when(personService.findById(MAX_ID)).thenReturn(Optional.empty());

    assertThatIllegalStateException()
        .isThrownBy(() -> bookability().requireBookable(template(null, MAX_ID)))
        .withMessage("A person the template names was deleted");
  }

  @Test
  void refusesDeletedLinePerson() {
    openBank();
    when(personService.findById(MAX_ID)).thenReturn(Optional.empty());
    lines(line(null, null, MAX_ID, "FOR"));

    assertThatIllegalStateException()
        .isThrownBy(() -> bookability().requireBookable(template(BANK_ID, null)))
        .withMessage("A person the template names was deleted");
  }
}
