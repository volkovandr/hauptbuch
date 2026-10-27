-- PDF (rules) statement profiles (statements.md §3.2a, statements issue 21): parse rules run
-- locally in place of the AI call. The settings are typed rules_ columns, independent of the csv_
-- ones even where they mean the same; the rules are an ordered child table.
alter table statement_profile drop constraint statement_profile_format_check;
alter table statement_profile
  add constraint statement_profile_format_check check (format in ('csv','pdf','pdf_rules'));

alter table statement_profile
  add column rules_thousands_separator   text,
  add column rules_decimal_separator     text,
  add column rules_minus_position        text
    check (rules_minus_position in ('leading','trailing')),
  add column rules_date_format           text,
  add column rules_continuation          text
    check (rules_continuation in ('previous','next','off')),
  add column rules_smart_space_width     int,
  add column rules_period_start_plus_one boolean;

create table statement_profile_rule (
  statement_profile_rule_id bigint generated always as identity primary key,
  statement_profile_id      bigint not null references statement_profile(statement_profile_id),
  sort_order                int  not null,
  kind                      text not null check (kind in
                              ('header','remove_before','remove_from','remove_between','set','booking')),
  pattern                   text not null,
  pattern_end               text,
  unique (statement_profile_id, sort_order)
);
