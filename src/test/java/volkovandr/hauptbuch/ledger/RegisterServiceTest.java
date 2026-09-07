package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountPath;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.Person;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.ledger.RegisterView.RegisterAccountOption;
import volkovandr.hauptbuch.ledger.repository.PayeeRepository;
import volkovandr.hauptbuch.ledger.repository.RegisterRepository;
import volkovandr.hauptbuch.ledger.repository.TagReadRepository;

/**
 * Unit tier (plan §1.5): {@link RegisterService}'s orchestration with its collaborators mocked —
 * the picker → viewed-accounts hand-off (register §2.3), the base-currency gate, and the dock's
 * datalist / transfer / person targets. Picker membership and the tab-strip model are {@link
 * RegisterPickerService}'s own tests; the SQL is covered in {@link RegisterSqlLogicTest}.
 */
@ExtendWith(MockitoExtension.class)
class RegisterServiceTest {

  private static final String EUR = "EUR";
  private static final String ASSET = "asset";
  private static final long CASH = 10L;
  private static final long GIRO = 11L;

  @Mock private RegisterRepository registerRepository;
  @Mock private PayeeRepository payeeRepository;
  @Mock private AccountService accountService;
  @Mock private SettingsService settingsService;
  @Mock private RegisterRowRenderer rowRenderer;
  @Mock private TagReadRepository tagReadRepository;
  @Mock private PersonService personService;
  @Mock private RegisterPickerService registerPickerService;

  private RegisterService registerService;

  @BeforeEach
  void setUp() {
    registerService =
        new RegisterService(
            registerRepository,
            payeeRepository,
            accountService,
            settingsService,
            rowRenderer,
            tagReadRepository,
            personService,
            registerPickerService);
    lenient().when(tagReadRepository.liveTagLabels()).thenReturn(List.of());
    lenient().when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));
    lenient().when(payeeRepository.findFilterOptions()).thenReturn(List.of());
    lenient().when(personService.findAllLive()).thenReturn(List.of());
    lenient().when(accountService.findLiveByTypes(anyList())).thenReturn(List.of());
    lenient().when(registerPickerService.membership(any(), any(), any())).thenReturn(List.of());
    lenient()
        .when(registerRepository.findRows(anyList(), any(), any(), any(), anyString()))
        .thenReturn(List.of());
    lenient().when(rowRenderer.render(anyList())).thenReturn(List.of());
  }

  private static Account ownAccount(long id, String name) {
    return new Account(
        id, name, ASSET, null, EUR, 210, LocalDate.now(), null, null, false, false, false);
  }

  private RegisterFilter defaultFilter() {
    return new RegisterFilter(List.of(), null, null, null, null);
  }

  @Test
  void emptyAccountSelectionIsResolvedToThePickersMembership() {
    when(registerPickerService.membership(RegisterPicker.LAST_USED, null, null))
        .thenReturn(List.of(CASH, GIRO));

    registerService.view(defaultFilter());

    verify(registerRepository).findRows(eq(List.of(CASH, GIRO)), any(), any(), any(), anyString());
  }

  @Test
  void anExplicitTickSelectionIsUsedVerbatim() {
    registerService.view(new RegisterFilter(List.of(GIRO), RegisterPicker.OPEN, null, null, null));

    verify(registerRepository).findRows(eq(List.of(GIRO)), any(), any(), any(), anyString());
  }

  @Test
  void freshBookWithoutBaseCurrencyRendersNoRowsAndTouchesNoQuery() {
    when(settingsService.baseCurrency()).thenReturn(Optional.empty());

    RegisterView view = registerService.view(defaultFilter());

    assertThat(view.rows()).isEmpty();
    verify(registerRepository, never()).findRows(anyList(), any(), any(), any(), anyString());
  }

  @Test
  void theAccountDatalistOffersOpenNonPersonOwnAccountsOnly() {
    Account personLeaf =
        new Account(
            9L,
            "personal.EUR",
            ASSET,
            null,
            EUR,
            null,
            LocalDate.now(),
            null,
            null,
            false,
            true,
            false);
    when(accountService.findLiveByTypes(List.of("asset", "liability")))
        .thenReturn(List.of(ownAccount(CASH, "Cash"), personLeaf));

    RegisterView view = registerService.view(defaultFilter());

    assertThat(view.accounts()).extracting(RegisterAccountOption::name).containsExactly("Cash");
  }

  @Test
  void categoryOptionsAreComposedLeafPathsSortedByPath() {
    when(accountService.findPostableLeafPaths(List.of("income", "expense"), " - "))
        .thenReturn(List.of(new AccountPath(3L, "Transport"), new AccountPath(2L, "Food - Milk")));

    RegisterView view = registerService.view(defaultFilter());

    assertThat(view.categories())
        .extracting(
            RegisterView.RegisterCategoryOption::accountId,
            RegisterView.RegisterCategoryOption::name)
        .containsExactly(tuple(2L, "Food - Milk"), tuple(3L, "Transport"));
  }

  @Test
  void offersToAndFromTransferTargetsForEveryOpenOwnAccount() {
    when(accountService.findLiveByTypes(List.of("asset", "liability")))
        .thenReturn(List.of(ownAccount(CASH, "Cash"), ownAccount(GIRO, "Giro")));

    RegisterView view = registerService.view(defaultFilter());

    assertThat(view.transferTargets())
        .containsExactly("To → Cash", "From ← Cash", "To → Giro", "From ← Giro");
  }

  @Test
  void offersForAndByPersonTargetsForEveryLivePerson() {
    when(personService.findAllLive())
        .thenReturn(List.of(new Person(1L, "Max", null), new Person(2L, "Alice", null)));

    RegisterView view = registerService.view(defaultFilter());

    assertThat(view.personTargets()).containsExactly("for Max", "by Max", "for Alice", "by Alice");
  }
}
