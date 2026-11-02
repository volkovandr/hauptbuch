-- PDF (rules) profiles: remove every whitespace character from a captured date before it is read
-- (statements issue 25), for PDFs whose text puts a space between all characters. NULL = off.
alter table statement_profile
  add column rules_date_remove_spaces boolean;
