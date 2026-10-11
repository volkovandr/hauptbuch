-- parse_raw is the CURRENT response text (the AI's body, or its repaired / operator-edited form).
-- parse_raw_original keeps the AI's verbatim body, written once the first time the text changes
-- (issue receipt-processing/34); null while parse_raw is still what the model said.
alter table receipt add column parse_raw_original text;

comment on column receipt.parse_raw is
  'Current raw response text: the AI body, or its auto-repaired / operator-edited form';
comment on column receipt.parse_raw_original is
  'The AI''s verbatim body when parse_raw was changed from it; null while untouched';
