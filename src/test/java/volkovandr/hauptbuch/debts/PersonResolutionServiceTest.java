package volkovandr.hauptbuch.debts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (plan §1.5): the shared {@code for}/{@code by} resolution both pickers render from
 * (register §3.5, data-model §7, plan stage 8b.1). The load-bearing rule is that reviving a
 * soft-deleted person is <em>never</em> silent — an undecided match is Pending, not Resolved.
 */
class PersonResolutionServiceTest {

  private static final OffsetDateTime DELETED = OffsetDateTime.parse("2026-01-01T00:00:00Z");

  private final PersonService personService = mock();
  private final PersonResolutionService service = new PersonResolutionService(personService);

  private static PersonTarget.Parsed forMax() {
    return PersonTarget.parse("for Max").orElseThrow();
  }

  @Test
  void resolvesLivePersonWithNothingToDecide() {
    when(personService.matchExact("Max"))
        .thenReturn(new PersonMatch.Live(new Person(1L, "Max", null)));

    PersonResolution resolution = service.resolve(forMax(), null);

    assertThat(resolution).isEqualTo(new PersonResolution.Resolved("Max", "FOR", null, "for Max"));
  }

  @Test
  void resolvesUnknownNameAsNewPerson() {
    // Typing an unknown name creates the person at commit — the caption says so up front.
    when(personService.matchExact("Max")).thenReturn(new PersonMatch.NotFound());

    PersonResolution resolution = service.resolve(forMax(), null);

    assertThat(resolution)
        .isEqualTo(new PersonResolution.Resolved("Max", "FOR", null, "for Max (new person)"));
  }

  @Test
  void softDeletedOnlyMatchIsPendingUntilDecided() {
    // Revival is never silent (data-model §7): the picker must ask before this can commit.
    when(personService.matchExact("Max"))
        .thenReturn(new PersonMatch.DeletedOnly(new Person(3L, "Max", DELETED)));

    assertThat(service.resolve(forMax(), null)).isEqualTo(new PersonResolution.Pending("Max"));
  }

  @Test
  void restoreDecisionResolvesToRevival() {
    when(personService.matchExact("Max"))
        .thenReturn(new PersonMatch.DeletedOnly(new Person(3L, "Max", DELETED)));

    PersonResolution resolution =
        service.resolve(forMax(), PersonResolutionService.DECISION_REVIVE);

    assertThat(resolution)
        .isEqualTo(new PersonResolution.Resolved("Max", "FOR", true, "for Max (restoring)"));
  }

  @Test
  void createNewDecisionResolvesToDistinctPerson() {
    // Duplicate names are allowed (data-model §7) — declining revival makes a separate person.
    when(personService.matchExact("Max"))
        .thenReturn(new PersonMatch.DeletedOnly(new Person(3L, "Max", DELETED)));

    PersonResolution resolution = service.resolve(forMax(), PersonResolutionService.DECISION_NEW);

    assertThat(resolution)
        .isEqualTo(new PersonResolution.Resolved("Max", "FOR", false, "for Max (new person)"));
  }

  @Test
  void refusesAmbiguousName() {
    when(personService.matchExact("Max"))
        .thenReturn(
            new PersonMatch.Ambiguous(
                List.of(new Person(1L, "Max", null), new Person(2L, "Max", null))));

    assertThat(service.resolve(forMax(), null))
        .isInstanceOf(PersonResolution.Refused.class)
        .extracting(r -> ((PersonResolution.Refused) r).message())
        .asString()
        .contains("More than one");
  }

  @Test
  void refusesNameBeginningWithReservedSigil() {
    // "for for Max" parses to the person name "for Max" — a field message, never an uncaught 500.
    PersonTarget.Parsed doubled = PersonTarget.parse("for for Max").orElseThrow();

    assertThat(service.resolve(doubled, null))
        .isInstanceOf(PersonResolution.Refused.class)
        .extracting(r -> ((PersonResolution.Refused) r).message())
        .asString()
        .contains("entry shortcuts");
  }

  @Test
  void carriesTheByDirectionThrough() {
    when(personService.matchExact("Max"))
        .thenReturn(new PersonMatch.Live(new Person(1L, "Max", null)));

    PersonResolution resolution = service.resolve(PersonTarget.parse("by Max").orElseThrow(), null);

    assertThat(resolution).isEqualTo(new PersonResolution.Resolved("Max", "BY", null, "by Max"));
  }
}
