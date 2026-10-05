package volkovandr.hauptbuch.statements;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.statements.repository.StatementProfileRepository;

/**
 * Statement profiles (statements.md §3.1): validation on top of the plain repository. A profile is
 * reference data, so this is the one place the generic create/update shape is right (CLAUDE.md
 * §1.7); what makes a profile <em>valid</em> lives in {@link StatementCsvParser#validate}.
 */
@Service
public class StatementProfileService {

  private static final Logger LOG = LoggerFactory.getLogger(StatementProfileService.class);

  private final StatementProfileRepository repository;
  private final StatementCsvParser parser;

  StatementProfileService(StatementProfileRepository repository, StatementCsvParser parser) {
    this.repository = repository;
    this.parser = parser;
  }

  /** The live profiles, by name. */
  public List<StatementProfile> live() {
    return repository.findLive();
  }

  /**
   * A live profile by id.
   *
   * @throws StatementFormatException when it does not exist or was deleted
   */
  public StatementProfile get(long statementProfileId) {
    return repository
        .findById(statementProfileId)
        .filter(profile -> profile.deletedAt() == null)
        .orElseThrow(
            () -> new StatementFormatException("That statement profile no longer exists."));
  }

  /**
   * Validate and save {@code submitted}: insert when it has no id, otherwise overwrite.
   *
   * @return the profile's id
   * @throws StatementFormatException naming the first setting that is wrong
   */
  public long save(StatementProfile submitted) {
    StatementProfile profile = normalised(submitted);
    validate(profile);
    if (profile.statementProfileId() == null) {
      long id = repository.insert(profile);
      LOG.info("Created statement profile {}", id);
      return id;
    }
    long id = profile.statementProfileId();
    if (repository.update(id, profile) == 0) {
      throw new StatementFormatException("That statement profile no longer exists.");
    }
    return id;
  }

  /** Soft-delete a profile; statements read through it keep working. */
  public void delete(long statementProfileId) {
    if (repository.softDelete(statementProfileId) > 0) {
      LOG.info("Deleted statement profile {}", statementProfileId);
    }
  }

  /** Validate {@code submitted} as {@link #save} would, without writing. */
  void validate(StatementProfile submitted) {
    if (submitted.name() == null || submitted.name().isBlank()) {
      throw new StatementFormatException("Give the profile a name.");
    }
    if (submitted.windowDaysBefore() < 0 || submitted.windowDaysAfter() < 0) {
      throw new StatementFormatException("The date window cannot be negative.");
    }
    parser.validate(submitted);
  }

  /** {@code submitted} with text trimmed, blank optional columns made null, and defaults filled. */
  static StatementProfile normalised(StatementProfile s) {
    return new StatementProfile(
        s.statementProfileId(),
        s.name() == null ? "" : s.name().strip(),
        StatementProfile.FORMAT_CSV,
        s.windowDaysBefore(),
        s.windowDaysAfter(),
        blankToNull(s.aiNote()),
        s.csvDelimiter(),
        s.csvQuote(),
        blankToNull(s.csvEncoding()) == null ? "UTF-8" : s.csvEncoding().strip(),
        Objects.requireNonNullElse(s.csvSkipRows(), 0),
        Boolean.TRUE.equals(s.csvHasHeader()),
        s.csvDecimalSeparator(),
        s.csvDateFormat() == null ? null : s.csvDateFormat().strip(),
        s.csvSignMode(),
        blankToNull(s.colBookingDate()),
        blankToNull(s.colValueDate()),
        blankToNull(s.colAmount()),
        blankToNull(s.colDebit()),
        blankToNull(s.colCredit()),
        blankToNull(s.colCurrency()),
        blankToNull(s.colCounterparty()),
        blankToNull(s.colDescription()),
        blankToNull(s.colBankCategory()),
        blankToNull(s.colIban()),
        null);
  }

  private static String blankToNull(String text) {
    return text == null || text.isBlank() ? null : text.strip();
  }
}
