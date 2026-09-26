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

import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.PostToAccount;
import volkovandr.hauptbuch.accounts.PostToAccountService;
import volkovandr.hauptbuch.accounts.PostToResolution;
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

  private final PostToAccountService postToAccountService = mock();
  private final PersonResolutionService personResolutionService = mock();
  private final DockAccountResolutionService service =
      new DockAccountResolutionService(postToAccountService, personResolutionService);

  private static PostToAccount card() {
    return new PostToAccount(
        new Account(
            2L, "Credit card", "asset", 1L, EUR, 210, null, null, null, false, false, false),
        "BankAaa - Credit card");
  }

  @Test
  void resolvedAccountEchoesItsPickerLabel() {
    when(postToAccountService.resolve("credit card"))
        .thenReturn(new PostToResolution.Resolved(card()));

    DockAccountResolution resolution = service.resolve("  credit card ", null);

    assertThat(resolution.accountId()).isEqualTo(2L);
    assertThat(resolution.statusText()).isEqualTo("BankAaa - Credit card (EUR)");
    assertThat(resolution.personName()).isNull();
    assertThat(resolution.error()).isNull();
  }

  @Test
  void groupIsRefusedWithItsReason() {
    when(postToAccountService.resolve("BankAaa")).thenReturn(new PostToResolution.Group("BankAaa"));

    DockAccountResolution resolution = service.resolve("BankAaa", null);

    assertThat(resolution.accountId()).isNull();
    assertThat(resolution.error()).isEqualTo("'BankAaa' is a group — pick one of its accounts");
  }

  @Test
  void unknownNameResolvesToNeitherIdNorPersonAndSuggestsTheSigils() {
    when(postToAccountService.resolve("Nope")).thenReturn(new PostToResolution.NotFound("Nope"));

    DockAccountResolution resolution = service.resolve("Nope", null);

    assertThat(resolution.accountId()).isNull();
    assertThat(resolution.personName()).isNull();
    assertThat(resolution.error()).contains("No open account named 'Nope'").contains("for Nope");
  }

  @Test
  void blankTextResolvesToNothing() {
    DockAccountResolution resolution = service.resolve("   ", null);

    assertThat(resolution.accountId()).isNull();
    assertThat(resolution.error()).isNotNull();
    verify(postToAccountService, never()).resolve(anyString());
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
    verify(postToAccountService, never()).resolve(anyString());
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
