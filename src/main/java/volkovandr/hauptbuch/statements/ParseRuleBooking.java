package volkovandr.hauptbuch.statements;

import java.util.HashMap;
import java.util.Map;

/**
 * One booking a PDF (rules) profile is assembling (statements.md §3.2a): the set values overlaid by
 * the booking line's own captures, and a description its continuation lines are appended to.
 */
final class ParseRuleBooking {

  private static final String DESCRIPTION = "description";

  private final Map<String, String> fields = new HashMap<>();
  private final Integer smartSpaceWidth;
  private String description;
  private int previousLength = -1;

  /**
   * A booking from the values the set rules hold and the booking line's own captures, which win.
   *
   * @param smartSpaceWidth the column width at which a wrapped line joins the next with no space,
   *     or null for always a space
   */
  ParseRuleBooking(
      Map<String, String> setValues, Map<String, String> own, Integer smartSpaceWidth) {
    fields.putAll(setValues);
    fields.putAll(own);
    this.smartSpaceWidth = smartSpaceWidth;
    String captured = fields.remove(DESCRIPTION);
    description = captured == null ? "" : captured.strip();
  }

  /** Append a continuation line, with no space after a line wrapped at the column width. */
  void append(String line) {
    if (!description.isEmpty()) {
      boolean wrapped = smartSpaceWidth != null && previousLength == smartSpaceWidth;
      description += wrapped ? "" : " ";
    }
    description += line.strip();
    previousLength = line.stripTrailing().length();
  }

  /** The captured fields as raw text, the joined description included when there is one. */
  Map<String, String> rawFields() {
    Map<String, String> raw = new HashMap<>(fields);
    if (!description.isEmpty()) {
      raw.put(DESCRIPTION, description);
    }
    return raw;
  }
}
