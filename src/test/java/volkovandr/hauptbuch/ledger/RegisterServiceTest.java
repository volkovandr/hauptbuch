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
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.RegisterView.RegisterAccountOption;
import volkovandr.hauptbuch.ledger.RegisterView.RegisterCategoryOption;
import volkovandr.hauptbuch.ledger.repository.PayeeRepository;
import volkovandr.hauptbuch.ledger.repository.RegisterRepository;
import volkovandr.hauptbuch.ledger.repository.TagReadRepository;

/**
 * Unit tier (plan §1.5): {@link RegisterService}'s orchestration with its collaborators mocked —
 * default-account resolution (register §2.3), the base-currency gate, and the account/payee filter
 * options. The row rendering it delegates to {@link RegisterRowRenderer} is covered in that class's
 * own test; the SQL is covered in {@link RegisterSqlLogicTest}.
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
            tagReadRepository);
    lenient().when(tagReadRepository.liveTagLabels()).thenReturn(List.of());
    lenient().when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));
    lenient().when(payeeRepository.findFilterOptions()).thenReturn(List.of());
    lenient()
        .when(registerRepository.findRows(anyList(), any(), any(), any(), anyString()))
        .thenReturn(List.of());
    lenient().when(rowRenderer.render(anyList())).thenReturn(List.of());
  }

  private static Account ownAccount(long id, String name) {
    return new Account(id, name, ASSET, null, EUR, 210, LocalDate.now(), null, null, false);
  }

  private static Account closed(long id, String name) {
    return new Account(
        id,
        name,
        ASSET,
        null,
        EUR,
        30,
        LocalDate.now().minusYears(1),
        LocalDate.now(),
        null,
        false);
  }

  private static Account category(long id, String name) {
    return new Account(id, name, "expense", null, EUR, null, null, null, null, false);
  }

  private static Account currencyLeaf(long id, String currencyCode, long parentId) {
    return new Account(
        id, currencyCode, "expense", parentId, currencyCode, null, null, null, null, true);
  }

  private RegisterFilter defaultFilter() {
    return new RegisterFilter(List.of(), null, null, null);
  }

  @Test
  void emptyAccountFilterResolvesToOpenOwnAccountsOnly() {
    when(accountService.findLiveByTypes(anyList()))
        .thenReturn(List.of(ownAccount(CASH, "Cash"), closed(GIRO, "Old Giro")));

    RegisterView view = registerService.view(defaultFilter());

    // The default viewed set is the open own accounts; the closed one is neither viewed nor
    // offered.
    verify(registerRepository).findRows(eq(List.of(CASH)), any(), any(), any(), anyString());
    assertThat(view.accounts())
        .extracting(RegisterAccountOption::accountId, RegisterAccountOption::selected)
        .containsExactly(tuple(CASH, true));
  }

  @Test
  void explicitAccountFilterIsUsedVerbatim() {
    when(accountService.findLiveByTypes(anyList()))
        .thenReturn(List.of(ownAccount(CASH, "Cash"), ownAccount(GIRO, "Giro")));

    registerService.view(new RegisterFilter(List.of(GIRO), null, null, null));

    verify(registerRepository).findRows(eq(List.of(GIRO)), any(), any(), any(), anyString());
  }

  @Test
  void freshBookWithoutBaseCurrencyRendersNoRowsAndTouchesNoQuery() {
    when(settingsService.baseCurrency()).thenReturn(Optional.empty());
    when(accountService.findLiveByTypes(anyList())).thenReturn(List.of(ownAccount(CASH, "Cash")));

    RegisterView view = registerService.view(defaultFilter());

    assertThat(view.rows()).isEmpty();
    verify(registerRepository, never()).findRows(anyList(), any(), any(), any(), anyString());
  }

  @Test
  void categoryOptionsExcludeAutoManagedCurrencyLeaves() {
    when(accountService.findLiveByTypes(List.of("asset", "liability")))
        .thenReturn(List.of(ownAccount(CASH, "Cash")));
    when(accountService.findLiveByTypes(List.of("income", "expense")))
        .thenReturn(List.of(category(1L, "Food"), currencyLeaf(2L, "EUR", 1L)));

    RegisterView view = registerService.view(defaultFilter());

    assertThat(view.categories()).extracting(RegisterCategoryOption::name).containsExactly("Food");
  }

  @Test
  void offersToAndFromTransferTargetsForEveryOpenOwnAccount() {
    when(accountService.findLiveByTypes(List.of("asset", "liability")))
        .thenReturn(List.of(ownAccount(CASH, "Cash"), ownAccount(GIRO, "Giro")));
    when(accountService.findLiveByTypes(List.of("income", "expense"))).thenReturn(List.of());

    RegisterView view = registerService.view(defaultFilter());

    // Each open own account contributes a To → and a From ← target (register §3.5, plan stage
    // 7d.3).
    assertThat(view.transferTargets())
        .containsExactly("To → Cash", "From ← Cash", "To → Giro", "From ← Giro");
  }
}
