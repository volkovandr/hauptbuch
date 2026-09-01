package volkovandr.hauptbuch.importer;

/**
 * Resolves a QIF {@code L} or {@code S} value into a canonical {@link ImportedTarget} plus Money's
 * optional {@code /Class} tag suffix (import.md §4.2, §8): {@code Food/Holiday} → category {@code
 * Food} + class {@code Holiday}; {@code [Account]/Holiday} → a transfer to {@code Account} + class
 * {@code Holiday}; {@code Audi:Fuel} → just the category path. No id is resolved here (§3).
 */
final class QifTarget {

  private QifTarget() {}

  /** A resolved target and its class suffix (null when there is none, or it was destroyed). */
  record Resolved(ImportedTarget target, String className) {}

  static Resolved resolve(String rawValue) {
    int slash = classSlash(rawValue);
    String targetText = slash < 0 ? rawValue : rawValue.substring(0, slash);
    String className = slash < 0 ? null : rawValue.substring(slash + 1);
    if (className != null && QifText.isDestroyed(className)) {
      className = null; // §8: a destroyed class contributes nothing, never a "????" tag
    }
    return new Resolved(toTarget(targetText), className);
  }

  /** Index of the {@code /} starting the class suffix, or -1 — after {@code ]} for a transfer. */
  private static int classSlash(String rawValue) {
    if (rawValue.startsWith("[")) {
      int close = rawValue.indexOf(']');
      return close >= 0 && close + 1 < rawValue.length() && rawValue.charAt(close + 1) == '/'
          ? close + 1
          : -1;
    }
    return rawValue.indexOf('/');
  }

  private static ImportedTarget toTarget(String targetText) {
    if (targetText.startsWith("[") && targetText.endsWith("]")) {
      return new ImportedTarget.AccountReference(targetText.substring(1, targetText.length() - 1));
    }
    return new ImportedTarget.CategoryPath(targetText);
  }
}
