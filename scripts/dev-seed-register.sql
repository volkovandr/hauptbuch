-- ─────────────────────────────────────────────────────────────────────────────
-- Dev-only seed data for eyeballing the transaction register (plan stage 7a).
--
-- NOT a Flyway migration and NOT test-suite data — a throwaway script to hand-load a realistic
-- book into the local docker-compose Postgres so the /register screen has something to show.
--
-- Run against the dev DB (see application.yaml — localhost:15432, db/user/pass all "hauptbuch"):
--     psql "postgresql://hauptbuch:hauptbuch@localhost:15432/hauptbuch" -f scripts/dev-seed-register.sql
--
-- Every transaction it books is balanced (postings sum to zero) and single-currency (EUR), so it
-- upholds the model's invariants (data-model §8). It assumes the V1/V2 migrations have run (the
-- currency and system-account rows exist). Safe to run more than once: it skips its own accounts,
-- categories, and payees if they are already present, and only sets the (write-once) base currency
-- when unset. Re-running DOES append another batch of ~45 transactions.
-- ─────────────────────────────────────────────────────────────────────────────

do $$
declare
  base_ccy    text := 'EUR';
  ob_leaf     bigint;   -- the EUR Opening Balances leaf (counter-leg for opening balances)
  giro        bigint;
  cash        bigint;
  visa        bigint;
  salary      bigint;   -- income category leaf
  own_accts   bigint[];
  expense_cats bigint[];
  payees      bigint[];
  cat_names   text[] := array[
    'Groceries', 'Restaurants', 'Transport', 'Fuel', 'Utilities',
    'Rent', 'Entertainment', 'Clothing', 'Health', 'Household'];
  payee_names text[] := array[
    'Rewe', 'Aldi', 'Migros', 'Shell', 'Deutsche Bahn', 'Amazon',
    'IKEA', 'Apotheke', 'Netflix', 'Stadtwerke', 'H&M', 'Rossmann'];
  cat_id      bigint;
  pay_name    text;
  n           int;
  a           bigint;
  cat         bigint;
  pay         bigint;
  amt         numeric(19,4);
  txn         bigint;
  txn_date    date;
begin
  -- ── base currency (write-once; only if unset) ────────────────────────────
  update settings set base_currency = base_ccy where settings_id = 1 and base_currency is null;

  select account_id into ob_leaf
  from account where name = 'Opening Balances ' || base_ccy;

  -- ── own accounts (asset/liability), each with a distinct register hue ─────
  -- Opening balances are booked as real balanced transactions against the OB leaf, dated ~1y ago.
  select account_id into giro from account where name = 'Giro' and type = 'asset';
  if giro is null then
    insert into account (name, type, currency_code, hue, opened_at)
      values ('Giro', 'asset', base_ccy, 210, current_date - interval '1 year')
      returning account_id into giro;
    insert into transaction (date, note) values (current_date - interval '1 year', 'Opening balance')
      returning transaction_id into txn;
    insert into posting (transaction_id, account_id, amount) values (txn, giro, 3200.00);
    insert into posting (transaction_id, account_id, amount) values (txn, ob_leaf, -3200.00);
  end if;

  select account_id into cash from account where name = 'Cash' and type = 'asset';
  if cash is null then
    insert into account (name, type, currency_code, hue, opened_at)
      values ('Cash', 'asset', base_ccy, 140, current_date - interval '1 year')
      returning account_id into cash;
    insert into transaction (date, note) values (current_date - interval '1 year', 'Opening balance')
      returning transaction_id into txn;
    insert into posting (transaction_id, account_id, amount) values (txn, cash, 250.00);
    insert into posting (transaction_id, account_id, amount) values (txn, ob_leaf, -250.00);
  end if;

  select account_id into visa from account where name = 'Visa' and type = 'liability';
  if visa is null then
    insert into account (name, type, currency_code, hue, opened_at)
      values ('Visa', 'liability', base_ccy, 30, current_date - interval '1 year')
      returning account_id into visa;
    -- A card carries a negative (owed) opening balance.
    insert into transaction (date, note) values (current_date - interval '1 year', 'Opening balance')
      returning transaction_id into txn;
    insert into posting (transaction_id, account_id, amount) values (txn, visa, -640.00);
    insert into posting (transaction_id, account_id, amount) values (txn, ob_leaf, 640.00);
  end if;

  own_accts := array[giro, cash, visa];

  -- ── expense categories: top-level EUR leaves (leaves-only, data-model §5) ─
  expense_cats := array[]::bigint[];
  foreach pay_name in array cat_names loop  -- (reuse pay_name as a scratch text var)
    select account_id into cat_id from account where name = pay_name and type = 'expense';
    if cat_id is null then
      insert into account (name, type, currency_code) values (pay_name, 'expense', base_ccy)
        returning account_id into cat_id;
    end if;
    expense_cats := expense_cats || cat_id;
  end loop;

  -- ── income category (Salary) ─────────────────────────────────────────────
  select account_id into salary from account where name = 'Salary' and type = 'income';
  if salary is null then
    insert into account (name, type, currency_code) values ('Salary', 'income', base_ccy)
      returning account_id into salary;
  end if;

  -- ── payees ───────────────────────────────────────────────────────────────
  payees := array[]::bigint[];
  foreach pay_name in array payee_names loop
    select payee_id into pay from payee where name = pay_name;
    if pay is null then
      insert into payee (name) values (pay_name) returning payee_id into pay;
    end if;
    payees := payees || pay;
  end loop;

  -- ── ~40 ordinary expense transactions over the last ~11 months ───────────
  -- Deterministic pseudo-random picks from the pools; every leg pair sums to zero.
  for n in 1..40 loop
    a        := own_accts[1 + (n * 7) % array_length(own_accts, 1)];
    cat      := expense_cats[1 + (n * 3) % array_length(expense_cats, 1)];
    pay      := payees[1 + (n * 5) % array_length(payees, 1)];
    amt      := round((6 + (n * 137 % 900) / 10.0)::numeric, 2);   -- ~6..96
    txn_date := current_date - ((n * 8) % 330);                    -- spread over ~11 months

    insert into transaction (date, payee_id, note)
      values (txn_date, pay, null)
      returning transaction_id into txn;
    insert into posting (transaction_id, account_id, amount) values (txn, a, -amt);   -- funds out
    insert into posting (transaction_id, account_id, amount) values (txn, cat,  amt);  -- category
  end loop;

  -- ── 3 monthly salary credits into Giro (income) ──────────────────────────
  for n in 1..3 loop
    txn_date := current_date - (n * 30);
    insert into transaction (date, note) values (txn_date, 'Monthly salary')
      returning transaction_id into txn;
    insert into posting (transaction_id, account_id, amount) values (txn, giro,   2600.00);
    insert into posting (transaction_id, account_id, amount) values (txn, salary, -2600.00);
  end loop;

  -- ── 2 transfers Giro → Cash (two own-account legs; renders as ⇄ on a single-account view) ──
  for n in 1..2 loop
    txn_date := current_date - (n * 45);
    insert into transaction (date, note) values (txn_date, 'Cash withdrawal')
      returning transaction_id into txn;
    insert into posting (transaction_id, account_id, amount) values (txn, giro, -200.00);
    insert into posting (transaction_id, account_id, amount) values (txn, cash,  200.00);
  end loop;

  -- ── 1 split: Cash pays for two categories at once (Category cell shows both) ──
  insert into transaction (date, payee_id, note)
    values (current_date - 12, payees[1], 'Weekly shop + treat')
    returning transaction_id into txn;
  insert into posting (transaction_id, account_id, amount) values (txn, cash,           -47.50);
  insert into posting (transaction_id, account_id, amount) values (txn, expense_cats[1], 38.00);  -- Groceries
  insert into posting (transaction_id, account_id, amount) values (txn, expense_cats[6],  9.50);  -- Entertainment

  raise notice 'Dev register seed done: 3 accounts, % categories, % payees, ~46 transactions.',
    array_length(expense_cats, 1) + 1, array_length(payees, 1);
end $$;
