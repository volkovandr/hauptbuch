package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.Currency;
import volkovandr.hauptbuch.ledger.CurrencyService;

/**
 * Integration tier (plan §1.5): the add-a-currency operation end to end against real Postgres. It
 * confirms a currency the V2 seed does not carry can be added with its two system leaves
 * provisioned under the right parents, on both a fresh book and one that already has a category —
 * the category gets no back-filled leaf, because a category's currency-leaf appears lazily
 * (data-model §6.5).
 *
 * <p>{@code @Transactional} rolls each test back on the reused container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class CurrencyProvisioningServiceIntegrationTest {

  private static final String NOK = "NOK";
  private static final String OPENING_BALANCES = "Opening Balances";
  private static final String FX_GAIN_LOSS = "FX gain/loss";

  @Autowired JdbcClient jdbcClient;
  @Autowired CurrencyProvisioningService service;
  @Autowired CurrencyService currencyService;
  @Autowired AccountService accountService;

  @BeforeEach
  void setUp() {
    // NOK is not among the seeded seven, so it is a clean "add a new currency" subject.
    assertThat(currencyService.exists(NOK)).isFalse();
  }

  private List<Account> leavesUnder(String parentName) {
    Account parent = accountService.findTopLevelByName(parentName).orElseThrow();
    return accountService.findChildrenOf(parent.accountId());
  }

  @Test
  void addsCurrencyRowAndBothSystemLeavesOnFreshBook() {
    Currency nok = service.createCurrency("nok", 2, "kr", "Norwegian Krone");

    // The row landed with a normalised (upper-case) code.
    assertThat(nok.code()).isEqualTo(NOK);
    assertThat(currencyService.exists(NOK)).isTrue();

    Account openingLeaf =
        accountService.findLeafUnderParentNamed(OPENING_BALANCES, NOK).orElseThrow();
    assertThat(openingLeaf.name()).isEqualTo("Opening Balances NOK");
    assertThat(openingLeaf.type()).isEqualTo("equity");
    assertThat(openingLeaf.currencyCode()).isEqualTo(NOK);

    Account fxLeaf = accountService.findLeafUnderParentNamed(FX_GAIN_LOSS, NOK).orElseThrow();
    assertThat(fxLeaf.name()).isEqualTo("FX gain/loss NOK");
    assertThat(fxLeaf.type()).isEqualTo("income");
    assertThat(fxLeaf.currencyCode()).isEqualTo(NOK);
  }

  @Test
  void doesNotBackfillLeafUnderExistingCategoryParent() {
    long food =
        jdbcClient
            .sql(
                "insert into account (name, type, currency_code) values ('Food', 'expense', 'EUR') "
                    + "returning account_id")
            .query(Long.class)
            .single();

    service.createCurrency(NOK, 2, "kr", "Norwegian Krone");

    // The category is untouched — no per-currency leaf was manufactured under it (§6.5).
    assertThat(accountService.findChildrenOf(food)).isEmpty();
    // Only the two system parents gained an NOK leaf.
    assertThat(leavesUnder(OPENING_BALANCES))
        .extracting(Account::name)
        .contains("Opening Balances NOK");
    assertThat(leavesUnder(FX_GAIN_LOSS)).extracting(Account::name).contains("FX gain/loss NOK");
  }

  @Test
  void rejectsAnAlreadyExistingCurrency() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> service.createCurrency("EUR", 2, "€", "Euro"))
        .withMessageContaining("already exists");
  }
}
