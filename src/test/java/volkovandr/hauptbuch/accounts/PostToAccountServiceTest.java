package volkovandr.hauptbuch.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.accounts.repository.AccountRepository;

/**
 * Unit tier (plan §1.5): the post-to set every own-account picker offers, and the resolver that
 * reads a picked or typed label back (issue transaction-register-ui/25) — posting leaves only, each
 * named by its full {@code Parent - Leaf} path, so a group is never offered and two same-named
 * leaves stay tellable apart.
 */
class PostToAccountServiceTest {

  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final String ASSET = "asset";
  private static final LocalDate OPENED = LocalDate.of(2026, 7, 1);

  private final AccountRepository accountRepository = mock();
  private final List<AccountNode> nodes = new ArrayList<>();
  private final PostToAccountService service = new PostToAccountService(accountRepository);

  private void given(Account... accounts) {
    for (Account account : accounts) {
      nodes.add(new AccountNode(account, 0));
    }
    when(accountRepository.findLiveByTypesWithDepth(any())).thenReturn(nodes);
  }

  private static Account own(long id, String name, Long parentId, String currency) {
    return new Account(
        id, name, ASSET, parentId, currency, null, OPENED, null, null, false, false, false);
  }

  private static Account closed(long id, String name) {
    return new Account(id, name, ASSET, null, EUR, null, OPENED, OPENED, null, false, false, false);
  }

  private static Account personLeaf(long id, String name) {
    return new Account(id, name, ASSET, null, EUR, null, OPENED, null, null, false, true, false);
  }

  /** BankAaa holds two cards; Cash is a top-level leaf. */
  private void givenBankWithTwoCards() {
    given(
        own(1L, "BankAaa", null, EUR),
        own(2L, "Credit card", 1L, EUR),
        own(3L, "Debit card", 1L, EUR),
        own(4L, "Cash", null, EUR));
  }

  // ── the post-to set ─────────────────────────────────────────────────────────────

  @Test
  void offersLeavesByPathButNotTheirParent() {
    givenBankWithTwoCards();

    assertThat(service.postToAccounts())
        .extracting(a -> a.account().accountId(), PostToAccount::entryLabel)
        .containsExactly(
            tuple(2L, "BankAaa - Credit card (EUR)"),
            tuple(3L, "BankAaa - Debit card (EUR)"),
            tuple(4L, "Cash (EUR)"));
  }

  @Test
  void leavesOutClosedAccountsAndPersonLeaves() {
    given(own(1L, "Cash", null, EUR), closed(2L, "Old"), personLeaf(3L, "personal.EUR"));

    assertThat(service.postToAccounts()).extracting(PostToAccount::path).containsExactly("Cash");
  }

  @Test
  void parentWhoseOnlyChildIsClosedIsStillNotOffered() {
    // The ledger's leaves-only rule counts every child, so the parent stays unpostable.
    given(
        own(1L, "BankAaa", null, EUR),
        new Account(2L, "Old", ASSET, 1L, EUR, null, OPENED, OPENED, null, false, false, false));

    assertThat(service.postToAccounts()).isEmpty();
  }

  @Test
  void sortsByPathIgnoringCase() {
    given(own(1L, "giro", null, EUR), own(2L, "Cash", null, EUR), own(3L, "BankAaa", null, EUR));

    assertThat(service.postToAccounts())
        .extracting(PostToAccount::path)
        .containsExactly("BankAaa", "Cash", "giro");
  }

  @Test
  void groupOfNamesGroupByItsPathAndNothingElse() {
    // What the id-based pickers (receipt, Settle-up) refuse a posted group with.
    given(
        own(1L, "BankAaa", null, EUR), own(2L, "Cards", 1L, EUR), own(3L, "Credit card", 2L, EUR));

    assertThat(service.groupOf(2L)).contains(new PostToResolution.Group("BankAaa - Cards"));
    assertThat(service.groupOf(3L)).isEmpty();
    assertThat(service.groupOf(99L)).isEmpty();
  }

  // ── resolving a picked or typed label ───────────────────────────────────────────

  private long resolvedId(String text) {
    PostToResolution resolution = service.resolve(text);
    assertThat(resolution).isInstanceOf(PostToResolution.Resolved.class);
    return ((PostToResolution.Resolved) resolution).account().account().accountId();
  }

  @Test
  void resolvesTheLabelThePickerOffers() {
    givenBankWithTwoCards();

    assertThat(resolvedId("BankAaa - Credit card (EUR)")).isEqualTo(2L);
    assertThat(resolvedId("bankaaa - credit card")).isEqualTo(2L);
  }

  @Test
  void resolvesUnambiguousBareName() {
    givenBankWithTwoCards();

    assertThat(resolvedId("Debit card")).isEqualTo(3L);
    assertThat(resolvedId("Cash (EUR)")).isEqualTo(4L);
  }

  @Test
  void refusesGroupNamingIt() {
    givenBankWithTwoCards();

    assertThat(service.resolve("BankAaa (EUR)")).isEqualTo(new PostToResolution.Group("BankAaa"));
  }

  @Test
  void refusesAmbiguousBareNameListingEveryMatch() {
    given(
        own(1L, "BankAaa", null, EUR),
        own(2L, "Credit card", 1L, EUR),
        own(3L, "BankBbb", null, EUR),
        own(4L, "Credit card", 3L, EUR));

    assertThat(service.resolve("Credit card"))
        .isEqualTo(
            new PostToResolution.Ambiguous(
                List.of("BankAaa - Credit card (EUR)", "BankBbb - Credit card (EUR)")));
    assertThat(resolvedId("BankBbb - Credit card")).isEqualTo(4L);
  }

  @Test
  void exactPathOutranksSameNamedNestedLeaf() {
    given(own(1L, "Card", null, EUR), own(2L, "BankAaa", null, EUR), own(3L, "Card", 2L, EUR));

    assertThat(resolvedId("Card")).isEqualTo(1L);
  }

  @Test
  void currencySuffixPicksBetweenSameNamedAccounts() {
    given(own(1L, "Card", null, EUR), own(2L, "Card", null, CHF));

    assertThat(resolvedId("Card (CHF)")).isEqualTo(2L);
    assertThat(service.resolve("Card")).isInstanceOf(PostToResolution.Ambiguous.class);
  }

  @Test
  void refusesSuffixThatContradictsTheAccount() {
    given(own(1L, "Cash", null, EUR));

    assertThat(service.resolve("Cash (CHF)"))
        .isEqualTo(new PostToResolution.WrongCurrency("Cash (EUR)", CHF));
  }

  @Test
  void nameCarryingItsCurrencyRoundTripsItsLabel() {
    given(own(1L, "Card (EUR)", null, EUR), own(2L, "BankAaa-EUR", null, EUR));

    assertThat(resolvedId("Card (EUR)")).isEqualTo(1L);
    assertThat(resolvedId("BankAaa-EUR")).isEqualTo(2L);
  }

  @Test
  void doesNotResolveUnknownClosedOrPersonAccount() {
    given(own(1L, "Cash", null, EUR), closed(2L, "Old"), personLeaf(3L, "personal.EUR"));

    assertThat(service.resolve("Giro")).isEqualTo(new PostToResolution.NotFound("Giro"));
    assertThat(service.resolve("Old")).isInstanceOf(PostToResolution.NotFound.class);
    assertThat(service.resolve("personal.EUR")).isInstanceOf(PostToResolution.NotFound.class);
  }
}
