package volkovandr.hauptbuch.debts;

import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.accounts.ReservedNamePrefix;

/**
 * Resolves a typed {@code for}/{@code by} sigil against the person roster (register §3.5,
 * data-model §7, plan stage 8b.1) — the single home of the rule, called by <em>both</em> pickers:
 * the Category field's counterpart ({@code categories}' {@code CategoriesController}) and the
 * Account field's funding leg ({@code operations}' {@code RegisterEntryController}).
 *
 * <p>It exists because the Restore/Create-new revival choice is genuinely three-way and must behave
 * identically wherever a person is typed; duplicating it per controller is how the two drift apart.
 * Resolution is read-only — the person and their per-currency leaf are created at <em>commit</em>
 * by {@link PersonProvisioningService}, never here (data-model §7).
 */
@Service
public class PersonResolutionService {

  /** The decision values the pending Restore/Create-new buttons re-post. */
  public static final String DECISION_REVIVE = "REVIVE";

  public static final String DECISION_NEW = "NEW";

  private final PersonService personService;

  PersonResolutionService(PersonService personService) {
    this.personService = personService;
  }

  /**
   * Classify a parsed sigil into what the picker should render (see {@link PersonResolution}).
   *
   * <ul>
   *   <li>exactly one live person, or none at all — {@link PersonResolution.Resolved}, ready to
   *       commit ({@code revive} is {@code null}: there was nothing to decide);
   *   <li>only a soft-deleted person, undecided — {@link PersonResolution.Pending};
   *   <li>only a soft-deleted person, decided — {@code Resolved} carrying the decision;
   *   <li>more than one live person — {@link PersonResolution.Refused}; the resolver must not pick
   *       for the user (data-model §7 allows duplicate live names).
   * </ul>
   *
   * <p>A name beginning with a reserved sigil is refused here rather than thrown, so a typed {@code
   * for for Max} surfaces as an ordinary field message instead of an uncaught 500 (plan stage
   * 8b.1).
   *
   * @param parsed the sigil and name the picker parsed
   * @param decision {@link #DECISION_REVIVE}/{@link #DECISION_NEW}, or {@code null} when the user
   *     has not been asked (or had nothing to decide)
   */
  public PersonResolution resolve(PersonTarget.Parsed parsed, String decision) {
    if (ReservedNamePrefix.isReserved(parsed.personName())) {
      return new PersonResolution.Refused(
          "'"
              + parsed.personName()
              + "' cannot be a person's name — to/from/for/by are entry shortcuts");
    }

    PersonMatch match = personService.matchExact(parsed.personName());

    if (match instanceof PersonMatch.Ambiguous) {
      return new PersonResolution.Refused(
          "More than one person named '"
              + parsed.personName()
              + "' — rename one via the People page to disambiguate");
    }

    boolean decided = DECISION_REVIVE.equals(decision) || DECISION_NEW.equals(decision);
    if (match instanceof PersonMatch.DeletedOnly && !decided) {
      return new PersonResolution.Pending(parsed.personName());
    }

    boolean revive = match instanceof PersonMatch.DeletedOnly && DECISION_REVIVE.equals(decision);
    return new PersonResolution.Resolved(
        parsed.personName(),
        parsed.direction().name(),
        match instanceof PersonMatch.DeletedOnly ? revive : null,
        statusText(parsed, match, revive));
  }

  /** The caption shown beside a resolved person target. */
  private static String statusText(PersonTarget.Parsed parsed, PersonMatch match, boolean revive) {
    String base = PersonTarget.option(parsed.direction(), parsed.personName());
    if (match instanceof PersonMatch.NotFound) {
      return base + " (new person)";
    }
    if (match instanceof PersonMatch.DeletedOnly) {
      return base + (revive ? " (restoring)" : " (new person)");
    }
    return base;
  }
}
