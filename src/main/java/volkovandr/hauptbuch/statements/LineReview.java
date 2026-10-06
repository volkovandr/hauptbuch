package volkovandr.hauptbuch.statements;

import java.util.List;

/**
 * One statement line with what the matcher found for it.
 *
 * @param line the line
 * @param status where it stands
 * @param match its confirmed match, or null
 * @param candidates the proposals, best tier first, closest date first
 */
public record LineReview(
    StatementLine line,
    LineStatus status,
    StatementMatch match,
    List<ProposedCandidate> candidates) {}
