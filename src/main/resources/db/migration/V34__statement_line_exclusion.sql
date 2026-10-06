-- The operator's "a different transaction that looks the same" decision on an overlapping line
-- (statements.md §4.4): the posting is not this line's movement, so it is never proposed to it again.
create table statement_line_exclusion (
  statement_line_exclusion_id bigint generated always as identity primary key,
  statement_line_id           bigint not null references statement_line(statement_line_id),
  posting_id                  bigint not null references posting(posting_id) on delete cascade,
  unique (statement_line_id, posting_id)
);
