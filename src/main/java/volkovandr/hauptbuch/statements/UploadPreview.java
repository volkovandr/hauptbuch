package volkovandr.hauptbuch.statements;

import java.time.LocalDate;
import java.util.List;
import java.util.OptionalLong;

/**
 * What the confirm step of an upload shows: how the profile read the file and which account the
 * file points at.
 *
 * @param lineCount the lines read
 * @param problems the problem text of each line that has one
 * @param periodStart the earliest booking date, or null
 * @param periodEnd the latest booking date, or null
 * @param proposedAccountId the account whose detection labels match the file's IBANs
 */
public record UploadPreview(
    int lineCount,
    List<String> problems,
    LocalDate periodStart,
    LocalDate periodEnd,
    OptionalLong proposedAccountId) {

  /** Keeps the record immutable: the problems are copied in. */
  public UploadPreview {
    problems = List.copyOf(problems);
  }
}
