package volkovandr.hauptbuch.analytics.repository;

/**
 * What one segment of an axis node's key names (reporting.md §9.1): a real account or tag, or one
 * of the two synthetic levels of the per-person debt tree (reporting issue 06) — the single
 * "Personal debts" node and one person beneath it. A person's own currency leaves are real accounts
 * again. The one place these segments are spelled and parsed, so the SQL that mints them and the
 * engine that reads them back cannot drift apart.
 *
 * <p>A key segment is also a Category/Account filter value (reporting.md §6.3): ticking "Personal
 * debts" in the Account filter stores {@link #PERSONAL_DEBTS} (reporting issue 11).
 *
 * @param kind what the segment names
 * @param id the account/tag id for {@link Kind#NODE}, the person id for {@link Kind#PERSON}, {@code
 *     -1} otherwise
 */
public record NodeKey(Kind kind, long id) {

  /** The "Personal debts" node's key: every per-person debt leaf (data-model §7). */
  public static final String PERSONAL_DEBTS = "personal";

  /** The "Personal debts" node's label, on the report axis and in the Account filter alike. */
  public static final String PERSONAL_DEBTS_LABEL = "Personal debts";

  /** A person's key segment is this prefix and their {@code person_id}. */
  static final String PERSON_PREFIX = "person:";

  /** Never a real row's id (every id is a positive bigserial). */
  private static final long NO_ID = -1;

  /** What a key segment names. */
  public enum Kind {
    /** A real account or tag, by id — or, with id {@code -1}, nothing at all. */
    NODE,
    /** The "Personal debts" node: every person's debt leaves. */
    PERSONAL_DEBTS,
    /** One person beneath "Personal debts": that person's debt leaves. */
    PERSON
  }

  /** A person's key segment beneath "Personal debts". */
  public static String personKey(long personId) {
    return PERSON_PREFIX + personId;
  }

  /**
   * What {@code segment} names. A malformed or stale segment (a garbage request param, Tag's own
   * {@code "<id>:unspecified"} bucket, the pre-issue-06 {@code "personal:<CUR>"} bucket) degrades
   * to a {@link Kind#NODE} with id {@code -1}, which matches no row, rather than throwing.
   */
  public static NodeKey parse(String segment) {
    if (PERSONAL_DEBTS.equals(segment)) {
      return new NodeKey(Kind.PERSONAL_DEBTS, NO_ID);
    }
    if (segment.startsWith(PERSON_PREFIX)) {
      long personId = idOrNone(segment.substring(PERSON_PREFIX.length()));
      return personId == NO_ID ? new NodeKey(Kind.NODE, NO_ID) : new NodeKey(Kind.PERSON, personId);
    }
    return new NodeKey(Kind.NODE, idOrNone(segment));
  }

  /**
   * What a (possibly composite, {@code "<parentKey>|<ownKey>"}) frontier key's own node is: its
   * segment after the last {@code "|"}, or the whole key for a top-level node. Every account/tag id
   * is globally unique (a strict single-parent tree), so the last segment alone is always enough to
   * seed the next level's children query, however deep the key nests.
   */
  public static NodeKey ofLastSegment(String key) {
    return parse(key.substring(key.lastIndexOf('|') + 1));
  }

  private static long idOrNone(String digits) {
    try {
      return Long.parseLong(digits);
    } catch (NumberFormatException malformed) {
      return NO_ID;
    }
  }
}
