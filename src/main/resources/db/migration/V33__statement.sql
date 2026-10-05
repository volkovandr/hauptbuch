-- V33 — bank statements (data-model §15, statements sub-plan slice b1).
--
-- A statement profile says how to read one source; a statement is one uploaded file for one account;
-- its lines are the bank's bookings; a match links a line to a posting. The match is the only stored
-- result of reconciliation (statements.md §4.5) — slice c fills it; the table exists from the start
-- so the schema lands once.
create table statement_profile (
  statement_profile_id  bigint generated always as identity primary key,
  name                  text not null,
  format                text not null check (format in ('csv','pdf')),
  window_days_before    int  not null default 10 check (window_days_before >= 0),
  window_days_after     int  not null default 3  check (window_days_after >= 0),
  ai_note               text,
  csv_delimiter         text,
  csv_quote             text,
  csv_encoding          text,
  csv_skip_rows         int,
  csv_has_header        boolean,
  csv_decimal_separator text,
  csv_date_format       text,
  csv_sign_mode         text check (csv_sign_mode in ('signed','debit_credit')),
  col_booking_date      text,
  col_value_date        text,
  col_amount            text,
  col_debit             text,
  col_credit            text,
  col_currency          text,
  col_counterparty      text,
  col_description       text,
  col_bank_category     text,
  col_iban              text,
  deleted_at            timestamptz
);

create table statement (
  statement_id          bigint generated always as identity primary key,
  statement_profile_id  bigint not null references statement_profile(statement_profile_id),
  account_id            bigint not null references account(account_id),
  state                 text not null check (state in ('new','processing','processed','failed')),
  original_filename     text not null,
  file_path             text not null,
  period_start          date,
  period_end            date,
  opening_balance       numeric(19,4),
  closing_balance       numeric(19,4),
  sent_text             text,
  parse_raw             text,
  parse_error           text,
  tokens_in             int,
  tokens_out            int,
  tokens_cache_write    int,
  tokens_cache_read     int,
  parse_cost            numeric(12,6),
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now(),
  deleted_at            timestamptz
);

create index statement_account_idx on statement (account_id);

create table statement_line (
  statement_line_id      bigint generated always as identity primary key,
  statement_id           bigint not null references statement(statement_id),
  sort_order             int  not null,
  booking_date           date,
  value_date             date,
  amount                 numeric(19,4),
  original_amount        numeric(19,4),
  original_currency_code text references currency(currency_code),
  original_rate          numeric(19,8),
  counterparty           text,
  description            text,
  bank_category          text,
  raw_text               text,
  problem                text
);

create index statement_line_statement_idx on statement_line (statement_id, sort_order);

create table statement_match (
  statement_match_id  bigint generated always as identity primary key,
  statement_line_id   bigint not null unique references statement_line(statement_line_id),
  statement_id        bigint not null references statement(statement_id),
  posting_id          bigint not null references posting(posting_id) on delete cascade,
  matched_at          timestamptz not null default now(),
  unique (statement_id, posting_id)
);
