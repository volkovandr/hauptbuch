-- V42 — index statement_match by posting (.scratch/statements/issues/32).
--
-- The existing indexes lead with statement_line_id or statement_id, so "is this posting matched on
-- another statement" (the candidate query's matched_elsewhere) and the join from a match to its
-- posting (neighbour matches) scan the whole table — once per candidate in the first case.
create index statement_match_posting_id_idx on statement_match (posting_id);
