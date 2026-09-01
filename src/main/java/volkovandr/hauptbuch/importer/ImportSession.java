package volkovandr.hauptbuch.importer;

import java.time.OffsetDateTime;

/**
 * The single import campaign (import.md §2, §11). One session is {@code open} at a time — the rule
 * that makes the mirror rule (§6) and the commit gate (§9) definable at all; every upload feeds the
 * open one. A campaign that goes wrong is {@code discarded} (the feature's only "undo"), never
 * unwound; a successful commit leaves it {@code committed}.
 *
 * <p>{@code defaultCharset} and {@code defaultDateOrder} are the campaign-wide detection defaults
 * the first staged file establishes and later files inherit (b2); both null until then.
 *
 * @param importSessionId surrogate PK; null for a not-yet-persisted session
 * @param state one of {@link ImportSessionState}'s values
 * @param defaultCharset {@code utf_8} / {@code windows_1252}, or null before the first file
 * @param defaultDateOrder {@code day_month} / {@code month_day} / {@code ambiguous}, or null before
 *     the first file
 * @param startedAt when the session was opened
 * @param committedAt when the commit succeeded; null while the session is open or discarded
 */
public record ImportSession(
    Long importSessionId,
    String state,
    String defaultCharset,
    String defaultDateOrder,
    OffsetDateTime startedAt,
    OffsetDateTime committedAt) {}
