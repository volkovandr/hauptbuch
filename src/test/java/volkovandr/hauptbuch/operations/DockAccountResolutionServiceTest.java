package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.PersonResolution;
import volkovandr.hauptbuch.debts.PersonResolutionService;
import volkovandr.hauptbuch.debts.PersonTarget;

/**
 * Unit tier (plan §1.5): the dock's Account field resolves either an own account or a {@code
 * for}/{@code by} person (register §3.3, plan stage 8b.1). The rule that matters is that nothing
 * resolves halfway — an unresolvable value yields neither an id nor a person, so a stale id can
 * never ride along under changed text.
 */
class DockAccountResolutionServiceTest {

  private static final String EUR = "EUR";

  private final AccountService accountService = mock();
  private final PersonResolutionService personResolutionService = mock();
  private final DockAccountResolutionService service =
      new DockAccountResolutionService(accountService, personResolutionService);

  private static Account cash() {
    return new Account(1L, "Cash", "asset", null, EUR, 210, null, null, null, false, false);
  }

  @Test
  void resolvesOwnAccountByBareName() {
    when(accountService.findOwnAccountByName("Cash")).thenReturn(Optional.of(cash()));

    DockAccountResolution resolution = service.resolve("Cash", null);

    assertThat(resolution.accountId()).isEqualTo(1L);
    assertThat(resolution.statusText()).isEqualTo("Cash (EUR)");
    assertThat(resolution.personName()).isNull();
    assertThat(resolution.error()).isNull();
  }

  @Test
  void resolvesOwnAccountByTheLabelTheDatalistOffers() {
    // The datalist offers "Cash (EUR)", so round-tripping that exact string must work.
    when(accountService.findOwnAccountByName("Cash")).thenReturn(Optional.of(cash()));

    assertThat(service.resolve("Cash (EUR)", null).accountId()).isEqualTo(1L);
  }

  @Test
  void refusesCurrencySuffixThatContradictsTheAccount() {
    // Ignoring the suffix would fund the transaction from an account the user did not name.
    when(accountService.findOwnAccountByName("Cash")).thenReturn(Optional.of(cash()));

    DockAccountResolution resolution = service.resolve("Cash (CHF)", null);

    assertThat(resolution.accountId()).isNull();
    assertThat(resolution.error()).contains("EUR").contains("CHF");
  }

  @Test
  void unknownNameResolvesToNeitherIdNorPerson() {
    when(accountService.findOwnAccountByName("Nope")).thenReturn(Optional.empty());

    DockAccountResolution resolution = service.resolve("Nope", null);

    assertThat(resolution.accountId()).isNull();
    assertThat(resolution.personName()).isNull();
    assertThat(resolution.error()).contains("No open account named");
  }

  @Test
  void blankTextResolvesToNothing() {
    DockAccountResolution resolution = service.resolve("   ", null);

    assertThat(resolution.accountId()).isNull();
    assertThat(resolution.error()).isNotNull();
    verify(accountService, never()).findOwnAccountByName(anyString());
  }

  @Test
  void personSigilResolvesToNameAndDirectionNotAnId() {
    // The leaf is provisioned at commit, not here (data-model §7) — so there is no id yet.
    when(personResolutionService.resolve(any(PersonTarget.Parsed.class), isNull()))
        .thenReturn(new PersonResolution.Resolved("Max", "BY", null, "by Max"));

    DockAccountResolution resolution = service.resolve("by Max", null);

    assertThat(resolution.accountId()).isNull();
    assertThat(resolution.personName()).isEqualTo("Max");
    assertThat(resolution.personDirection()).isEqualTo("BY");
    assertThat(resolution.statusText()).isEqualTo("by Max");
    verify(accountService, never()).findOwnAccountByName(anyString());
  }

  @Test
  void pendingRevivalIsSurfacedForTheChoice() {
    when(personResolutionService.resolve(any(PersonTarget.Parsed.class), isNull()))
        .thenReturn(new PersonResolution.Pending("Max"));

    DockAccountResolution resolution = service.resolve("for Max", null);

    assertThat(resolution.pending()).isTrue();
    assertThat(resolution.pendingName()).isEqualTo("Max");
    assertThat(resolution.accountId()).isNull();
    assertThat(resolution.personName()).isNull();
  }

  @Test
  void refusedPersonBecomesFieldError() {
    when(personResolutionService.resolve(any(PersonTarget.Parsed.class), isNull()))
        .thenReturn(new PersonResolution.Refused("More than one person named 'Max'"));

    DockAccountResolution resolution = service.resolve("for Max", null);

    assertThat(resolution.error()).contains("More than one");
    assertThat(resolution.personName()).isNull();
  }

  @Test
  void carriesTheRevivalDecisionThrough() {
    when(personResolutionService.resolve(any(PersonTarget.Parsed.class), eq("REVIVE")))
        .thenReturn(new PersonResolution.Resolved("Max", "FOR", true, "for Max (restoring)"));

    assertThat(service.resolve("for Max", "REVIVE").personRevive()).isEqualTo("true");
  }
}
