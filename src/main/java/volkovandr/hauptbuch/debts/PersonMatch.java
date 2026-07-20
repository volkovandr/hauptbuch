package volkovandr.hauptbuch.debts;

import java.util.List;

/**
 * The result of matching a typed name against existing persons by exact name (register §3.5/§7,
 * plan stage 8b) — what the entry dock's {@code for}/{@code by} and Account-field resolution needs
 * to decide whether it can commit straight away, must ask for a revival decision, or must refuse
 * (an ambiguous name — data-model §7 allows duplicate person names, disambiguated in pickers).
 * Duplicates are judged among <em>live</em> persons only; a soft-deleted duplicate never blocks a
 * live match (revival is a separate concern, {@link DeletedOnly}).
 */
public sealed interface PersonMatch {

  /** Exactly one live person has this name — resolve straight to them. */
  record Live(Person person) implements PersonMatch {}

  /**
   * No live person has this name, but a soft-deleted one does (the most recently deleted, if
   * several) — revival must be confirmed, never silent (data-model §7).
   */
  record DeletedOnly(Person person) implements PersonMatch {}

  /** No person, live or deleted, has this name — a genuinely new person. */
  record NotFound() implements PersonMatch {}

  /** More than one live person shares this name — cannot resolve without disambiguation. */
  record Ambiguous(List<Person> matches) implements PersonMatch {
    /** Defensively copy the list so the record cannot be mutated after construction. */
    public Ambiguous {
      matches = List.copyOf(matches);
    }
  }
}
