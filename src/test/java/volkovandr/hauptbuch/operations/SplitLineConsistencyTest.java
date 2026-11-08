package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountPath;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.accounts.PostToAccount;
import volkovandr.hauptbuch.accounts.PostToAccountService;
import volkovandr.hauptbuch.accounts.PostToResolution;

/**
 * Unit tier: the commit-time guard against a split line whose visible category text and hidden
 * resolved state have drifted apart (issue transaction-register-ui/30). The text is what the
 * operator sees and believes they are booking to; the hidden id/direction/person is what the commit
 * would actually use, and the resolve round-trip that keeps them equal can lose a race with Save.
 * Every case posts the line arrays exactly as the panel does.
 */
@ExtendWith(MockitoExtension.class)
class SplitLineConsistencyTest {

  private static final long FOOD_ID = 10L;
  private static final long CASH_ID = 20L;
  private static final long OTHER_ID = 21L;
  private static final String FOOD = "Food";
  private static final String CASH_SEK = "Cash-SEK";
  private static final String TO = "TO";
  private static final String TO_CASH_SEK = "To → Cash-SEK (SEK)";

  @Mock private AccountService accountService;
  @Mock private PostToAccountService postToAccountService;

  private SplitLineConsistency consistency;

  @BeforeEach
  void setUp() {
    consistency = new SplitLineConsistency(accountService, postToAccountService);
  }

  private static Account account(long id, String name, String type) {
    return new Account(id, name, type, null, "SEK", null, null, null, null, false, false, false);
  }

  /** One-line form as the panel posts it; a blank string is an unset hidden field. */
  private static SplitForm line(
      String text, String id, String direction, String personName, String personDirection) {
    return form(
        List.of(text),
        List.of(id),
        List.of(direction),
        List.of(personName),
        List.of(personDirection));
  }

  private static SplitForm form(
      List<String> texts,
      List<String> ids,
      List<String> directions,
      List<String> personNames,
      List<String> personDirections) {
    MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
    params.put("categoryText", texts);
    params.put("lineCategoryId", ids);
    params.put("lineTransferDirection", directions);
    params.put("linePersonName", personNames);
    params.put("linePersonDirection", personDirections);
    return SplitFormBinder.bind(params);
  }

  @Test
  void acceptsCategoryTextMatchingTheResolvedName() {
    when(accountService.findById(FOOD_ID))
        .thenReturn(Optional.of(account(FOOD_ID, FOOD, "expense")));

    assertThatCode(() -> consistency.verify(line("food", "10", "", "", "")))
        .doesNotThrowAnyException();
  }

  @Test
  void acceptsCategoryTextMatchingTheResolvedPath() {
    when(accountService.findById(FOOD_ID))
        .thenReturn(Optional.of(account(FOOD_ID, "Milk", "expense")));
    when(accountService.findLivePaths(List.of("income", "expense"), " - "))
        .thenReturn(List.of(new AccountPath(FOOD_ID, "Food - Milk")));

    assertThatCode(() -> consistency.verify(line("Food - Milk", "10", "", "", "")))
        .doesNotThrowAnyException();
  }

  @Test
  void refusesStaleCategoryIdWhoseTextWasEdited() {
    when(accountService.findById(FOOD_ID))
        .thenReturn(Optional.of(account(FOOD_ID, FOOD, "expense")));
    when(accountService.findLivePaths(List.of("income", "expense"), " - ")).thenReturn(List.of());

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> consistency.verify(line("Transport", "10", "", "", "")))
        .withMessageContaining("Line 1")
        .withMessageContaining("Transport")
        .withMessageContaining("pick");
  }

  @Test
  void refusesRealAccountIdCarriedWithoutTransferDirection() {
    when(accountService.findById(CASH_ID))
        .thenReturn(Optional.of(account(CASH_ID, CASH_SEK, "asset")));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> consistency.verify(line("NOK", "20", "", "", "")))
        .withMessageContaining("Line 1");
  }

  @Test
  void refusesAnIdWhoseTextWasCleared() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> consistency.verify(line("", "10", "", "", "")))
        .withMessageContaining("Line 1")
        .withMessageContaining("blank");
  }

  @Test
  void acceptsTransferWhoseTextResolvesToTheCarriedAccount() {
    PostToAccount cash = new PostToAccount(account(CASH_ID, CASH_SEK, "asset"), CASH_SEK);
    when(postToAccountService.resolve("Cash-SEK (SEK)"))
        .thenReturn(new PostToResolution.Resolved(cash));

    assertThatCode(() -> consistency.verify(line(TO_CASH_SEK, "20", TO, "", "")))
        .doesNotThrowAnyException();
  }

  @Test
  void refusesTransferTextWhoseDirectionWasLost() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> consistency.verify(line(TO_CASH_SEK, "20", "", "", "")))
        .withMessageContaining("Line 1");
  }

  @Test
  void refusesTransferTextResolvingToDifferentAccountThanCarried() {
    PostToAccount cash = new PostToAccount(account(CASH_ID, CASH_SEK, "asset"), CASH_SEK);
    when(postToAccountService.resolve("Cash-SEK (SEK)"))
        .thenReturn(new PostToResolution.Resolved(cash));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () -> consistency.verify(line(TO_CASH_SEK, String.valueOf(OTHER_ID), TO, "", "")))
        .withMessageContaining("Line 1");
  }

  @Test
  void acceptsPersonLineWhoseTextNamesTheCarriedPerson() {
    assertThatCode(() -> consistency.verify(line("for Max", "", "", "Max", "FOR")))
        .doesNotThrowAnyException();
  }

  @Test
  void refusesPersonLineWhoseTextWasEditedAway() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> consistency.verify(line("Food", "", "", "Max", "FOR")))
        .withMessageContaining("Line 1");
  }

  @Test
  void skipsBlankLinesButNumbersLinesAsDisplayed() {
    when(accountService.findById(FOOD_ID))
        .thenReturn(Optional.of(account(FOOD_ID, FOOD, "expense")));
    when(accountService.findLivePaths(List.of("income", "expense"), " - ")).thenReturn(List.of());
    SplitForm form =
        form(
            List.of(FOOD, "", "Transport"),
            List.of("10", "", "10"),
            List.of("", "", ""),
            List.of("", "", ""),
            List.of("", "", ""));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> consistency.verify(form))
        .withMessageContaining("Line 3");
  }

  @Test
  void comparesNothingWhenTheFormPostedNoLineText() {
    MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
    params.put("lineCategoryId", List.of("10"));

    assertThatCode(() -> consistency.verify(SplitFormBinder.bind(params)))
        .doesNotThrowAnyException();
  }

  @Test
  void refusesPersonTextOverStaleCategoryId() {
    // The operator retyped a resolved category line as "for Max"; Save beat the resolve, so the
    // person fields are still blank and the old category id would be booked.
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> consistency.verify(line("for Max", "10", "", "", "")))
        .withMessageContaining("Line 1");
  }

  @Test
  void leavesUnresolvedPersonLineToTheBinder() {
    // A pending Restore/Create-new decision carries no person name and no id yet.
    assertThatCode(() -> consistency.verify(line("for Max", "", "", "", "")))
        .doesNotThrowAnyException();
  }
}
