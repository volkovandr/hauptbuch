package volkovandr.hauptbuch.receipts;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * A deterministic, AI-free repair of the mechanical faults in the model's TOON body (issue
 * receipt-processing/33): an unquoted comma or a stray quote in an item name, an {@code items[N]}
 * that disagrees with the row count, and a code fence or prose around the body. Pure text in → text
 * plus notes out; it never saves anything, the operator reviews the result first.
 *
 * <p>A well-formed row has {@code columns − 1} separating commas. Only the name can hold stray
 * commas, so the trailing {@code columns − 1} cells are peeled off from the right (a quoted cell —
 * a multi-tag cell — counts as one) and everything before them is the name. A row with too few
 * cells is never guessed at: it is flagged as a warning and left as is.
 */
@Component
public class ToonRepair {

  private static final Pattern TABLE_HEADER =
      Pattern.compile("^(\\s*)items\\[(\\d+)]\\{([^}]*)}:\\s*$");
  private static final String MERCHANT = "merchant:";
  private static final String FENCE = "```";

  /** The repaired text, what was changed, and what could not be fixed. */
  public record Result(String text, List<String> changes, List<String> warnings) {
    /** Defensive copies, so the record stays immutable. */
    public Result {
      changes = List.copyOf(changes);
      warnings = List.copyOf(warnings);
    }
  }

  /** Repair {@code raw}; a blank or table-less body comes back as is with no notes. */
  public Result repair(String raw) {
    List<String> changes = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    if (raw == null || raw.isBlank()) {
      return new Result(raw == null ? "" : raw, changes, warnings);
    }
    List<String> lines = new ArrayList<>(List.of(raw.split("\r?\n", -1)));
    stripWrapping(lines, changes);
    repairTable(lines, changes, warnings);
    String text = String.join("\n", lines);
    return new Result(changes.isEmpty() ? raw : text, changes, warnings);
  }

  /** Drop a code fence and any prose around the body: everything before {@code merchant:}. */
  private static void stripWrapping(List<String> lines, List<String> changes) {
    int start = leadingLinesToDrop(lines);
    List<String> before = List.copyOf(lines.subList(0, start));
    lines.subList(0, before.size()).clear();
    boolean closed = stripClosingFence(lines);
    if (closed || before.stream().anyMatch(ToonRepair::isFence)) {
      changes.add("removed the code fence");
    }
    if (before.stream().anyMatch(line -> !line.isBlank() && !isFence(line))) {
      changes.add("removed text before merchant:");
    }
  }

  /** Lines before {@code merchant:}; with no such line, only an opening fence is dropped. */
  private static int leadingLinesToDrop(List<String> lines) {
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).strip().startsWith(MERCHANT)) {
        return i;
      }
    }
    return !lines.isEmpty() && isFence(lines.get(0)) ? 1 : 0;
  }

  /** Remove trailing blank lines and a closing fence; true when a fence was removed. */
  private static boolean stripClosingFence(List<String> lines) {
    while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
      lines.remove(lines.size() - 1);
    }
    boolean closed = !lines.isEmpty() && FENCE.equals(lines.get(lines.size() - 1).strip());
    if (closed) {
      lines.remove(lines.size() - 1);
    }
    return closed;
  }

  private static boolean isFence(String line) {
    return line.strip().startsWith(FENCE);
  }

  private static void repairTable(List<String> lines, List<String> changes, List<String> warnings) {
    int header = findHeader(lines);
    if (header < 0) {
      return;
    }
    Matcher matcher = TABLE_HEADER.matcher(lines.get(header));
    if (!matcher.matches() || !matcher.group(3).startsWith("name,")) {
      return;
    }
    int indent = matcher.group(1).length();
    int columns = matcher.group(3).split(",", -1).length;
    int first = header + 1;
    int last = first;
    while (last < lines.size() && isRow(lines.get(last), indent)) {
      last++;
    }
    int rows = last - first;
    for (int i = first; i < last; i++) {
      repairRow(lines, i, i - first + 1, columns, i == last - 1, changes, warnings);
    }
    if (Integer.parseInt(matcher.group(2)) != rows) {
      changes.add("items[" + matcher.group(2) + "] → items[" + rows + "]");
      lines.set(header, lines.get(header).replaceFirst("items\\[\\d+]", "items[" + rows + "]"));
    }
  }

  private static int findHeader(List<String> lines) {
    for (int i = 0; i < lines.size(); i++) {
      if (TABLE_HEADER.matcher(lines.get(i)).matches()) {
        return i;
      }
    }
    return -1;
  }

  private static boolean isRow(String line, int headerIndent) {
    return !line.isBlank() && line.length() - line.stripLeading().length() > headerIndent;
  }

  private static void repairRow(
      List<String> lines,
      int index,
      int rowNumber,
      int columns,
      boolean lastRow,
      List<String> changes,
      List<String> warnings) {
    String line = lines.get(index);
    String indent = line.substring(0, line.length() - line.stripLeading().length());
    String row = line.strip();
    int end = row.length();
    for (int cell = 0; cell < columns - 1; cell++) {
      int start = cellStart(row, end);
      if (start < 0) {
        warnings.add(
            "row "
                + rowNumber
                + (lastRow
                    ? ": looks incomplete (the response may be cut off) — left as is"
                    : ": has fewer fields than the header — left as is"));
        return;
      }
      end = start;
    }
    String name = row.substring(0, end);
    String fixed = ToonNames.wellFormed(name);
    if (!fixed.equals(name)) {
      changes.add(
          "row "
              + rowNumber
              + (name.contains("\"") ? ": fixed the quotes in the name" : ": quoted the name"));
      lines.set(index, indent + fixed + row.substring(end));
    }
  }

  /**
   * The index of the comma that separates the cell ending at {@code end} (exclusive) from what
   * precedes it, or -1 when there is none. A cell wrapped in quotes is skipped as a whole.
   */
  private static int cellStart(String row, int end) {
    if (end >= 2 && row.charAt(end - 1) == '"') {
      for (int q = end - 2; q >= 1; q--) {
        if (row.charAt(q) == '"' && row.charAt(q - 1) == ',') {
          return q - 1;
        }
        if (row.charAt(q) == '"' && row.charAt(q - 1) != '\\') {
          break;
        }
      }
    }
    return end == 0 ? -1 : row.lastIndexOf(',', end - 1);
  }
}
